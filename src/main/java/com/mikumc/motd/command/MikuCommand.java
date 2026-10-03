package com.mikumc.motd.command;

import com.mikumc.motd.MikuMOTDPlugin;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/**
 * /mikumotd 命令：reload（重载配置）、info（查看状态）。
 */
public final class MikuCommand implements SimpleCommand {

    private static final List<String> SUGGESTIONS = List.of("info", "reload");

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
            case "info" -> {
                if (!source.hasPermission("mikumotd.command.info")) {
                    this.deny(source);
                    return;
                }
                source.sendMessage(this.plugin.statusInfo());
            }
            default -> source.sendMessage(Component.text(
                    "用法：/mikumotd <info|reload>", NamedTextColor.YELLOW));
        }
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        CommandSource source = invocation.source();
        if (invocation.arguments().length > 1) {
            return List.of();
        }
        String prefix = invocation.arguments().length == 0 ? "" : invocation.arguments()[0].toLowerCase(Locale.ROOT);
        return SUGGESTIONS.stream()
                .filter(s -> s.startsWith(prefix))
                .filter(s -> source.hasPermission("mikumotd.command." + s))
                .toList();
    }

    /** 任一子命令权限即视为可用（具体子命令仍在其分支内二次校验）。 */
    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission("mikumotd.command.info")
                || invocation.source().hasPermission("mikumotd.command.reload");
    }

    private void deny(CommandSource source) {
        source.sendMessage(Component.text("没有执行该操作的权限", NamedTextColor.RED));
    }
}
