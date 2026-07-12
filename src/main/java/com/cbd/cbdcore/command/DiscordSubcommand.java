package com.cbd.cbdcore.command;

import com.cbd.cbdcore.CBDCore;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

import java.util.List;

final class DiscordSubcommand implements Subcommand {

    private final CBDCore plugin;

    DiscordSubcommand(CBDCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        if (args.length < 2 || !args[1].equalsIgnoreCase("test")) {
            CommandFeedback.send(sender, "&c사용법: /cbdcore discord test");
            return;
        }

        String webhookUrl = plugin.getConfig().getString("discord.webhook-url", "");
        if (webhookUrl == null || webhookUrl.isBlank()) {
            CommandFeedback.send(sender, "&cdiscord.webhook-url이 설정되지 않았습니다.");
            return;
        }
        if (!plugin.getConfig().getBoolean("discord.enabled", false)) {
            CommandFeedback.send(sender, "&cdiscord.enabled이 false로 설정되어 있습니다.");
            return;
        }

        CommandFeedback.send(sender, "&e디스코드로 테스트 메시지를 전송하는 중...");

        // 실제 전송 결과(성공/실패)를 확인한 뒤에 응답하기 위해 CompletionStage가 완료될 때까지 기다린다.
        // 콜백은 별도 스레드에서 완료되므로, CommandSender를 다루기 전에 반드시 메인 스레드로 되돌아온다.
        plugin.discordBridgeService().sendTest("CBDCore 웹훅 연결 테스트입니다.")
                .whenComplete((result, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (error != null || result == null || !result.success()) {
                        CommandFeedback.send(sender, "&c디스코드 웹훅 테스트에 실패했습니다. 콘솔 로그를 확인하세요.");
                        return;
                    }
                    CommandFeedback.send(sender, "&a디스코드 웹훅 연결에 성공했습니다. 채널을 확인하세요.");
                }));
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 2) {
            return List.of("test");
        }
        return List.of();
    }
}
