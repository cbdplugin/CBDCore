package com.cbd.cbdcore.motd;

import net.kyori.adventure.text.Component;

import java.util.List;

/**
 * config.yml의 motd.* 설정을 읽어 만든 불변 스냅샷.
 */
public record MotdSettings(boolean enabled, Component motd, int defaultTargetWidth, List<MotdLine> lines) {

    public static MotdSettings disabled() {
        return new MotdSettings(false, Component.empty(), 240, List.of());
    }

    /**
     * 현재 config.yml에 유효하게 반영된 각 줄의 정렬 너비. /cbdcore motd 명령어가 텍스트만
     * 바꿀 때 기존 너비를 보존하기 위해 사용한다.
     */
    public List<Integer> configuredWidths() {
        return lines.stream().map(MotdLine::targetWidth).toList();
    }
}
