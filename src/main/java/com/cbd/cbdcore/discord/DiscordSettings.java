package com.cbd.cbdcore.discord;

import com.cbd.cbdcore.discord.inbound.InboundSettings;
import com.cbd.cbdcore.discord.outbound.OutboundSettings;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;

/**
 * config.yml의 discord.* 설정을 읽어 만든 불변 스냅샷.
 *
 * <p>방향별로 완전히 분리되어 있다:
 * <ul>
 *   <li>{@link OutboundSettings} - 게임 -&gt; 디스코드 (웹훅 전송)</li>
 *   <li>{@link InboundSettings} - 디스코드 -&gt; 게임 (봇 토큰 + Gateway 수신)</li>
 * </ul>
 *
 * <p>메인 스레드(reload)에서만 생성되고, 다른 스레드(비동기 채팅 이벤트, Gateway 콜백 등)는
 * 이 완성된 불변 객체만 참조하므로 설정 리로드와 경쟁 상태가 생기지 않는다.
 */
public record DiscordSettings(OutboundSettings outbound, InboundSettings inbound) {

    private static final Set<String> ALLOWED_HOSTS = Set.of("discord.com", "discordapp.com");
    private static final String WEBHOOK_PATH_PREFIX = "/api/webhooks/";

    /** 디스코드 표준 "성공/온라인" 초록색. */
    public static final int DEFAULT_JOIN_COLOR = 0x57F287;
    /** 디스코드 표준 "위험/오프라인" 빨간색. */
    public static final int DEFAULT_LEAVE_COLOR = 0xED4245;

    public static DiscordSettings disabled() {
        return new DiscordSettings(OutboundSettings.disabled(), InboundSettings.disabled());
    }

    /**
     * config.yml에 적힌 색상 문자열("#RRGGBB" 또는 "RRGGBB")을 파싱한다.
     * 값이 비어있거나 형식이 잘못되면 fallback을 사용한다.
     */
    public static int parseColor(String hex, int fallback) {
        if (hex == null || hex.isBlank()) {
            return fallback;
        }
        String normalized = hex.startsWith("#") ? hex.substring(1) : hex;
        try {
            return Integer.parseInt(normalized, 16) & 0xFFFFFF;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * 웹훅 URL이 https + discord.com(또는 discordapp.com) + /api/webhooks/ 경로 형태인지 검증한다.
     * 형식이 유효하지 않으면 URL 자체는 로그에 남기지 않고 예외만 던진다.
     *
     * @throws IllegalArgumentException 검증에 실패한 경우 (원본 URL을 포함하지 않는 메시지)
     */
    public static URI validateWebhookUrl(String rawUrl) {
        URI uri;
        try {
            uri = new URI(rawUrl);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("디스코드 웹훅 URL 형식이 올바르지 않습니다.");
        }

        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("디스코드 웹훅 URL은 https만 허용됩니다.");
        }

        String host = uri.getHost();
        if (host == null || !ALLOWED_HOSTS.contains(host.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("디스코드 웹훅 URL의 호스트가 올바르지 않습니다.");
        }

        String path = uri.getPath();
        if (path == null || !path.startsWith(WEBHOOK_PATH_PREFIX)) {
            throw new IllegalArgumentException("디스코드 웹훅 URL 경로가 올바르지 않습니다.");
        }

        if (uri.getUserInfo() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("디스코드 웹훅 URL에 허용되지 않는 구성 요소가 포함되어 있습니다.");
        }

        return uri;
    }
}
