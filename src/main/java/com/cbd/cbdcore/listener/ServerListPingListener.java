package com.cbd.cbdcore.listener;

import com.cbd.cbdcore.CBDCore;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerListPingEvent;

public class ServerListPingListener implements Listener {

    private final CBDCore plugin;

    public ServerListPingListener(CBDCore plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onServerListPing(ServerListPingEvent event) {
        CBDCore.ServerStatusSnapshot status = plugin.getStatus();

        if (status.motdEnabled()) {
            event.motd(status.motd());
        }

        if (status.iconEnabled() && status.icon() != null) {
            try {
                event.setServerIcon(status.icon());
            } catch (IllegalArgumentException | UnsupportedOperationException e) {
                if (plugin.shouldWarnIconApplyFailure()) {
                    plugin.getLogger().warning("서버 핑에 아이콘을 적용하지 못했습니다: " + e.getMessage());
                }
            }
        }
    }
}
