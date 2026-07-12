package com.cbd.cbdcore.listener;

import com.cbd.cbdcore.CBDCore;
import com.cbd.cbdcore.discord.DiscordWebhookClient;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * 인게임 채팅/접속/퇴장을 디스코드 웹훅 채널로 중계한다 (게임 -> 디스코드 단방향).
 */
public class DiscordChatListener implements Listener {

    private final CBDCore plugin;

    public DiscordChatListener(CBDCore plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChat(AsyncChatEvent event) {
        if (event.isCancelled() || !isDiscordEnabled() || !plugin.getConfig().getBoolean("discord.chat.enabled", true)) {
            return;
        }

        String message = PlainTextComponentSerializer.plainText().serialize(event.message());
        String sanitized = DiscordWebhookClient.sanitizeMentions(message);
        String avatarUrl = resolveAvatarUrl(event.getPlayer().getUniqueId().toString());

        DiscordWebhookClient.send(
                webhookUrl(),
                sanitized,
                event.getPlayer().getName(),
                avatarUrl,
                plugin.getLogger()
        );
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (!isDiscordEnabled() || !plugin.getConfig().getBoolean("discord.join-leave.enabled", true)) {
            return;
        }

        String format = plugin.getConfig().getString("discord.join-leave.join-format", "%player% 님이 접속했습니다.");
        String content = format.replace("%player%", event.getPlayer().getName());
        String avatarUrl = resolveAvatarUrl(event.getPlayer().getUniqueId().toString());

        DiscordWebhookClient.send(webhookUrl(), content, event.getPlayer().getName(), avatarUrl, plugin.getLogger());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        if (!isDiscordEnabled() || !plugin.getConfig().getBoolean("discord.join-leave.enabled", true)) {
            return;
        }

        String format = plugin.getConfig().getString("discord.join-leave.leave-format", "%player% 님이 퇴장했습니다.");
        String content = format.replace("%player%", event.getPlayer().getName());
        String avatarUrl = resolveAvatarUrl(event.getPlayer().getUniqueId().toString());

        DiscordWebhookClient.send(webhookUrl(), content, event.getPlayer().getName(), avatarUrl, plugin.getLogger());
    }

    private boolean isDiscordEnabled() {
        return plugin.getConfig().getBoolean("discord.enabled", false);
    }

    private String webhookUrl() {
        return plugin.getConfig().getString("discord.webhook-url", "");
    }

    private String resolveAvatarUrl(String uuid) {
        String template = plugin.getConfig().getString("discord.avatar-url", "");
        if (template == null || template.isBlank()) {
            return null;
        }
        return template.replace("%uuid%", uuid);
    }
}
