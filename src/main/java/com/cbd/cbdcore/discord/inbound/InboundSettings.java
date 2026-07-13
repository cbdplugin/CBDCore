package com.cbd.cbdcore.discord.inbound;

import java.net.URI;

/**
 * 디스코드 -&gt; 게임(Gateway 수신) 방향 설정의 불변 스냅샷.
 *
 * @param enabled         디스코드 채팅을 인게임으로 중계할지
 * @param botToken        Gateway 연결에 사용할 봇 토큰
 * @param webhookUri      수신 채널을 자동으로 알아낼 때 사용할 웹훅 URI (channelId가 비어있을 때만 사용). 없으면 null.
 * @param channelId       수신할 채널 ID를 직접 지정. 비어있으면 webhookUri에서 자동으로 알아낸다.
 * @param maxLength       인게임으로 방송할 때 허용하는 최대 길이(코드포인트 기준)
 * @param maxLines        인게임으로 방송할 때 허용하는 최대 줄 수
 * @param showAttachments 본문이 비고 첨부파일만 있는 메시지를 "[첨부파일 N개]"로 표시할지
 * @param format          인게임 방송 형식. %name%/%message% 치환, '&amp;' 색상 코드 사용 가능.
 */
public record InboundSettings(
        boolean enabled,
        String botToken,
        URI webhookUri,
        String channelId,
        int maxLength,
        int maxLines,
        boolean showAttachments,
        String format
) {

    public static final String DEFAULT_FORMAT = "&7[디스코드] &f%name% &7: &f%message%";

    public static InboundSettings disabled() {
        return new InboundSettings(false, "", null, "", 256, 2, true, DEFAULT_FORMAT);
    }

    /** config.yml에 채널 ID가 직접 지정되어 있는지. */
    public boolean hasExplicitChannel() {
        return channelId != null && !channelId.isBlank();
    }

    /**
     * Gateway에 연결해 채팅을 수신할 수 있는 상태인지
     * (활성화 + 봇 토큰 설정됨 + 수신 채널을 알아낼 방법(명시적 channelId 또는 webhookUri)이 있음).
     */
    public boolean canRelay() {
        return enabled
                && botToken != null && !botToken.isBlank()
                && (hasExplicitChannel() || webhookUri != null);
    }
}
