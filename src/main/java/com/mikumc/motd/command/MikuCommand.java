package com.mikumc.motd.command;

import com.mikumc.motd.MikuMOTDPlugin;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/**
 * /mikumotd 命令：reload（重载配置）、maintenance（切换维护模式）、info（查看状态）。
 */
public final class MikuCommand implements SimpleCommand {

    private static final List<String> SUGGESTIONS = List.of("info", "maintenance", "reload");

    private final MikuMOTDPlugin plugin;

    public MikuCommand(MikuMOTDPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "info";

        switch (sub) {
            case "reload" -> {
                if (!source.hasPermission("mikumotd.command.reload")) {
                    this.deny(source);
                    return;
                }
                boolean ok;
                try {
                    ok = this.plugin.reload();
                } catch (Exception e) {
                    this.plugin.logger().error("配置重载失败", e);
                    ok = false;
                }
                if (ok) {
                    source.sendMessage(Component.text("MikuMOTD 配置已重载", NamedTextColor.GREEN));
                } else {
                    source.sendMessage(Component.text("MikuMOTD 配置重载失败，详见控制台", NamedTextColor.RED));
                }
            }
            case "maintenance" -> {
                if (!source.hasPermission("mikumotd.command.maintenance")) {
                    this.deny(source);
                    return;
                }
                boolean target;
                if (args.length >= 2) {
                    String value = args[1].toLowerCase(Locale.ROOT);
                    if (value.equals("on") || value.equals("true")) {
                        target = true;
                    } else if (value.equals("off") || value.equals("false")) {
                        target = false;
                    } else if (value.equals("toggle") || value.equals("t")) {
                        target = !this.plugin.isMaintenance();
                    } else {
                        source.sendMessage(Component.text("用法：/mikumotd maintenance <on|off|toggle>",
                                NamedTextColor.YELLOW));
                        return;
                    }
                } else {
                    target = !this.plugin.isMaintenance();
                }
                this.plugin.setMaintenance(target);
                source.sendMessage(Component.text(
                        target ? "维护模式已开启" : "维护模式已关闭", NamedTextColor.GREEN));
            }
            case "info" -> {
                if (!source.hasPermission("mikumotd.command.info")) {
                    this.deny(source);
                    return;
                }
                source.sendMessage(this.plugin.statusInfo());
            }
            default -> source.sendMessage(Component.text(
                    "用法：/mikumotd <info|maintenance|reload>", NamedTextColor.YELLOW));
        }
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        if (invocation.arguments().length > 1) {
            return List.of();
        }
        String prefix = invocation.arguments().length == 0 ? "" : invocation.arguments()[0].toLowerCase(Locale.ROOT);
        return SUGGESTIONS.stream().filter(s -> s.startsWith(prefix)).toList();
    }

    private void deny(CommandSource source) {
        source.sendMessage(Component.text("没有执行该操作的权限", NamedTextColor.RED));
    }
}
