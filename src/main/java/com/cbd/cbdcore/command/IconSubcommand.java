package com.cbd.cbdcore.command;

import com.cbd.cbdcore.CBDCore;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.util.CachedServerIcon;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class IconSubcommand implements Subcommand {

    private final CBDCore plugin;

    IconSubcommand(CBDCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        if (args.length < 2) {
            CommandFeedback.send(sender, "&c사용법: /cbdcore icon <파일명>");
            return;
        }

        String fileName = args[1];
        File iconFile;
        try {
            iconFile = plugin.iconService().resolveDataFile(fileName);
        } catch (IllegalArgumentException e) {
            CommandFeedback.send(sender, "&c허용되지 않는 경로입니다. plugins/CBDCore 폴더 내부의 파일만 지정할 수 있습니다.");
            return;
        }

        if (!iconFile.exists()) {
            CommandFeedback.send(sender, "&cplugins/CBDCore/" + fileName + " 파일을 찾을 수 없습니다.");
            return;
        }

        // 기존 아이콘을 지우기 전에 새 아이콘을 먼저 검증한다.
        CachedServerIcon candidate;
        try {
            candidate = Bukkit.loadServerIcon(iconFile);
        } catch (Exception e) {
            CommandFeedback.send(sender, "&c아이콘을 불러오지 못했습니다. 파일 형식(64x64 png)을 확인하세요.");
            return;
        }

        plugin.getConfig().set("icon.file", fileName);
        plugin.getConfig().set("icon.enabled", true);
        plugin.saveConfig();
        plugin.iconService().applyVerified(candidate);
        plugin.reload();

        CommandFeedback.send(sender, "&a서버 아이콘이 변경되었습니다: " + fileName);
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length != 2) {
            return List.of();
        }

        File[] files = plugin.getDataFolder().listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".png"));
        List<String> names = new ArrayList<>();
        if (files != null) {
            for (File f : files) {
                names.add(f.getName());
            }
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }
}
