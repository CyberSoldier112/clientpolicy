package com.craftpilot.clientpolicy.config;

import com.craftpilot.clientpolicy.signature.ChannelPattern;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/** Immutable snapshot of config.yml; replaced wholesale on {@code /clientpolicy reload}. */
public final class PluginConfig {

    private final int scanDelayTicks;
    private final int rescanWindowSeconds;
    private final boolean trackBrand;
    private final Action brandUnknownAction;

    private final String defaultProfileName;
    private final Action unknownChannelAction;
    private final List<ChannelPattern> ignoreChannels;

    private final String kickMessage;
    private final String warnMessage;
    private final boolean notifyStaff;
    private final String notifyFormat;

    private final Map<String, PolicyProfile> profiles;

    private final int retentionDays;
    private final Map<String, String> messages;

    private PluginConfig(FileConfiguration config, Logger logger) {
        this.scanDelayTicks = Math.max(1, config.getInt("detection.scan-delay-ticks", 60));
        this.rescanWindowSeconds = Math.max(0, config.getInt("detection.rescan-window-seconds", 120));
        this.trackBrand = config.getBoolean("detection.track-brand", true);
        this.brandUnknownAction = Action.parse(config.getString("detection.brand-unknown-action"), Action.LOG);

        this.defaultProfileName = String.valueOf(config.getString("policy.default-profile", "default"))
                .trim().toLowerCase(Locale.ROOT);
        this.unknownChannelAction = Action.parse(config.getString("policy.unknown-channel-action"), Action.LOG);

        List<ChannelPattern> ignored = new ArrayList<>();
        for (String raw : config.getStringList("policy.ignore-channels")) {
            if (raw != null && !raw.isBlank()) {
                ignored.add(ChannelPattern.compile(raw));
            }
        }
        this.ignoreChannels = Collections.unmodifiableList(ignored);

        this.kickMessage = config.getString("enforcement.kick-message",
                "<red>A mod that is not permitted on this server was detected: <white><mod>");
        this.warnMessage = config.getString("enforcement.warn-message",
                "<gold>[!] <yellow><mod> <yellow>is not permitted here.");
        this.notifyStaff = config.getBoolean("enforcement.notify-staff", true);
        this.notifyFormat = config.getString("enforcement.notify-format",
                "<gray>[CP] <white><player> <gray>-> <red><mod> <dark_gray>(<action>)");

        Map<String, PolicyProfile> parsedProfiles = new LinkedHashMap<>();
        ConfigurationSection profilesSection = config.getConfigurationSection("profiles");
        if (profilesSection != null) {
            for (String key : profilesSection.getKeys(false)) {
                ConfigurationSection section = profilesSection.getConfigurationSection(key);
                if (section == null) {
                    continue;
                }
                String name = key.trim().toLowerCase(Locale.ROOT);
                parsedProfiles.put(name, PolicyProfile.from(name, section));
            }
        }
        if (parsedProfiles.isEmpty()) {
            logger.warning("No usable 'profiles' section found - falling back to an empty BLOCKLIST profile.");
            parsedProfiles.put(defaultProfileName, PolicyProfile.fallback(defaultProfileName));
        } else if (!parsedProfiles.containsKey(defaultProfileName)) {
            String firstKey = parsedProfiles.keySet().iterator().next();
            logger.warning("policy.default-profile '" + defaultProfileName + "' does not exist, using '"
                    + firstKey + "' instead.");
        }
        this.profiles = Collections.unmodifiableMap(parsedProfiles);

        this.retentionDays = Math.max(0, config.getInt("storage.retention-days", 30));

        Map<String, String> parsedMessages = new LinkedHashMap<>();
        ConfigurationSection messagesSection = config.getConfigurationSection("messages");
        if (messagesSection != null) {
            for (String key : messagesSection.getKeys(true)) {
                if (messagesSection.isString(key)) {
                    parsedMessages.put(key, messagesSection.getString(key, ""));
                }
            }
        }
        this.messages = Collections.unmodifiableMap(parsedMessages);
    }

    public static PluginConfig from(FileConfiguration config, Logger logger) {
        return new PluginConfig(config, logger);
    }

    public int scanDelayTicks() {
        return scanDelayTicks;
    }

    public int rescanWindowSeconds() {
        return rescanWindowSeconds;
    }

    public long rescanWindowMillis() {
        return TimeUnit.SECONDS.toMillis(rescanWindowSeconds);
    }

    public boolean trackBrand() {
        return trackBrand;
    }

    public Action brandUnknownAction() {
        return brandUnknownAction;
    }

    public Action unknownChannelAction() {
        return unknownChannelAction;
    }

    public String defaultProfileName() {
        return defaultProfileName;
    }

    /** @param channel already lowercase channel name */
    public boolean isIgnoredChannel(String channel) {
        for (ChannelPattern pattern : ignoreChannels) {
            if (pattern.matches(channel)) {
                return true;
            }
        }
        return false;
    }

    public List<ChannelPattern> ignoreChannels() {
        return ignoreChannels;
    }

    public String kickMessage() {
        return kickMessage;
    }

    public String warnMessage() {
        return warnMessage;
    }

    public boolean notifyStaff() {
        return notifyStaff;
    }

    public String notifyFormat() {
        return notifyFormat;
    }

    public Map<String, PolicyProfile> profiles() {
        return profiles;
    }

    /** @return the configured default profile, never {@code null} */
    public PolicyProfile defaultProfile() {
        PolicyProfile profile = profiles.get(defaultProfileName);
        if (profile != null) {
            return profile;
        }
        return profiles.values().iterator().next();
    }

    public PolicyProfile profile(String name) {
        return name == null ? null : profiles.get(name.trim().toLowerCase(Locale.ROOT));
    }

    public int retentionDays() {
        return retentionDays;
    }

    public String prefix() {
        return messages.getOrDefault("prefix", "");
    }

    public String message(String key, String fallback) {
        String value = messages.get(key);
        return value == null || value.isEmpty() ? fallback : value;
    }
}
