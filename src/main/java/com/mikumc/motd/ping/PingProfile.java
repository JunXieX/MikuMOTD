package com.mikumc.motd.ping;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 一组等价选择的响应模板（同一画像的 描述 × 图标 笛卡尔积），
 * 每次 ping 随机取一条。单模板时无随机开销。
 */
public final class PingProfile {

    private final ResponseTemplate[] templates;

    PingProfile(ResponseTemplate[] templates) {
        this.templates = templates;
    }

    ResponseTemplate next() {
        if (this.templates.length == 1) {
            return this.templates[0];
        }
        return this.templates[ThreadLocalRandom.current().nextInt(this.templates.length)];
    }

    void update(int online, int max) {
        for (ResponseTemplate template : this.templates) {
            template.update(online, max);
        }
    }

    void dispose() {
        for (ResponseTemplate template : this.templates) {
            template.dispose();
        }
    }
}
