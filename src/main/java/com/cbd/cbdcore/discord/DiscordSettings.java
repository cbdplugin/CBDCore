package com.cbd.cbdcore.discord;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Set;

/**
 * config.yml의 discord.* 설정을 읽어 만든 불변 스냅샷.
 * 메인 스레드(reload)에서만 생성되고, 다른 스레드(비동기 채팅 이벤트 등)는
 * 이 완성된 객체만 참조하므로 설정 리로드와 경쟁 상태가 생기지 않는다.
 */
public record DiscordSettings(
        boolean enabled,
        boolean chatEnabled,
        boolean joinLeaveEnabled,
        URI webhookUri,
        String avatarUrlTemplate,
        String joinFormat,
        String leaveFormat
) {

    private static final Set<String> ALLOWED_HOSTS = Set.of("discord.com", "discordapp.com");
    private static final String WEBHOOK_PATH_PREFIX = "/api/webhooks/";

    public static DiscordSettings disabled() {
        return new DiscordSettings(
                false, false, false, null, "",
                "%player% 님이 접속했습니다.",
                "%player% 님이 퇴장했습니다."
        );
    }

    /**
     * 실제로 메시지를 보낼 수 있는 상태인지 (활성화 + 유효한 웹훅 URL).
     */
    public boolean canSend() {
        return enabled && webhookUri != null;
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
        if (host == null || !ALLOWED_HOSTS.contains(host.toLowerCase(java.util.Locale.ROOT))) {
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
