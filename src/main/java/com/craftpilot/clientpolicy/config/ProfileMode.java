package com.craftpilot.clientpolicy.config;

import java.util.Locale;

/** How a profile turns a matched signature into a verdict. */
public enum ProfileMode {

    /** Everything that is not in {@code allow} is a violation. */
    ALLOWLIST,
    /** Only what is in {@code deny} is a violation. */
    BLOCKLIST;

    public static ProfileMode parse(String raw, ProfileMode fallback) {
        if (raw == null) {
            return fallback;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }
}
