package com.cbd.cbdcore.config;

import com.cbd.cbdcore.discord.DiscordBridgeService;
import com.cbd.cbdcore.discord.DiscordSettings;
import com.cbd.cbdcore.icon.ServerIconService;
import com.cbd.cbdcore.motd.MotdService;
import com.cbd.cbdcore.motd.MotdSettings;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.CachedServerIcon;

import java.net.URI;

/**
 * config.yml을 다시 읽어 MOTD/아이콘/디스코드 상태를 각 서비스에 반영한다.
 * 항상 메인 스레드(명령어 실행, onEnable)에서만 호출된다.
 */
public final class ConfigService {

    private static final int CURRENT_CONFIG_VERSION = 3;

    private final JavaPlugin plugin;
    private final MotdService motdService;
    private final ServerIconService iconService;
    private final DiscordBridgeService discordBridgeService;

    private volatile PluginSettings settings = PluginSettings.initial();

    public ConfigService(JavaPlugin plugin, ServerIconService iconService, DiscordBridgeService discordBridgeService) {
        this.plugin = plugin;
        this.motdService = new MotdService(plugin.getLogger());
        this.iconService = iconService;
        this.discordBridgeService = discordBridgeService;
    }

    public void reload() {
        plugin.reloadConfig();
        migrateConfigIfNeeded();

        FileConfiguration config = plugin.getConfig();

        MotdSettings motdSettings = motdService.parse(config);

        boolean iconEnabled = config.getBoolean("icon.enabled", false);
        CachedServerIcon icon = iconEnabled
                ? iconService.loadOrKeepPrevious(config.getString("icon.file", "icon.png"))
                : null;

        this.settings = new PluginSettings(motdSettings, iconEnabled, icon);
        discordBridgeService.updateSettings(buildDiscordSettings(config));
    }

    /**
     * 이전 버전에 만들어진 config.yml에는 config-version 키가 없거나 discord 섹션이 없을 수 있다.
     * saveDefaultConfig()는 기존 파일을 덮어쓰지 않으므로, 새로 추가된 키는 defaults 체인을 통해
     * 자동으로 기본값이 적용된다 (별도 병합 로직 불필요). 여기서는 버전 표시만 갱신한다.
     */
    private void migrateConfigIfNeeded() {
        FileConfiguration config = plugin.getConfig();
        int version = config.getInt("config-version", 1);
        if (version < CURRENT_CONFIG_VERSION) {
            config.set("config-version", CURRENT_CONFIG_VERSION);
            plugin.saveConfig();
            plugin.getLogger().info("config.yml을 최신 구조(config-version " + CURRENT_CONFIG_VERSION + ")로 갱신했습니다.");
        }
    }

    private DiscordSettings buildDiscordSettings(FileConfiguration config) {
        boolean enabled = config.getBoolean("discord.enabled", false);
        boolean chatEnabled = config.getBoolean("discord.chat.enabled", true);
        boolean joinLeaveEnabled = config.getBoolean("discord.join-leave.enabled", true);
        String rawUrl = config.getString("discord.webhook-url", "");
        String avatarTemplate = config.getString("discord.avatar-url", "");
        String joinFormat = config.getString("discord.join-leave.join-format", "%player% 님이 접속했습니다.");
        String leaveFormat = config.getString("discord.join-leave.leave-format", "%player% 님이 퇴장했습니다.");

        URI webhookUri = null;
        if (enabled && rawUrl != null && !rawUrl.isBlank()) {
            try {
                webhookUri = DiscordSettings.validateWebhookUrl(rawUrl);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning(e.getMessage());
            }
        }

        return new DiscordSettings(enabled, chatEnabled, joinLeaveEnabled, webhookUri, avatarTemplate, joinFormat, leaveFormat);
    }

    public MotdService motdService() {
        return motdService;
    }

    public PluginSettings settings() {
        return settings;
    }
}
