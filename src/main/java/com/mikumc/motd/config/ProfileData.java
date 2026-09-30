package com.mikumc.motd.config;

import java.util.List;

/**
 * 一个 MOTD 画像的配置快照：版本名、描述列表、图标列表与玩家列表行。
 * 描述与图标做笛卡尔积后生成实际响应模板；玩家列表行由同画像内的模板共享。
 */
public record ProfileData(
        String versionName,
        List<String> descriptions,
        List<String> favicons,
        List<String> playerList
) {

    public static final ProfileData EMPTY =
            new ProfileData("", List.of(), List.of(), List.of());
}
