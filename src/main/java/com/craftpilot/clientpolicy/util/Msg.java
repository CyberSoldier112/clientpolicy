package com.craftpilot.clientpolicy.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

/** MiniMessage helpers; every user facing string in this plugin goes through here. */
public final class Msg {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private Msg() {
    }

    public static Component parse(String raw, TagResolver... resolvers) {
        if (raw == null || raw.isEmpty()) {
            return Component.empty();
        }
        return MINI.deserialize(raw, resolvers);
    }

    public static Component parse(String prefix, String raw, TagResolver... resolvers) {
        String prefixed = (prefix == null ? "" : prefix) + (raw == null ? "" : raw);
        return parse(prefixed, resolvers);
    }

    /** Placeholder whose value is inserted verbatim, so player input can never inject tags. */
    public static TagResolver ph(String key, String value) {
        return Placeholder.unparsed(key, value == null ? "" : value);
    }
}
