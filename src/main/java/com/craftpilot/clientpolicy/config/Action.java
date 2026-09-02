package com.craftpilot.clientpolicy.config;

import java.util.Locale;

/**
 * Policy outcome, ordered from softest to hardest. The ordinal order is
 * meaningful: {@link #isHarderThan(Action)} relies on it.
 */
public enum Action {

    /** Do nothing at all, not even a log line. */
    ALLOW,
    /** Record the detection (console + database) without touching the player. */
    LOG,
    /** Record and send the configured warning to the player. */
    WARN,
    /** Record and remove the player with the configured kick message. */
    KICK;

    public static Action parse(String raw, Action fallback) {
        if (raw == null) {
            return fallback;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    public boolean isHarderThan(Action other) {
        return other == null || ordinal() > other.ordinal();
    }
}
