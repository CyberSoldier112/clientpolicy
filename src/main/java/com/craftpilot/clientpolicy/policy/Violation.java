package com.craftpilot.clientpolicy.policy;

import com.craftpilot.clientpolicy.config.Action;
import com.craftpilot.clientpolicy.signature.Category;

import java.util.List;

/**
 * A single policy breach produced by {@link PolicyEngine}.
 *
 * @param modId       signature id, or {@code unknown:<channel>} / {@code unknown:brand}
 * @param usesLadder  {@code true} for signature hits (escalation ladder applies),
 *                    {@code false} for unknown channel / unknown brand (fixed action)
 * @param fixedAction action used when {@code usesLadder} is false
 */
public record Violation(String modId,
                        String display,
                        Category category,
                        List<String> channels,
                        Reason reason,
                        boolean usesLadder,
                        Action fixedAction) {

    public enum Reason {
        /** Signature is on the profile's deny list. */
        DENIED,
        /** ALLOWLIST profile and the signature is not on the allow list. */
        NOT_ALLOWED,
        /** Channel matched no signature at all. */
        UNKNOWN_CHANNEL,
        /** minecraft:brand could not be read within the rescan window. */
        UNKNOWN_BRAND
    }

    public String channelsJoined() {
        return String.join(",", channels);
    }

    public static Violation signature(String modId, String display, Category category,
                                      List<String> channels, Reason reason) {
        return new Violation(modId, display, category, List.copyOf(channels), reason, true, Action.LOG);
    }

    public static Violation fixed(String modId, String display, Category category,
                                  List<String> channels, Reason reason, Action action) {
        return new Violation(modId, display, category, List.copyOf(channels), reason, false, action);
    }
}
