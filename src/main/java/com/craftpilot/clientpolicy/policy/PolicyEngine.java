package com.craftpilot.clientpolicy.policy;

import com.craftpilot.clientpolicy.ClientPolicyPlugin;
import com.craftpilot.clientpolicy.config.Action;
import com.craftpilot.clientpolicy.config.PluginConfig;
import com.craftpilot.clientpolicy.config.PolicyProfile;
import com.craftpilot.clientpolicy.config.ProfileMode;
import com.craftpilot.clientpolicy.fingerprint.ClientFingerprint;
import com.craftpilot.clientpolicy.fingerprint.ClientLoader;
import com.craftpilot.clientpolicy.signature.Category;
import com.craftpilot.clientpolicy.signature.ModSignature;
import com.craftpilot.clientpolicy.signature.SignatureRegistry;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a fingerprint into a verdict. Stateless and side effect free, so it can
 * be called from commands for a read-only preview as well as from the scanner.
 *
 * <p>Must run on the main thread: it reads Bukkit permissions.</p>
 */
public final class PolicyEngine {

    private final ClientPolicyPlugin plugin;

    public PolicyEngine(ClientPolicyPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Picks the profile bound to the player through {@code clientpolicy.profile.<name>},
     * falling back to {@code policy.default-profile}.
     */
    public ResolvedProfile resolveProfile(Player player) {
        PluginConfig config = plugin.config();
        PolicyProfile fallback = config.defaultProfile();

        for (PolicyProfile profile : config.profiles().values()) {
            if (profile.name().equals(fallback.name())) {
                continue; // only an explicit override should beat the default
            }
            if (player.hasPermission(profile.permission())) {
                return new ResolvedProfile(profile, "permission " + profile.permission());
            }
        }
        if (player.hasPermission(fallback.permission())) {
            return new ResolvedProfile(fallback, "permission " + fallback.permission());
        }
        return new ResolvedProfile(fallback, "policy.default-profile");
    }

    /**
     * @param finalPass {@code true} for the last scan of the rescan window and for manual
     *                  rescans; only then is a missing brand treated as a violation, so a
     *                  brand that simply has not arrived yet does not trigger anything
     */
    public EvaluationResult evaluate(Player player, ClientFingerprint fingerprint, boolean finalPass) {
        PluginConfig config = plugin.config();
        SignatureRegistry registry = plugin.signatures();
        ResolvedProfile resolved = resolveProfile(player);
        PolicyProfile profile = resolved.profile();

        String brand = fingerprint.brand();
        Map<ModSignature, List<String>> matched = new LinkedHashMap<>();
        List<String> unknownChannels = new ArrayList<>();
        List<String> ignoredChannels = new ArrayList<>();

        for (String channel : fingerprint.sortedChannels()) {
            if (config.isIgnoredChannel(channel)) {
                ignoredChannels.add(channel);
                continue;
            }
            ModSignature signature = registry.matchChannel(channel);
            if (signature != null) {
                matched.computeIfAbsent(signature, key -> new ArrayList<>()).add(channel);
            } else {
                unknownChannels.add(channel);
            }
        }

        if (config.trackBrand() && brand != null) {
            for (ModSignature signature : registry.matchBrand(brand)) {
                matched.computeIfAbsent(signature, key -> new ArrayList<>()).add("brand:" + brand);
            }
        }

        List<Violation> violations = new ArrayList<>();

        for (Map.Entry<ModSignature, List<String>> entry : matched.entrySet()) {
            ModSignature signature = entry.getKey();
            if (!profile.isViolation(signature.id())) {
                continue;
            }
            Violation.Reason reason = profile.deny().contains(signature.id())
                    ? Violation.Reason.DENIED
                    : Violation.Reason.NOT_ALLOWED;
            violations.add(Violation.signature(signature.id(), signature.display(),
                    signature.category(), entry.getValue(), reason));
        }

        Action unknownAction = config.unknownChannelAction();
        if (unknownAction != Action.ALLOW) {
            for (String channel : unknownChannels) {
                violations.add(Violation.fixed("unknown:" + channel, channel, Category.OTHER,
                        List.of(channel), Violation.Reason.UNKNOWN_CHANNEL, unknownAction));
            }
        }

        if (finalPass && config.trackBrand() && (brand == null || brand.isBlank())
                && config.brandUnknownAction() != Action.ALLOW) {
            violations.add(Violation.fixed("unknown:brand", "Unreadable client brand", Category.OTHER,
                    List.of(), Violation.Reason.UNKNOWN_BRAND, config.brandUnknownAction()));
        }

        ClientLoader loader = ClientLoader.detect(brand, fingerprint.channels());
        return new EvaluationResult(resolved, brand, loader, matched, unknownChannels, ignoredChannels, violations);
    }

    /** True when the profile treats an id as explicitly permitted. */
    public boolean isExplicitlyAllowed(PolicyProfile profile, String modId) {
        return profile.allow().contains(modId)
                || (profile.mode() == ProfileMode.BLOCKLIST && !profile.deny().contains(modId));
    }
}
