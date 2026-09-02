package com.craftpilot.clientpolicy.signature;

import java.util.Locale;

/** Coarse grouping used by {@code /clientpolicy rules} and the audit log. */
public enum Category {

    MINIMAP,
    SCHEMATIC,
    AUTOMATION,
    PERFORMANCE,
    UTILITY,
    OTHER;

    public static Category parse(String raw, Category fallback) {
        if (raw == null) {
            return fallback;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    public String display() {
        String lower = name().toLowerCase(Locale.ROOT);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }
}
