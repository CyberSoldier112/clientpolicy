package com.craftpilot.clientpolicy.signature;

import java.util.List;
import java.util.regex.Pattern;

/** One entry of signatures.yml: a mod id plus the fingerprints that reveal it. */
public final class ModSignature {

    private final String id;
    private final String display;
    private final Category category;
    private final List<ChannelPattern> channels;
    private final Pattern brandPattern;

    public ModSignature(String id, String display, Category category,
                        List<ChannelPattern> channels, Pattern brandPattern) {
        this.id = id;
        this.display = display;
        this.category = category;
        this.channels = List.copyOf(channels);
        this.brandPattern = brandPattern;
    }

    public String id() {
        return id;
    }

    public String display() {
        return display;
    }

    public Category category() {
        return category;
    }

    public List<ChannelPattern> channels() {
        return channels;
    }

    /**
     * False when the entry carries neither channels nor a brand regex, i.e. the mod
     * exists in the profile lists but can never be observed. Reported honestly by
     * {@code /clientpolicy rules}.
     */
    public boolean detectable() {
        return !channels.isEmpty() || brandPattern != null;
    }

    /** @param channel already lowercase channel name */
    public boolean matchesChannel(String channel) {
        for (ChannelPattern pattern : channels) {
            if (pattern.matches(channel)) {
                return true;
            }
        }
        return false;
    }

    public boolean matchesBrand(String brand) {
        return brandPattern != null && brand != null && brandPattern.matcher(brand).find();
    }

    @Override
    public String toString() {
        return id;
    }
}
