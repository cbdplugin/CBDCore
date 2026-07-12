package com.cbd.cbdcore;

import com.cbd.cbdcore.command.CBDCoreCommand;
import com.cbd.cbdcore.listener.DiscordChatListener;
import com.cbd.cbdcore.listener.ServerListPingListener;
import com.cbd.cbdcore.util.MotdTextUtils;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.CachedServerIcon;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public class CBDCore extends JavaPlugin {

    private static final int MAX_MOTD_LINES = 2;
    private static final int MAX_LINE_LENGTH = 512;
    private static final int MIN_TARGET_WIDTH = 0;
    private static final int MAX_TARGET_WIDTH = 512;
    private static final int CURRENT_CONFIG_VERSION = 2;

    /**
     * MOTD/아이콘 상태를 하나로 묶은 불변 스냅샷.
     * 리로드 시 전체를 새로 만들어 한 번에 교체하므로, 서버 핑 처리 중
     * 설정 값과 캐시된 결과가 서로 어긋나는 상태가 발생하지 않는다.
     */
    public record ServerStatusSnapshot(
            boolean motdEnabled,
            Component motd,
            boolean iconEnabled,
            CachedServerIcon icon
    ) {
    }

    private volatile ServerStatusSnapshot status =
            new ServerStatusSnapshot(false, Component.empty(), false, null);

    private final AtomicBoolean iconApplyWarned = new AtomicBoolean(false);

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reload();

        getServer().getPluginManager().registerEvents(new ServerListPingListener(this), this);
        getServer().getPluginManager().registerEvents(new DiscordChatListener(this), this);

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
        getLogger().info("CBDCore가 비활성화되었습니다.");
    }

    /**
     * config.yml을 다시 읽어 MOTD와 서버 아이콘 상태를 새 스냅샷으로 한 번에 교체합니다.
     * 아이콘 로드에 실패하면 기존 스냅샷의 아이콘을 그대로 유지합니다.
     */
    public void reload() {
        reloadConfig();
        migrateConfigIfNeeded();

        boolean motdEnabled = getConfig().getBoolean("motd.enabled", true);
        Component motd = buildMotd();

        boolean iconEnabled = getConfig().getBoolean("icon.enabled", false);
        CachedServerIcon icon = iconEnabled ? loadIconOrKeepPrevious() : null;

        this.status = new ServerStatusSnapshot(motdEnabled, motd, iconEnabled, icon);
    }

    /**
     * 이전 버전에 만들어진 config.yml에는 config-version 키가 없다.
     * 현재는 구조 변경 없이 버전 표시만 추가하면 되므로 키만 채워 넣는다.
     */
    private void migrateConfigIfNeeded() {
        int version = getConfig().getInt("config-version", 1);
        if (version < CURRENT_CONFIG_VERSION) {
            getConfig().set("config-version", CURRENT_CONFIG_VERSION);
            saveConfig();
            getLogger().info("config.yml을 최신 구조(config-version " + CURRENT_CONFIG_VERSION + ")로 갱신했습니다.");
        }
    }

    public int getDefaultMotdTargetWidth() {
        return Math.clamp(
                getConfig().getLong("motd.target-width", 240L),
                MIN_TARGET_WIDTH,
                MAX_TARGET_WIDTH
        );
    }

    /**
     * 현재 config.yml에 유효하게 반영된 각 MOTD 줄의 정렬 너비를 순서대로 반환합니다.
     * /cbdcore motd 명령어가 텍스트만 변경할 때 기존 너비를 보존하기 위해 사용합니다.
     */
    public List<Integer> getConfiguredMotdWidths() {
        return parseMotdLines(getDefaultMotdTargetWidth()).stream()
                .map(MotdLine::targetWidth)
                .toList();
    }

    private Component buildMotd() {
        boolean center = getConfig().getBoolean("motd.center", false);
        int defaultTargetWidth = getDefaultMotdTargetWidth();

        List<MotdLine> lines = parseMotdLines(defaultTargetWidth);

        Component result = Component.empty();
        for (int i = 0; i < lines.size(); i++) {
            MotdLine motdLine = lines.get(i);
            Component line = MotdTextUtils.deserialize(motdLine.text());
            if (center) {
                line = MotdTextUtils.center(line, motdLine.targetWidth());
            }
            if (i > 0) {
                result = result.append(Component.newline());
            }
            result = result.append(line);
        }

        return result;
    }

    private record MotdLine(String text, int targetWidth) {
    }

    /**
     * motd.lines의 각 항목은 일반 문자열이거나, 줄마다 다른 가운데 정렬 너비를 주기 위한
     * {text: "...", width: n} 형태의 맵일 수 있습니다. width가 없으면 motd.target-width를 사용합니다.
     */
    private List<MotdLine> parseMotdLines(int defaultTargetWidth) {
        List<?> rawLines = getConfig().getList("motd.lines", List.of());
        List<MotdLine> lines = new ArrayList<>();

        for (int index = 0; index < rawLines.size(); index++) {
            if (lines.size() >= MAX_MOTD_LINES) {
                break;
            }

            Object raw = rawLines.get(index);
            String text;
            int targetWidth = defaultTargetWidth;

            if (raw instanceof Map<?, ?> map) {
                Object textValue = map.get("text");
                if (textValue == null) {
                    getLogger().warning("motd.lines[" + index + "]에 text가 없어 제외했습니다.");
                    continue;
                }
                if (!(textValue instanceof String stringText)) {
                    getLogger().warning("motd.lines[" + index + "].text는 문자열이어야 하므로 제외했습니다.");
                    continue;
                }
                text = stringText;

                Object widthValue = map.get("width");
                if (widthValue != null) {
                    if (widthValue instanceof Number number) {
                        targetWidth = Math.clamp(number.longValue(), MIN_TARGET_WIDTH, MAX_TARGET_WIDTH);
                    } else {
                        getLogger().warning("motd.lines[" + index + "].width는 숫자여야 하므로 기본값을 사용합니다.");
                    }
                }
            } else if (raw instanceof String stringLine) {
                text = stringLine;
            } else if (raw == null) {
                continue;
            } else {
                getLogger().warning("motd.lines[" + index + "]의 형식을 인식할 수 없어 제외했습니다.");
                continue;
            }

            if (text.codePointCount(0, text.length()) > MAX_LINE_LENGTH) {
                getLogger().warning("motd.lines[" + index + "]가 최대 길이 " + MAX_LINE_LENGTH + "자를 초과하여 제외했습니다.");
                continue;
            }

            lines.add(new MotdLine(text, targetWidth));
        }

        return lines;
    }

    private CachedServerIcon loadIconOrKeepPrevious() {
        String fileName = getConfig().getString("icon.file", "icon.png");
        File iconFile;
        try {
            iconFile = resolveDataFile(fileName);
        } catch (IllegalArgumentException e) {
            getLogger().warning("잘못된 아이콘 파일 경로입니다: " + fileName);
            return status.icon();
        }

        if (!iconFile.exists()) {
            getLogger().warning("서버 아이콘 파일을 찾을 수 없습니다: " + iconFile.getPath());
            return status.icon();
        }

        try {
            CachedServerIcon icon = Bukkit.loadServerIcon(iconFile);
            this.iconApplyWarned.set(false);
            return icon;
        } catch (Exception e) {
            getLogger().warning("서버 아이콘을 불러오는 중 오류가 발생했습니다 (기존 아이콘 유지): " + e.getMessage());
            return status.icon();
        }
    }

    /**
     * plugins/CBDCore 데이터 폴더를 벗어나지 않는 파일 경로만 허용합니다.
     *
     * @throws IllegalArgumentException 경로가 데이터 폴더를 벗어나는 경우
     */
    public File resolveDataFile(String fileName) {
        Path base = getDataFolder().toPath().toAbsolutePath().normalize();
        Path target = base.resolve(fileName).normalize();

        if (!target.startsWith(base)) {
            throw new IllegalArgumentException("데이터 폴더를 벗어난 경로입니다: " + fileName);
        }

        return target.toFile();
    }

    /**
     * 이미 검증된 아이콘을 현재 스냅샷에 즉시 반영합니다.
     * (명령어에서 새 아이콘을 미리 로드/검증한 뒤에만 호출)
     */
    public void applyVerifiedIcon(CachedServerIcon icon) {
        ServerStatusSnapshot current = this.status;
        this.status = new ServerStatusSnapshot(current.motdEnabled(), current.motd(), true, icon);
        this.iconApplyWarned.set(false);
    }

    public ServerStatusSnapshot getStatus() {
        return status;
    }

    public boolean shouldWarnIconApplyFailure() {
        return iconApplyWarned.compareAndSet(false, true);
    }
}
