package com.cbd.cbdcore.command;

import com.cbd.cbdcore.CBDCore;
import com.cbd.cbdcore.discord.DiscordWebhookClient;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import org.bukkit.Bukkit;
import org.bukkit.util.CachedServerIcon;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

public class CBDCoreCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = Arrays.asList("reload", "motd", "icon", "discord");

    private final CBDCore plugin;

    public CBDCoreCommand(CBDCore plugin) {
        this.plugin = plugin;
    }

    private void send(CommandSender sender, String legacyText) {
        Component component = LegacyComponentSerializer.legacyAmpersand().deserialize(legacyText);
        sender.sendMessage(component);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        if (args.length == 0) {
            send(sender, "&e/cbdcore reload &f- 설정과 아이콘을 다시 불러옵니다.");
            send(sender, "&e/cbdcore motd <내용> &f- MOTD를 설정합니다. '|'로 줄바꿈.");
            send(sender, "&e/cbdcore motd list &f- 현재 MOTD를 확인합니다.");
            send(sender, "&e/cbdcore icon <파일명> &f- 서버 아이콘을 변경합니다.");
            send(sender, "&e/cbdcore discord test &f- 디스코드 웹훅 연결을 테스트합니다.");
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                plugin.reload();
                send(sender, "&aCBDCore 설정을 다시 불러왔습니다.");
            }
            case "motd" -> handleMotd(sender, args);
            case "icon" -> handleIcon(sender, args);
            case "discord" -> handleDiscord(sender, args);
            default -> send(sender, "&c알 수 없는 명령어입니다. /cbdcore 로 도움말을 확인하세요.");
        }

        return true;
    }

    private void handleMotd(CommandSender sender, String[] args) {
        if (args.length < 2) {
            send(sender, "&c사용법: /cbdcore motd <내용> 또는 /cbdcore motd list");
            return;
        }

        if (args[1].equalsIgnoreCase("list")) {
            send(sender, "&e현재 MOTD (서버 목록에 실제로 표시되는 결과):");
            sender.sendMessage(plugin.getStatus().motd());
            return;
        }

        String text = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        List<String> lines = Arrays.stream(text.split("\\|", 2))
                .map(String::trim)
                .collect(Collectors.toList());

        List<Integer> existingWidths = plugin.getConfiguredMotdWidths();
        int defaultWidth = plugin.getDefaultMotdTargetWidth();

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
        send(sender, "&aMOTD가 변경되었습니다.");
    }

    private void handleIcon(CommandSender sender, String[] args) {
        if (args.length < 2) {
            send(sender, "&c사용법: /cbdcore icon <파일명>");
            return;
        }

        String fileName = args[1];
        File iconFile;
        try {
            iconFile = plugin.resolveDataFile(fileName);
        } catch (IllegalArgumentException e) {
            send(sender, "&c허용되지 않는 경로입니다. plugins/CBDCore 폴더 내부의 파일만 지정할 수 있습니다.");
            return;
        }

        if (!iconFile.exists()) {
            send(sender, "&cplugins/CBDCore/" + fileName + " 파일을 찾을 수 없습니다.");
            return;
        }

        // 기존 아이콘을 지우기 전에 새 아이콘을 먼저 검증한다.
        CachedServerIcon candidate;
        try {
            candidate = Bukkit.loadServerIcon(iconFile);
        } catch (Exception e) {
            send(sender, "&c아이콘을 불러오지 못했습니다. 파일 형식(64x64 png)을 확인하세요.");
            return;
        }

        plugin.getConfig().set("icon.file", fileName);
        plugin.getConfig().set("icon.enabled", true);
        plugin.saveConfig();
        plugin.applyVerifiedIcon(candidate);

        send(sender, "&a서버 아이콘이 변경되었습니다: " + fileName);
    }

    private void handleDiscord(CommandSender sender, String[] args) {
        if (args.length < 2 || !args[1].equalsIgnoreCase("test")) {
            send(sender, "&c사용법: /cbdcore discord test");
            return;
        }

        String webhookUrl = plugin.getConfig().getString("discord.webhook-url", "");
        if (webhookUrl == null || webhookUrl.isBlank()) {
            send(sender, "&cdiscord.webhook-url이 설정되지 않았습니다.");
            return;
        }

        DiscordWebhookClient.send(webhookUrl, "CBDCore 웹훅 연결 테스트입니다.", "CBDCore", null, plugin.getLogger());
        send(sender, "&a디스코드로 테스트 메시지를 전송했습니다. 채널을 확인하세요.");
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String[] args) {
        if (args.length == 1) {
            return SUBCOMMANDS.stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT)))
                    .collect(Collectors.toList());
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("motd")) {
            return List.of("list");
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("discord")) {
            return List.of("test");
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("icon")) {
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

        return List.of();
    }
}
