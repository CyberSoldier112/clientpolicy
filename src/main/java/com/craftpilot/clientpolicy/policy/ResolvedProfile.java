package com.craftpilot.clientpolicy.policy;

import com.craftpilot.clientpolicy.config.PolicyProfile;

/**
 * The profile a player ended up with plus the human readable reason why,
 * surfaced by {@code /clientpolicy profile}.
 */
public record ResolvedProfile(PolicyProfile profile, String reason) {

    public String name() {
        return profile.name();
    }
}
