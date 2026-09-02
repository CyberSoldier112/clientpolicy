package com.craftpilot.clientpolicy.fingerprint;

import java.util.Collection;
import java.util.Locale;

/** Mod loader guessed from the brand string and the loader handshake channels. */
public enum ClientLoader {

    VANILLA,
    FABRIC,
    FORGE,
    NEOFORGE,
    UNKNOWN;

    /**
     * @param brand    value of {@code minecraft:brand}, may be {@code null}
     * @param channels lowercase channels the client registered
     */
    public static ClientLoader detect(String brand, Collection<String> channels) {
        String normalizedBrand = brand == null ? "" : brand.toLowerCase(Locale.ROOT);

        if (normalizedBrand.contains("neoforge")) {
            return NEOFORGE;
        }
        if (normalizedBrand.contains("forge")) {
            return FORGE;
        }
        if (normalizedBrand.contains("fabric") || normalizedBrand.contains("quilt")) {
            return FABRIC;
        }

        for (String channel : channels) {
            if (channel.startsWith("neoforge:")) {
                return NEOFORGE;
            }
            if (channel.startsWith("fml:") || channel.startsWith("forge:")) {
                return FORGE;
            }
            if (channel.startsWith("fabric:") || channel.startsWith("fabric-")) {
                return FABRIC;
            }
        }

        if (normalizedBrand.equals("vanilla")) {
            return VANILLA;
        }
        return UNKNOWN;
    }
}
