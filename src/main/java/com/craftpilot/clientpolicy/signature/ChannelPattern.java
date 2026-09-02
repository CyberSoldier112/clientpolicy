package com.craftpilot.clientpolicy.signature;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A plugin messaging channel matcher supporting {@code *} and {@code ?} wildcards
 * (for example {@code litematica:*}). Patterns are compiled once at load time and
 * kept in the {@link SignatureRegistry} cache.
 */
public final class ChannelPattern {

    private final String raw;
    private final String literal;
    private final Pattern compiled;

    private ChannelPattern(String raw, String literal, Pattern compiled) {
        this.raw = raw;
        this.literal = literal;
        this.compiled = compiled;
    }

    public static ChannelPattern compile(String raw) {
        String normalized = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (normalized.indexOf('*') < 0 && normalized.indexOf('?') < 0) {
            return new ChannelPattern(normalized, normalized, null);
        }
        StringBuilder regex = new StringBuilder(normalized.length() + 8);
        StringBuilder buffer = new StringBuilder();
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if (c == '*' || c == '?') {
                if (buffer.length() > 0) {
                    regex.append(Pattern.quote(buffer.toString()));
                    buffer.setLength(0);
                }
                regex.append(c == '*' ? ".*" : ".");
            } else {
                buffer.append(c);
            }
        }
        if (buffer.length() > 0) {
            regex.append(Pattern.quote(buffer.toString()));
        }
        return new ChannelPattern(normalized, null, Pattern.compile(regex.toString()));
    }

    /** @param channel already lowercase channel name */
    public boolean matches(String channel) {
        if (channel == null) {
            return false;
        }
        return literal != null ? literal.equals(channel) : compiled.matcher(channel).matches();
    }

    public String raw() {
        return raw;
    }

    @Override
    public String toString() {
        return raw;
    }
}
