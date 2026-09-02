package com.craftpilot.clientpolicy.store;

import java.util.UUID;

/**
 * One row of the {@code detections} table.
 *
 * @param modId     signature id or {@code unknown:<channel>}
 * @param channels  comma separated channels that triggered the match
 * @param createdAt epoch millis
 */
public record DetectionRecord(long id,
                              UUID uuid,
                              String name,
                              String modId,
                              String channels,
                              String brand,
                              String profile,
                              String action,
                              long createdAt) {

    public static DetectionRecord of(UUID uuid, String name, String modId, String channels,
                                     String brand, String profile, String action, long createdAt) {
        return new DetectionRecord(-1L, uuid, name, modId, channels, brand, profile, action, createdAt);
    }
}
