package com.craftpilot.clientpolicy.command;

import com.craftpilot.clientpolicy.ClientPolicyPlugin;
import com.craftpilot.clientpolicy.config.Ladder;
import com.craftpilot.clientpolicy.config.PluginConfig;
import com.craftpilot.clientpolicy.config.PolicyProfile;
import com.craftpilot.clientpolicy.config.ProfileMode;
import com.craftpilot.clientpolicy.fingerprint.ClientFingerprint;
import com.craftpilot.clientpolicy.policy.EvaluationResult;
import com.craftpilot.clientpolicy.policy.ResolvedProfile;
import com.craftpilot.clientpolicy.policy.Violation;
import com.craftpilot.clientpolicy.signature.Category;
import com.craftpilot.clientpolicy.signature.ModSignature;
import com.craftpilot.clientpolicy.signature.SignatureRegistry;
import com.craftpilot.clientpolicy.store.DetectionRecord;
import com.craftpilot.clientpolicy.util.Msg;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The single root command. Everything the administrator needs to verify and tune
 * the signature database lives here, because a signature list that cannot be
 * checked against a real client is the fastest way to get false positives.
 */
public final class ClientPolicyCommand implements CommandExecutor, TabCompleter {

    private static final String ADMIN_PERMISSION = "clientpolicy.admin";
    private static final String RULES_PERMISSION = "clientpolicy.rules";
    private static final String CHECK_PERMISSION = "clientpolicy.check";

    private static final List<String> SUBCOMMANDS =
            List.of("rules", "check", "inspect", "history", "profile", "rescan", "reload");

    private static final int DEFAULT_HISTORY_LIMIT = 10;
    private static final int MAX_HISTORY_LIMIT = 100;

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final ClientPolicyPlugin plugin;

    public ClientPolicyCommand(ClientPolicyPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, String @NotNull [] args) {
        if (args.length == 0) {
            sendHelp(sender, label);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "rules" -> {
                if (require(sender, RULES_PERMISSION)) {
                    handleRules(sender);
                }
            }
            case "check" -> {
                if (require(sender, CHECK_PERMISSION)) {
                    handleCheck(sender);
                }
            }
            case "inspect" -> {
                if (require(sender, ADMIN_PERMISSION)) {
                    handleInspect(sender, args);
                }
            }
            case "history" -> {
                if (require(sender, ADMIN_PERMISSION)) {
                    handleHistory(sender, args);
                }
            }
            case "profile" -> {
                if (require(sender, ADMIN_PERMISSION)) {
                    handleProfile(sender, args);
                }
            }
            case "rescan" -> {
                if (require(sender, ADMIN_PERMISSION)) {
                    handleRescan(sender, args);
                }
            }
            case "reload" -> {
                if (require(sender, ADMIN_PERMISSION)) {
                    handleReload(sender);
                }
            }
            default -> sendHelp(sender, label);
        }
        return true;
    }

    // ------------------------------------------------------------------ rules

    private void handleRules(CommandSender sender) {
        PluginConfig config = plugin.config();
        PolicyProfile profile;
        String reason;
        if (sender instanceof Player player) {
            ResolvedProfile resolved = plugin.policy().resolveProfile(player);
            profile = resolved.profile();
            reason = resolved.reason();
        } else {
            profile = config.defaultProfile();
            reason = "policy.default-profile";
        }

        send(sender, "<gray>Client mod rules for profile <aqua><profile> <dark_gray>(<mode>)",
                Msg.ph("profile", profile.name()), Msg.ph("mode", profile.mode().name()));

        if (profile.mode() == ProfileMode.ALLOWLIST) {
            sendRaw(sender, "<gray>Only the mods below are permitted. Anything else counts as a violation.");
            sendGrouped(sender, "<green>Permitted", profile.allow(), "<green>");
            if (!profile.deny().isEmpty()) {
                sendGrouped(sender, "<red>Explicitly blocked", profile.deny(), "<red>");
            }
        } else {
            sendRaw(sender, "<gray>Everything is permitted except the mods below.");
            sendGrouped(sender, "<red>Not permitted", profile.deny(), "<red>");
            if (!profile.allow().isEmpty()) {
                sendGrouped(sender, "<green>Explicitly permitted", profile.allow(), "<green>");
            }
        }

        Ladder ladder = profile.ladder();
        sendRaw(sender, "<gray>If a blocked mod is found: <yellow><first> <gray>for the first <count> time(s), "
                        + "then <red><after><gray>.",
                Msg.ph("first", ladder.first().name()),
                Msg.ph("count", String.valueOf(ladder.escalateAfter())),
                Msg.ph("after", ladder.after().name()));
        sendRaw(sender, "<dark_gray>Profile chosen by: <reason>", Msg.ph("reason", reason));
        sendRaw(sender, config.message("disclaimer",
                "<dark_gray><i>ClientPolicy only sees mods that announce themselves to the server.</i>"));
    }

    /** Prints the ids of one list, grouped by category, with honest detectability notes. */
    private void sendGrouped(CommandSender sender, String heading, Set<String> ids, String colour) {
        if (ids.isEmpty()) {
            sendRaw(sender, heading + "<gray>: <dark_gray>(none)");
            return;
        }
        SignatureRegistry registry = plugin.signatures();
        Map<Category, List<String>> grouped = new EnumMap<>(Category.class);

        for (String id : ids) {
            ModSignature signature = registry.byId(id);
            Category category = signature != null ? signature.category() : Category.OTHER;
            String label;
            if (signature == null) {
                label = id + " (no signature defined)";
            } else if (!signature.detectable()) {
                label = signature.display() + " (not detectable)";
            } else {
                label = signature.display();
            }
            grouped.computeIfAbsent(category, key -> new ArrayList<>()).add(label);
        }

        sendRaw(sender, heading + "<gray>:");
        for (Map.Entry<Category, List<String>> entry : grouped.entrySet()) {
            sendRaw(sender, "<dark_gray>  <category><gray>: " + colour + "<mods>",
                    Msg.ph("category", entry.getKey().display()),
                    Msg.ph("mods", String.join(", ", entry.getValue())));
        }
    }

    // ------------------------------------------------------------------ check

    private void handleCheck(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sendRaw(sender, plugin.config().message("players-only", "<red>Only a player can run this command."));
            return;
        }
        ClientFingerprint fingerprint = plugin.fingerprints().getOrCreate(player);
        plugin.fingerprints().refresh(player, fingerprint);
        EvaluationResult result = plugin.policy().evaluate(player, fingerprint, false);

        send(sender, "<gray>This is everything the server can see about your client:");
        sendFingerprintBody(sender, result, fingerprint, false);
        sendRaw(sender, plugin.config().message("disclaimer",
                "<dark_gray><i>ClientPolicy only sees mods that announce themselves to the server.</i>"));
    }

    // ------------------------------------------------------------------ inspect

    private void handleInspect(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sendRaw(sender, "<red>Usage: /clientpolicy inspect <player>");
            return;
        }
        Player target = onlineTarget(sender, args[1]);
        if (target == null) {
            return;
        }
        ClientFingerprint fingerprint = plugin.fingerprints().getOrCreate(target);
        plugin.fingerprints().refresh(target, fingerprint);
        EvaluationResult result = plugin.policy().evaluate(target, fingerprint, false);

        send(sender, "<gray>Fingerprint of <white><player>",
                Msg.ph("player", target.getName()));
        sendFingerprintBody(sender, result, fingerprint, true);
    }

    /** Shared rendering for {@code check} and {@code inspect}. */
    private void sendFingerprintBody(CommandSender sender, EvaluationResult result,
                                     ClientFingerprint fingerprint, boolean verbose) {
        String brand = result.brand();
        sendRaw(sender, "<dark_gray>  Brand<gray>: <white><brand> <dark_gray>| Loader<gray>: <white><loader>",
                Msg.ph("brand", brand == null || brand.isBlank() ? "(not reported yet)" : brand),
                Msg.ph("loader", result.loader().name()));

        sendRaw(sender, "<dark_gray>  Profile<gray>: <aqua><profile> <dark_gray>(<mode>, via <reason>)",
                Msg.ph("profile", result.profile().name()),
                Msg.ph("mode", result.profile().profile().mode().name()),
                Msg.ph("reason", result.profile().reason()));

        if (result.matched().isEmpty()) {
            sendRaw(sender, "<dark_gray>  Recognised mods<gray>: <dark_gray>(none)");
        } else {
            sendRaw(sender, "<dark_gray>  Recognised mods<gray>:");
            for (Map.Entry<ModSignature, List<String>> entry : result.matched().entrySet()) {
                ModSignature signature = entry.getKey();
                boolean violation = result.violations().stream()
                        .anyMatch(v -> v.modId().equals(signature.id()));
                sendRaw(sender, "<dark_gray>    " + (violation ? "<red>" : "<green>")
                                + "<mod> <dark_gray>[<id>, <category>] <gray>via <white><channels>",
                        Msg.ph("mod", signature.display()),
                        Msg.ph("id", signature.id()),
                        Msg.ph("category", signature.category().display()),
                        Msg.ph("channels", String.join(", ", entry.getValue())));
            }
        }

        if (!result.unknownChannels().isEmpty()) {
            sendRaw(sender, "<dark_gray>  Unrecognised channels<gray> (<count>)<gray>: <yellow><channels>",
                    Msg.ph("count", String.valueOf(result.unknownChannels().size())),
                    Msg.ph("channels", String.join(", ", result.unknownChannels())));
            if (verbose) {
                sendRaw(sender, "<dark_gray>    Copy these into signatures.yml to name the mod behind them.");
            }
        }

        if (verbose) {
            List<String> all = fingerprint.sortedChannels();
            sendRaw(sender, "<dark_gray>  Raw channels<gray> (<count>)<gray>: <white><channels>",
                    Msg.ph("count", String.valueOf(all.size())),
                    Msg.ph("channels", all.isEmpty() ? "(none)" : String.join(", ", all)));
            if (!result.ignoredChannels().isEmpty()) {
                sendRaw(sender, "<dark_gray>  Ignored by config<gray>: <dark_gray><channels>",
                        Msg.ph("channels", String.join(", ", result.ignoredChannels())));
            }
            sendRaw(sender, "<dark_gray>  Scan state<gray>: first scan <white><scanned><gray>, "
                            + "rescan window <white><window>",
                    Msg.ph("scanned", fingerprint.initialScanDone() ? "done" : "pending"),
                    Msg.ph("window", fingerprint.rescanDeadline() > System.currentTimeMillis() ? "open" : "closed"));
        }

        if (result.clean()) {
            sendRaw(sender, "<dark_gray>  Verdict<gray>: <green>no violations");
            return;
        }
        sendRaw(sender, "<dark_gray>  Verdict<gray>: <red><count> violation(s)",
                Msg.ph("count", String.valueOf(result.violations().size())));
        for (Violation violation : result.violations()) {
            sendRaw(sender, "<dark_gray>    <red><mod> <dark_gray>(<reason>)",
                    Msg.ph("mod", violation.display()),
                    Msg.ph("reason", violation.reason().name()));
        }
    }

    // ------------------------------------------------------------------ history

    private void handleHistory(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sendRaw(sender, "<red>Usage: /clientpolicy history <player> [limit]");
            return;
        }
        int limit = DEFAULT_HISTORY_LIMIT;
        if (args.length >= 3) {
            try {
                limit = Math.min(MAX_HISTORY_LIMIT, Math.max(1, Integer.parseInt(args[2])));
            } catch (NumberFormatException ex) {
                sendRaw(sender, "<red>'<value>' is not a number.", Msg.ph("value", args[2]));
                return;
            }
        }

        UUID uuid = resolveUuid(args[1]);
        if (uuid == null) {
            sendRaw(sender, plugin.config().message("player-not-found",
                    "<red>Player <white><player></white> is not online."), Msg.ph("player", args[1]));
            return;
        }
        if (!plugin.store().available()) {
            sendRaw(sender, plugin.config().message("storage-unavailable",
                    "<red>The detection database is unavailable, check the server log."));
            return;
        }

        String name = args[1];
        int finalLimit = limit;
        plugin.runAsync(() -> {
            List<DetectionRecord> records = plugin.store().history(uuid, finalLimit);
            plugin.runSync(() -> {
                if (records.isEmpty()) {
                    sendRaw(sender, plugin.config().message("history-empty",
                            "<gray>No records found for <white><player></white>."), Msg.ph("player", name));
                    return;
                }
                send(sender, "<gray>Last <count> record(s) for <white><player>",
                        Msg.ph("count", String.valueOf(records.size())),
                        Msg.ph("player", records.get(0).name()));
                SignatureRegistry registry = plugin.signatures();
                for (DetectionRecord record : records) {
                    sendRaw(sender, "<dark_gray>  <time> <gray>| <yellow><action> <gray>| <white><mod> "
                                    + "<dark_gray>(<profile>)",
                            Msg.ph("time", TIMESTAMP.format(Instant.ofEpochMilli(record.createdAt()))),
                            Msg.ph("action", record.action()),
                            Msg.ph("mod", registry.displayOf(record.modId())),
                            Msg.ph("profile", record.profile()));
                }
            });
        });
    }

    // ------------------------------------------------------------------ profile

    private void handleProfile(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sendRaw(sender, "<red>Usage: /clientpolicy profile <player>");
            return;
        }
        Player target = onlineTarget(sender, args[1]);
        if (target == null) {
            return;
        }
        ResolvedProfile resolved = plugin.policy().resolveProfile(target);
        PolicyProfile profile = resolved.profile();
        Ladder ladder = profile.ladder();

        send(sender, "<white><player> <gray>uses profile <aqua><profile>",
                Msg.ph("player", target.getName()), Msg.ph("profile", profile.name()));
        sendRaw(sender, "<dark_gray>  Chosen because of<gray>: <white><reason>",
                Msg.ph("reason", resolved.reason()));
        sendRaw(sender, "<dark_gray>  Mode<gray>: <white><mode> <dark_gray>| allow: <allow> | deny: <deny>",
                Msg.ph("mode", profile.mode().name()),
                Msg.ph("allow", String.valueOf(profile.allow().size())),
                Msg.ph("deny", String.valueOf(profile.deny().size())));
        sendRaw(sender, "<dark_gray>  Ladder<gray>: <white><first> <gray>until hit <white><count><gray>, "
                        + "then <white><after> <dark_gray>(counter resets after <hours>h)",
                Msg.ph("first", ladder.first().name()),
                Msg.ph("count", String.valueOf(ladder.escalateAfter())),
                Msg.ph("after", ladder.after().name()),
                Msg.ph("hours", String.valueOf(ladder.counterResetMillis() / 3_600_000L)));
        sendRaw(sender, "<dark_gray>  Bypass<gray>: <white><bypass> <dark_gray>| Notify: <notify>",
                Msg.ph("bypass", String.valueOf(target.hasPermission("clientpolicy.bypass"))),
                Msg.ph("notify", String.valueOf(target.hasPermission("clientpolicy.notify"))));

        List<String> held = new ArrayList<>();
        for (PolicyProfile candidate : plugin.config().profiles().values()) {
            if (target.hasPermission(candidate.permission())) {
                held.add(candidate.name());
            }
        }
        sendRaw(sender, "<dark_gray>  Profile permissions held<gray>: <white><held>",
                Msg.ph("held", held.isEmpty() ? "(none)" : String.join(", ", held)));
    }

    // ------------------------------------------------------------------ rescan

    private void handleRescan(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sendRaw(sender, "<red>Usage: /clientpolicy rescan <player>");
            return;
        }
        Player target = onlineTarget(sender, args[1]);
        if (target == null) {
            return;
        }
        plugin.fingerprints().forceRescan(target);
        sendRaw(sender, plugin.config().message("rescan-done", "<green>Re-evaluated <white><player></white>."),
                Msg.ph("player", target.getName()));
    }

    // ------------------------------------------------------------------ reload

    private void handleReload(CommandSender sender) {
        plugin.reloadEverything(() -> {
            PluginConfig config = plugin.config();
            sendRaw(sender, config.message("reload-success",
                            "<green>Reloaded. <gray><signatures> signatures, <profiles> profiles, "
                                    + "<players> online players re-evaluated."),
                    Msg.ph("signatures", String.valueOf(plugin.signatures().size())),
                    Msg.ph("profiles", String.valueOf(config.profiles().size())),
                    Msg.ph("players", String.valueOf(Bukkit.getOnlinePlayers().size())));
        });
    }

    // ------------------------------------------------------------------ tab complete

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String label, String @NotNull [] args) {
        if (args.length == 1) {
            List<String> available = new ArrayList<>();
            for (String sub : SUBCOMMANDS) {
                if (hasPermissionFor(sender, sub)) {
                    available.add(sub);
                }
            }
            return filter(available, args[0]);
        }
        if (args.length == 2 && sender.hasPermission(ADMIN_PERMISSION)) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("inspect") || sub.equals("history") || sub.equals("profile") || sub.equals("rescan")) {
                List<String> names = new ArrayList<>();
                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (sender instanceof Player viewer && !viewer.canSee(player)) {
                        continue;
                    }
                    names.add(player.getName());
                }
                return filter(names, args[1]);
            }
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("history") && sender.hasPermission(ADMIN_PERMISSION)) {
            return filter(List.of("10", "25", "50", "100"), args[2]);
        }
        return List.of();
    }

    private boolean hasPermissionFor(CommandSender sender, String sub) {
        return switch (sub) {
            case "rules" -> sender.hasPermission(RULES_PERMISSION);
            case "check" -> sender.hasPermission(CHECK_PERMISSION);
            default -> sender.hasPermission(ADMIN_PERMISSION);
        };
    }

    private static List<String> filter(Collection<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(lower)) {
                matches.add(option);
            }
        }
        return matches;
    }

    // ------------------------------------------------------------------ helpers

    private void sendHelp(CommandSender sender, String label) {
        send(sender, "<gray>Commands <dark_gray>(/<label>)", Msg.ph("label", label));
        if (sender.hasPermission(RULES_PERMISSION)) {
            sendRaw(sender, "<dark_gray>  /<label> rules <gray>- which mods are allowed for you",
                    Msg.ph("label", label));
        }
        if (sender.hasPermission(CHECK_PERMISSION)) {
            sendRaw(sender, "<dark_gray>  /<label> check <gray>- what the server sees on your client",
                    Msg.ph("label", label));
        }
        if (sender.hasPermission(ADMIN_PERMISSION)) {
            sendRaw(sender, "<dark_gray>  /<label> inspect <player> <gray>- raw channels, brand and matches",
                    Msg.ph("label", label));
            sendRaw(sender, "<dark_gray>  /<label> history <player> [limit] <gray>- past detections",
                    Msg.ph("label", label));
            sendRaw(sender, "<dark_gray>  /<label> profile <player> <gray>- resolved profile and why",
                    Msg.ph("label", label));
            sendRaw(sender, "<dark_gray>  /<label> rescan <player> <gray>- re-apply the policy now",
                    Msg.ph("label", label));
            sendRaw(sender, "<dark_gray>  /<label> reload <gray>- reload config.yml and signatures.yml",
                    Msg.ph("label", label));
        }
    }

    private boolean require(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) {
            return true;
        }
        sendRaw(sender, plugin.config().message("no-permission", "<red>You do not have permission to do that."));
        return false;
    }

    /** Online lookup with a consistent "not found" message. */
    private Player onlineTarget(CommandSender sender, String name) {
        Player target = Bukkit.getPlayerExact(name);
        if (target == null) {
            sendRaw(sender, plugin.config().message("player-not-found",
                    "<red>Player <white><player></white> is not online."), Msg.ph("player", name));
        }
        return target;
    }

    /**
     * Resolves a name for history lookups. Falls back to Paper's cache rather than
     * {@code getOfflinePlayer(String)}, which would hit the Mojang API on this thread.
     */
    private UUID resolveUuid(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online.getUniqueId();
        }
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        return cached == null ? null : cached.getUniqueId();
    }

    private void send(CommandSender sender, String raw, TagResolver... resolvers) {
        sender.sendMessage(Msg.parse(plugin.config().prefix(), raw, resolvers));
    }

    private void sendRaw(CommandSender sender, String raw, TagResolver... resolvers) {
        sender.sendMessage(Msg.parse(raw, resolvers));
    }
}
