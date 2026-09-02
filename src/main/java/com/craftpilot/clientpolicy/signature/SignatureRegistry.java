package com.craftpilot.clientpolicy.signature;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Immutable view of signatures.yml. Wildcard patterns are compiled once at load
 * time; channel lookups are additionally memoised so a busy join wave does not
 * re-walk every pattern for the same channel name.
 */
public final class SignatureRegistry {

    /** Marker stored in the lookup cache for "no signature matches this channel". */
    private static final ModSignature NONE =
            new ModSignature("", "", Category.OTHER, List.of(), null);

    private final Map<String, ModSignature> byId;
    private final List<ModSignature> ordered;
    private final Map<String, ModSignature> channelCache = new ConcurrentHashMap<>();

    private SignatureRegistry(Map<String, ModSignature> byId) {
        this.byId = Collections.unmodifiableMap(byId);
        this.ordered = List.copyOf(byId.values());
    }

    public static SignatureRegistry empty() {
        return new SignatureRegistry(new LinkedHashMap<>());
    }

    public static SignatureRegistry load(FileConfiguration configuration, Logger logger) {
        Map<String, ModSignature> parsed = new LinkedHashMap<>();
        ConfigurationSection root = configuration.getConfigurationSection("signatures");
        if (root == null) {
            logger.warning("signatures.yml has no 'signatures' section - nothing will be detected.");
            return new SignatureRegistry(parsed);
        }

        for (String rawId : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(rawId);
            if (section == null) {
                continue;
            }
            String id = rawId.trim().toLowerCase(Locale.ROOT);
            String display = section.getString("display", rawId);
            Category category = Category.parse(section.getString("category"), Category.OTHER);

            List<ChannelPattern> channels = new ArrayList<>();
            for (String channel : section.getStringList("channels")) {
                if (channel != null && !channel.isBlank()) {
                    channels.add(ChannelPattern.compile(channel));
                }
            }

            Pattern brand = null;
            String brandRegex = section.getString("brand");
            if (brandRegex != null && !brandRegex.isBlank()) {
                try {
                    brand = Pattern.compile(brandRegex);
                } catch (PatternSyntaxException ex) {
                    logger.log(Level.WARNING, "Signature '" + id + "' has an invalid brand regex, ignoring it.", ex);
                }
            }

            parsed.put(id, new ModSignature(id, display, category, channels, brand));
        }
        return new SignatureRegistry(parsed);
    }

    /**
     * @param channel raw channel name
     * @return the matching signature, or {@code null} when the channel is unknown
     */
    public ModSignature matchChannel(String channel) {
        if (channel == null || channel.isBlank()) {
            return null;
        }
        String key = channel.trim().toLowerCase(Locale.ROOT);
        ModSignature cached = channelCache.computeIfAbsent(key, lookup -> {
            for (ModSignature signature : ordered) {
                if (signature.matchesChannel(lookup)) {
                    return signature;
                }
            }
            return NONE;
        });
        return cached == NONE ? null : cached;
    }

    public List<ModSignature> matchBrand(String brand) {
        if (brand == null || brand.isBlank()) {
            return List.of();
        }
        List<ModSignature> matches = new ArrayList<>(1);
        for (ModSignature signature : ordered) {
            if (signature.matchesBrand(brand)) {
                matches.add(signature);
            }
        }
        return matches;
    }

    /** @return the signature for an id, or {@code null} when it is not declared */
    public ModSignature byId(String id) {
        return id == null ? null : byId.get(id.trim().toLowerCase(Locale.ROOT));
    }

    /** Display name for an id, falling back to the raw id for undeclared entries. */
    public String displayOf(String id) {
        ModSignature signature = byId(id);
        return signature != null ? signature.display() : id;
    }

    public Collection<ModSignature> all() {
        return ordered;
    }

    public int size() {
        return ordered.size();
    }
}
