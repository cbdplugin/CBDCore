package com.cbd.cbdcore.motd;

import com.cbd.cbdcore.CBDCore;
import com.cbd.cbdcore.config.PluginSettings;
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
        PluginSettings settings = plugin.configService().settings();

        if (settings.motd().enabled()) {
            event.motd(settings.motd().motd());
        }

        if (settings.iconEnabled() && settings.icon() != null) {
            try {
                event.setServerIcon(settings.icon());
            } catch (IllegalArgumentException | UnsupportedOperationException e) {
                if (plugin.iconService().shouldWarnApplyFailure()) {
                    plugin.getLogger().warning("서버 핑에 아이콘을 적용하지 못했습니다: " + e.getMessage());
                }
            }
        }
    }
}
