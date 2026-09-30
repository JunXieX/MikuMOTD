package com.mikumc.motd.config;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * MOTD 文本的输入格式。所有格式先反序列化为 Component，
 * 再按输出目标（描述走 JSON、玩家列表行走旧版段落码）序列化。
 */
public enum TextFormat {

    MINIMESSAGE {
        @Override
        public Component deserialize(String input) {
            return MiniMessage.miniMessage().deserialize(input);
        }
    },
    LEGACY_AMPERSAND {
        @Override
        public Component deserialize(String input) {
            return LegacyComponentSerializer.legacyAmpersand().deserialize(input);
        }
    },
    LEGACY_SECTION {
        @Override
        public Component deserialize(String input) {
            return LegacyComponentSerializer.legacySection().deserialize(input);
        }
    },
    JSON {
        @Override
        public Component deserialize(String input) {
            return GsonComponentSerializer.gson().deserialize(input);
        }
    };

    public abstract Component deserialize(String input);

    public static TextFormat parse(String name) {
        for (TextFormat format : values()) {
            if (format.name().equalsIgnoreCase(name)) {
                return format;
            }
        }
        return MINIMESSAGE;
    }
}
