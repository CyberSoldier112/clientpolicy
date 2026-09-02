package com.craftpilot.clientpolicy.config;

import org.bukkit.configuration.ConfigurationSection;

import java.util.concurrent.TimeUnit;

/**
 * Escalation ladder of a profile.
 *
 * <p>Violation hits {@code 1..escalateAfter} produce {@link #first()};
 * every hit beyond that produces {@link #after()}. With the shipped default
 * ({@code escalate-after: 2}) the first two hits warn and the third kicks.</p>
 *
 * @param counterResetMillis idle time after which the per-mod counter restarts,
 *                           {@code 0} means the counter never resets
 */
public record Ladder(Action first, Action after, int escalateAfter, long counterResetMillis) {

    public static final Ladder DEFAULT =
            new Ladder(Action.WARN, Action.KICK, 2, TimeUnit.HOURS.toMillis(24));

    public Action actionFor(int violationCount) {
        if (escalateAfter <= 0) {
            return after;
        }
        return violationCount <= escalateAfter ? first : after;
    }

    /** @param section may be {@code null} when the profile declares no ladder */
    public static Ladder from(ConfigurationSection section) {
        if (section == null) {
            return DEFAULT;
        }
        Action first = Action.parse(section.getString("first"), DEFAULT.first());
        Action after = Action.parse(section.getString("after"), DEFAULT.after());
        int escalateAfter = Math.max(0, section.getInt("escalate-after", DEFAULT.escalateAfter()));
        long resetHours = Math.max(0L, section.getLong("counter-reset-hours", 24L));
        return new Ladder(first, after, escalateAfter, TimeUnit.HOURS.toMillis(resetHours));
    }
}
