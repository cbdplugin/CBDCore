package com.cbd.cbdcore;

import com.cbd.cbdcore.command.CBDCoreCommand;
import com.cbd.cbdcore.config.ConfigService;
import com.cbd.cbdcore.discord.DiscordBridgeService;
import com.cbd.cbdcore.discord.DiscordGatewayClient;
import com.cbd.cbdcore.discord.DiscordOutboundListener;
import com.cbd.cbdcore.discord.JdkDiscordWebhookTransport;
import com.cbd.cbdcore.icon.ServerIconService;
import com.cbd.cbdcore.motd.ServerListPingListener;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;

/**
 * 플러그인 부트스트랩: 서비스 생성/연결, 리스너·명령어 등록만 담당한다.
 * 실제 MOTD/아이콘/디스코드 로직은 각 서비스({@link ConfigService}, {@link ServerIconService},
 * {@link DiscordBridgeService})로 옮겨졌다.
 */
public class CBDCore extends JavaPlugin {

    private ServerIconService iconService;
    private DiscordBridgeService discordBridgeService;
    private DiscordGatewayClient discordGatewayClient;
    private ConfigService configService;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.iconService = new ServerIconService(getDataFolder(), getLogger());
        this.discordBridgeService = new DiscordBridgeService(new JdkDiscordWebhookTransport(getLogger()), getLogger());
        this.discordGatewayClient = new DiscordGatewayClient(this, getLogger());
        this.configService = new ConfigService(this, iconService, discordBridgeService, discordGatewayClient);

        reload();

        getServer().getPluginManager().registerEvents(new ServerListPingListener(this), this);
        getServer().getPluginManager().registerEvents(new DiscordOutboundListener(discordBridgeService), this);

        CBDCoreCommand command = new CBDCoreCommand(this);
        PluginCommand pluginCommand = Objects.requireNonNull(
                getCommand("cbdcore"),
                "plugin.yml에 cbdcore 명령어가 등록되지 않았습니다."
        );
        pluginCommand.setExecutor(command);
        pluginCommand.setTabCompleter(command);

        getLogger().info("CBDCore가 활성화되었습니다.");
    }

    @Override
    public void onDisable() {
        if (discordBridgeService != null) {
            discordBridgeService.shutdown();
        }
        if (discordGatewayClient != null) {
            discordGatewayClient.shutdown();
        }
        getLogger().info("CBDCore가 비활성화되었습니다.");
    }

    public void reload() {
        configService.reload();
    }

    public ConfigService configService() {
        return configService;
    }

    public ServerIconService iconService() {
        return iconService;
    }

    public DiscordBridgeService discordBridgeService() {
        return discordBridgeService;
    }
}
