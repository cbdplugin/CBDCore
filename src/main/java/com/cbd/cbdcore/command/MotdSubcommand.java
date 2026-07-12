package com.cbd.cbdcore.command;

import com.cbd.cbdcore.CBDCore;
import com.cbd.cbdcore.motd.MotdSettings;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

final class MotdSubcommand implements Subcommand {

    private final CBDCore plugin;

    MotdSubcommand(CBDCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        if (args.length < 2) {
            CommandFeedback.send(sender, "&c사용법: /cbdcore motd <내용> 또는 /cbdcore motd list");
            return;
        }

        if (args[1].equalsIgnoreCase("list")) {
            CommandFeedback.send(sender, "&e현재 MOTD (서버 목록에 실제로 표시되는 결과):");
            sender.sendMessage(plugin.configService().settings().motd().motd());
            return;
        }

        String text = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        List<String> lines = Arrays.stream(text.split("\\|", 2))
                .map(String::trim)
                .collect(Collectors.toList());

        MotdSettings current = plugin.configService().settings().motd();
        List<Integer> existingWidths = current.configuredWidths();
        int defaultWidth = current.defaultTargetWidth();

        List<Map<String, Object>> entries = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            int width = i < existingWidths.size() ? existingWidths.get(i) : defaultWidth;
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("text", lines.get(i));
            entry.put("width", width);
            entries.add(entry);
        }

        plugin.getConfig().set("motd.lines", entries);
        plugin.saveConfig();
        plugin.reload();
        CommandFeedback.send(sender, "&aMOTD가 변경되었습니다.");
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 2) {
            return List.of("list");
        }
        return List.of();
    }
}
