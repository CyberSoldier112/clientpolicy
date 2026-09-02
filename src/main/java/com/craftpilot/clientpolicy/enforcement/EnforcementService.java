package com.craftpilot.clientpolicy.enforcement;

import com.craftpilot.clientpolicy.ClientPolicyPlugin;
import com.craftpilot.clientpolicy.config.Action;
import com.craftpilot.clientpolicy.config.Ladder;
import com.craftpilot.clientpolicy.config.PluginConfig;
import com.craftpilot.clientpolicy.fingerprint.ClientFingerprint;
import com.craftpilot.clientpolicy.policy.EvaluationResult;
import com.craftpilot.clientpolicy.policy.ResolvedProfile;
import com.craftpilot.clientpolicy.policy.Violation;
import com.craftpilot.clientpolicy.store.DetectionRecord;
import com.craftpilot.clientpolicy.util.Msg;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Turns the verdict of the policy engine into consequences.
 *
 * <p>Split across two threads on purpose: the decision needs the violation counter,
 * which lives in SQLite, while kicking and messaging need the main thread. So the
 * flow is main (evaluate, filter) -&gt; async (count, record) -&gt; main (act).</p>
 *
 * <p>Each mod is acted on at most once per session. That is what makes the ladder
 * count logins rather than rescans: a player who is warned at their first scan is
 * not warned again when a second channel arrives ten seconds later.</p>
 */
public final class EnforcementService {

    public static final String BYPASS_PERMISSION = "clientpolicy.bypass";
    public static final String NOTIFY_PERMISSION = "clientpolicy.notify";

    private final ClientPolicyPlugin plugin;

    public EnforcementService(ClientPolicyPlugin plugin) {
        this.plugin = plugin;
    }

    /** Decided outcome for one violation. */
    private record Decision(Violation violation, Action action, int count) {
    }

    /**
     * Evaluates the fingerprint and applies whatever the profile asks for.
     * Must be called from the main thread.
     */
    public void evaluateAndEnforce(Player player, ClientFingerprint fingerprint, boolean finalPass) {
        EvaluationResult result = plugin.policy().evaluate(player, fingerprint, finalPass);

        List<Violation> pending = new ArrayList<>();
        for (Violation violation : result.violations()) {
            if (fingerprint.markEnforced(violation.modId())) {
                pending.add(violation);
            }
        }
        if (pending.isEmpty()) {
            return;
        }

        ResolvedProfile resolved = result.profile();
        Ladder ladder = resolved.profile().ladder();
        boolean bypass = player.hasPermission(BYPASS_PERMISSION);
        UUID uuid = player.getUniqueId();
        String name = player.getName();
        String brand = result.brand();

        plugin.runAsync(() -> {
            long now = System.currentTimeMillis();
            List<Decision> decisions = new ArrayList<>(pending.size());

            for (Violation violation : pending) {
                int count = 1;
                Action action;
                if (violation.usesLadder()) {
                    count = plugin.store().bumpCounter(uuid, violation.modId(), now, ladder.counterResetMillis());
                    action = ladder.actionFor(count);
                } else {
                    action = violation.fixedAction();
                }
                if (action == Action.ALLOW) {
                    continue; // explicitly configured to be a non-event, so not even a log line
                }
                plugin.store().recordDetection(DetectionRecord.of(uuid, name, violation.modId(),
                        violation.channelsJoined(), brand, resolved.name(), action.name(), now));
                decisions.add(new Decision(violation, action, count));
            }

            if (!decisions.isEmpty()) {
                plugin.runSync(() -> apply(uuid, name, resolved, decisions, bypass));
            }
        });
    }

    /** Main thread: messages the player, the staff and the console. */
    private void apply(UUID uuid, String name, ResolvedProfile profile, List<Decision> decisions, boolean bypass) {
        PluginConfig config = plugin.config();
        Player player = Bukkit.getPlayer(uuid);
        boolean online = player != null && player.isOnline();

        Decision kick = null;
        for (Decision decision : decisions) {
            logToConsole(name, decision, profile, bypass);
            // LOG means "record it, do not bother anyone". Broadcasting it would spam
            // staff on every join, because unknown-channel-action defaults to LOG and
            // a modded client easily brings a dozen uncatalogued channels with it.
            if (config.notifyStaff() && decision.action() != Action.LOG) {
                notifyStaff(name, decision, profile, bypass);
            }
            if (decision.action() == Action.KICK && kick == null) {
                kick = decision;
            }
        }

        if (bypass || !online) {
            return;
        }

        if (kick != null) {
            // A kick supersedes the warnings from the same pass; sending both would
            // only flash text the player never gets to read.
            player.kick(Msg.parse(config.kickMessage(), resolvers(name, kick, profile)));
            return;
        }
        for (Decision decision : decisions) {
            if (decision.action() == Action.WARN) {
                player.sendMessage(Msg.parse(config.prefix(), config.warnMessage(),
                        resolvers(name, decision, profile)));
            }
        }
    }

    private void notifyStaff(String name, Decision decision, ResolvedProfile profile, boolean bypass) {
        PluginConfig config = plugin.config();
        Component message = Msg.parse(config.prefix(), config.notifyFormat(), resolvers(name, decision, profile));
        if (bypass) {
            message = message.append(Msg.parse(" <dark_gray>[bypassed]"));
        }
        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (staff.hasPermission(NOTIFY_PERMISSION) && !staff.getName().equals(name)) {
                staff.sendMessage(message);
            }
        }
    }

    private void logToConsole(String name, Decision decision, ResolvedProfile profile, boolean bypass) {
        Violation violation = decision.violation();
        plugin.getLogger().info(name + " -> " + violation.modId()
                + " [" + violation.reason() + "]"
                + " channels=" + (violation.channels().isEmpty() ? "-" : violation.channelsJoined())
                + " profile=" + profile.name()
                + " hit=" + decision.count()
                + " action=" + decision.action()
                + (bypass ? " (bypassed, not applied)" : ""));
    }

    private TagResolver[] resolvers(String name, Decision decision, ResolvedProfile profile) {
        Violation violation = decision.violation();
        return new TagResolver[]{
                Msg.ph("player", name),
                Msg.ph("mod", violation.display()),
                Msg.ph("mod_id", violation.modId()),
                Msg.ph("channels", violation.channels().isEmpty() ? "-" : violation.channelsJoined()),
                Msg.ph("category", violation.category().display()),
                Msg.ph("profile", profile.name()),
                Msg.ph("reason", violation.reason().name()),
                Msg.ph("action", decision.action().name()),
                Msg.ph("count", String.valueOf(decision.count()))
        };
    }
}
