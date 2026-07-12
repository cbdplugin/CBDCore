package com.cbd.cbdcore.config;

import com.cbd.cbdcore.motd.MotdSettings;
import org.bukkit.util.CachedServerIcon;

/**
 * MOTD/아이콘 상태를 하나로 묶은 불변 스냅샷.
 * 리로드 시 전체를 새로 만들어 한 번에 교체하므로, 서버 핑 처리 중
 * 설정 값과 캐시된 결과가 서로 어긋나는 상태가 발생하지 않는다.
 */
public record PluginSettings(MotdSettings motd, boolean iconEnabled, CachedServerIcon icon) {

    public static PluginSettings initial() {
        return new PluginSettings(MotdSettings.disabled(), false, null);
    }
}
