package com.cbd.cbdcore.config;

import com.cbd.cbdcore.discord.DiscordBridgeService;
import com.cbd.cbdcore.discord.DiscordGatewayClient;
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

    private static final int CURRENT_CONFIG_VERSION = 5;

    private final JavaPlugin plugin;
    private final MotdService motdService;
    private final ServerIconService iconService;
    private final DiscordBridgeService discordBridgeService;
    private final DiscordGatewayClient discordGatewayClient;

    private volatile PluginSettings settings = PluginSettings.initial();

    public ConfigService(
            JavaPlugin plugin,
            ServerIconService iconService,
            DiscordBridgeService discordBridgeService,
            DiscordGatewayClient discordGatewayClient
    ) {
        this.plugin = plugin;
        this.motdService = new MotdService(plugin.getLogger());
        this.iconService = iconService;
        this.discordBridgeService = discordBridgeService;
        this.discordGatewayClient = discordGatewayClient;
    }

    public void reload() {
        plugin.reloadConfig();
        FileConfiguration config = plugin.getConfig();
        migrateConfigIfNeeded(config);

        MotdSettings motdSettings = motdService.parse(config);

        boolean iconEnabled = config.getBoolean("icon.enabled", false);
        CachedServerIcon icon = iconEnabled
                ? iconService.loadOrKeepPrevious(config.getString("icon.file", "icon.png"))
                : null;

        this.settings = new PluginSettings(motdSettings, iconEnabled, icon);

        DiscordSettings discordSettings = buildDiscordSettings(config);
        discordBridgeService.updateSettings(discordSettings);
        discordGatewayClient.updateSettings(discordSettings);
    }

    /**
     * 이전 버전에 만들어진 config.yml에는 config-version 키가 없거나 새로 추가된 섹션(예:
     * discord.bot-token)이 없을 수 있다. reloadConfig()는 jar에 번들된 config.yml을 defaults로
     * 자동 설정해주지만, saveDefaultConfig()는 파일이 이미 존재하면 아무것도 쓰지 않으므로
     * 새로 추가된 키는 메모리상 기본값으로만 존재하고 사용자가 여는 실제 config.yml 파일에는
     * 영영 나타나지 않는다.
     *
     * copyDefaults(true) + saveConfig()는 항상 매 reload마다 실행한다 (config-version 비교로
     * 게이팅하지 않음). 예전에는 버전이 낮을 때만 마이그레이션을 돌렸는데, 그러면 "버전 숫자만
     * 올리고 실제 키는 못 쓰는" 마이그레이션 버그가 생겼을 때 이미 최신 버전 번호가 찍힌 파일은
     * 다시는 고쳐지지 않는 문제가 있었다. 항상 무조건 병합하면 이런 종류의 문제가 재발해도
     * 다음 reload에서 자동으로 스스로 복구된다. 이미 설정된 값은 그대로 두고, 파일에 없던 키만
     * defaults에서 실제 파일로 복사된다.
     */
    private void migrateConfigIfNeeded(FileConfiguration config) {
        int version = config.getInt("config-version", 1);
        config.set("config-version", CURRENT_CONFIG_VERSION);
        config.options().copyDefaults(true);
        plugin.saveConfig();
        if (version < CURRENT_CONFIG_VERSION) {
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
        int joinColor = DiscordSettings.parseColor(
                config.getString("discord.join-leave.join-color", ""), DiscordSettings.DEFAULT_JOIN_COLOR);
        int leaveColor = DiscordSettings.parseColor(
                config.getString("discord.join-leave.leave-color", ""), DiscordSettings.DEFAULT_LEAVE_COLOR);
        String botToken = config.getString("discord.bot-token", "");

        URI webhookUri = null;
        if (enabled && rawUrl != null && !rawUrl.isBlank()) {
            try {
                webhookUri = DiscordSettings.validateWebhookUrl(rawUrl);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning(e.getMessage());
            }
        }

        return new DiscordSettings(
                enabled, chatEnabled, joinLeaveEnabled, webhookUri, avatarTemplate,
                joinFormat, leaveFormat, joinColor, leaveColor, botToken
        );
    }

    public MotdService motdService() {
        return motdService;
    }

    public PluginSettings settings() {
        return settings;
    }
}
