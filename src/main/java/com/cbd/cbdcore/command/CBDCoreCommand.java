package com.cbd.cbdcore.command;

import com.cbd.cbdcore.CBDCore;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 각 서브커맨드로 위임만 하는 얇은 디스패처. 실제 로직은 각 {@link Subcommand} 구현체에 있다.
 */
public class CBDCoreCommand implements CommandExecutor, TabCompleter {

    private final Map<String, Subcommand> subcommands = new LinkedHashMap<>();

    public CBDCoreCommand(CBDCore plugin) {
        subcommands.put("reload", new ReloadSubcommand(plugin));
        subcommands.put("motd", new MotdSubcommand(plugin));
        subcommands.put("icon", new IconSubcommand(plugin));
        subcommands.put("discord", new DiscordSubcommand(plugin));
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        if (args.length == 0) {
            CommandFeedback.send(sender, "&e/cbdcore reload &f- 설정과 아이콘을 다시 불러옵니다.");
            CommandFeedback.send(sender, "&e/cbdcore motd <내용> &f- MOTD를 설정합니다. '|'로 줄바꿈.");
            CommandFeedback.send(sender, "&e/cbdcore motd list &f- 현재 MOTD를 확인합니다.");
            CommandFeedback.send(sender, "&e/cbdcore icon <파일명> &f- 서버 아이콘을 변경합니다.");
            CommandFeedback.send(sender, "&e/cbdcore discord test &f- 디스코드 웹훅 연결을 테스트합니다.");
            return true;
        }

        Subcommand subcommand = subcommands.get(args[0].toLowerCase(Locale.ROOT));
        if (subcommand == null) {
            CommandFeedback.send(sender, "&c알 수 없는 명령어입니다. /cbdcore 로 도움말을 확인하세요.");
            return true;
        }

        subcommand.execute(sender, args);
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String[] args) {
        if (args.length == 1) {
            return subcommands.keySet().stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT)))
                    .collect(Collectors.toList());
        }

        if (args.length >= 2) {
            Subcommand subcommand = subcommands.get(args[0].toLowerCase(Locale.ROOT));
            if (subcommand != null) {
                return subcommand.tabComplete(sender, args);
            }
        }

        return List.of();
    }
}
