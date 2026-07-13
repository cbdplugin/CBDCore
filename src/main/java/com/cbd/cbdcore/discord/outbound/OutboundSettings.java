package com.cbd.cbdcore.discord.outbound;

import java.net.URI;

/**
 * 게임 -&gt; 디스코드(웹훅) 방향 설정의 불변 스냅샷.
 *
 * @param chatEnabled       인게임 채팅을 디스코드로 중계할지
 * @param joinLeaveEnabled  접속/퇴장 알림을 디스코드로 보낼지
 * @param webhookUri        전송에 사용할 웹훅 URI. 비활성화되었거나 URL이 유효하지 않으면 null.
 * @param avatarUrlTemplate 채팅 전송 시 아바타 URL 템플릿(%uuid% 치환). 비어있으면 웹훅 기본 아바타 사용.
 * @param joinFormat        접속 알림 문구(%player% 치환)
 * @param leaveFormat       퇴장 알림 문구(%player% 치환)
 * @param joinColor         접속 임베드 좌측 색상(0xRRGGBB)
 * @param leaveColor        퇴장 임베드 좌측 색상(0xRRGGBB)
 */
public record OutboundSettings(
        boolean chatEnabled,
        boolean joinLeaveEnabled,
        URI webhookUri,
        String avatarUrlTemplate,
        String joinFormat,
        String leaveFormat,
        int joinColor,
        int leaveColor
) {

    public static OutboundSettings disabled() {
        return new OutboundSettings(
                false, false, null, "",
                "%player% 님이 접속했습니다.",
                "%player% 님이 퇴장했습니다.",
                0x57F287, 0xED4245
        );
    }

    /** 유효한 웹훅 URL이 설정되어 실제로 전송이 가능한 상태인지. */
    public boolean hasWebhook() {
        return webhookUri != null;
    }

    public boolean canSendChat() {
        return webhookUri != null && chatEnabled;
    }

    public boolean canSendJoinLeave() {
        return webhookUri != null && joinLeaveEnabled;
    }
}
