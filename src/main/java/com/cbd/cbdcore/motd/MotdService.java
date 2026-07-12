package com.cbd.cbdcore.motd;

import net.kyori.adventure.text.Component;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * config.yml의 motd.* 설정을 파싱해 {@link MotdSettings}(렌더링된 Component 포함)로 만든다.
 */
public final class MotdService {

    private static final int MAX_MOTD_LINES = 2;
    private static final int MAX_LINE_LENGTH = 512;
    private static final int MIN_TARGET_WIDTH = 0;
    private static final int MAX_TARGET_WIDTH = 512;

    private final Logger logger;

    public MotdService(Logger logger) {
        this.logger = logger;
    }

    public MotdSettings parse(FileConfiguration config) {
        boolean enabled = config.getBoolean("motd.enabled", true);
        boolean center = config.getBoolean("motd.center", false);
        int defaultTargetWidth = getDefaultTargetWidth(config);

        List<MotdLine> lines = parseMotdLines(config, defaultTargetWidth);

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

        return new MotdSettings(enabled, result, defaultTargetWidth, lines);
    }

    public int getDefaultTargetWidth(FileConfiguration config) {
        return Math.clamp(
                config.getLong("motd.target-width", 240L),
                MIN_TARGET_WIDTH,
                MAX_TARGET_WIDTH
        );
    }

    /**
     * motd.lines의 각 항목은 일반 문자열이거나, 줄마다 다른 가운데 정렬 너비를 주기 위한
     * {text: "...", width: n} 형태의 맵일 수 있습니다. width가 없으면 motd.target-width를 사용합니다.
     */
    private List<MotdLine> parseMotdLines(FileConfiguration config, int defaultTargetWidth) {
        List<?> rawLines = config.getList("motd.lines", List.of());
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
                    logger.warning("motd.lines[" + index + "]에 text가 없어 제외했습니다.");
                    continue;
                }
                if (!(textValue instanceof String stringText)) {
                    logger.warning("motd.lines[" + index + "].text는 문자열이어야 하므로 제외했습니다.");
                    continue;
                }
                text = stringText;

                Object widthValue = map.get("width");
                if (widthValue != null) {
                    if (widthValue instanceof Number number) {
                        targetWidth = Math.clamp(number.longValue(), MIN_TARGET_WIDTH, MAX_TARGET_WIDTH);
                    } else {
                        logger.warning("motd.lines[" + index + "].width는 숫자여야 하므로 기본값을 사용합니다.");
                    }
                }
            } else if (raw instanceof String stringLine) {
                text = stringLine;
            } else if (raw == null) {
                continue;
            } else {
                logger.warning("motd.lines[" + index + "]의 형식을 인식할 수 없어 제외했습니다.");
                continue;
            }

            if (text.codePointCount(0, text.length()) > MAX_LINE_LENGTH) {
                logger.warning("motd.lines[" + index + "]가 최대 길이 " + MAX_LINE_LENGTH + "자를 초과하여 제외했습니다.");
                continue;
            }

            lines.add(new MotdLine(text, targetWidth));
        }

        return lines;
    }
}
