package com.cbd.cbdcore.discord;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * 인게임 이벤트에서 필요한 정보만 추출해 {@link DiscordBridgeService}로 전달한다.
 * 설정 조회, 포맷팅, HTTP 호출 등 실제 로직은 전부 서비스 쪽 책임이다.
 * {@link AsyncChatEvent}는 비동기 스레드에서 실행되므로, 여기서는 플러그인 설정(FileConfiguration)에
 * 직접 접근하지 않고 이미 만들어진 불변 {@link DiscordSettings} 스냅샷만 참조하는
 * {@link DiscordBridgeService}만 호출한다.
 */
public class DiscordOutboundListener implements Listener {

    private final DiscordBridgeService bridgeService;

    public DiscordOutboundListener(DiscordBridgeService bridgeService) {
        this.bridgeService = bridgeService;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChat(AsyncChatEvent event) {
        if (event.isCancelled()) {
            return;
        }

        String message = PlainTextComponentSerializer.plainText().serialize(event.message());
        bridgeService.sendChat(event.getPlayer().getName(), message, event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        bridgeService.sendJoin(event.getPlayer().getName(), event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        bridgeService.sendLeave(event.getPlayer().getName(), event.getPlayer().getUniqueId());
    }
}
