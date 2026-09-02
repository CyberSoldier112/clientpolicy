package com.craftpilot.clientpolicy.config;

import org.bukkit.configuration.ConfigurationSection;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** A named policy profile as declared under {@code profiles} in config.yml. */
public record PolicyProfile(String name, ProfileMode mode, Set<String> allow, Set<String> deny, Ladder ladder) {

    /** Permission node that binds a player to this profile. */
    public String permission() {
        return "clientpolicy.profile." + name;
    }

    /** True when a signature id violates this profile. */
    public boolean isViolation(String modId) {
        if (deny.contains(modId)) {
            return true;
        }
        return mode == ProfileMode.ALLOWLIST && !allow.contains(modId);
    }

    public static PolicyProfile from(String name, ConfigurationSection section) {
        ProfileMode mode = ProfileMode.parse(section.getString("mode"), ProfileMode.BLOCKLIST);
        return new PolicyProfile(
                name,
                mode,
                lowerSet(section.getStringList("allow")),
                lowerSet(section.getStringList("deny")),
                Ladder.from(section.getConfigurationSection("ladder")));
    }

    /** Fallback used when config.yml declares no profile at all. */
    public static PolicyProfile fallback(String name) {
        return new PolicyProfile(name, ProfileMode.BLOCKLIST, Set.of(), Set.of(), Ladder.DEFAULT);
    }

    private static Set<String> lowerSet(List<String> raw) {
        Set<String> out = new LinkedHashSet<>();
        for (String value : raw) {
            if (value != null && !value.isBlank()) {
                out.add(value.trim().toLowerCase(Locale.ROOT));
            }
        }
        return Collections.unmodifiableSet(out);
    }
}
