package com.cbd.cbdcore.discord.inbound;

/**
 * Gateway 연결의 현재 상태. {@code FAILED}는 재연결로는 복구할 수 없는 상태(잘못된 토큰,
 * 허용되지 않은 intent, 삭제된 웹훅 등)를 나타내며, 이 상태에서는 설정을 다시 불러오기 전까지
 * 자동 재연결을 시도하지 않는다. 대신 {@code /cbdcore reload}가 설정을 그대로 다시 적용해도
 * 재연결이 일어나도록 하는 트리거로 쓰인다.
 */
public enum GatewayState {
    /** 중계 기능이 꺼져 있어 연결하지 않음. */
    DISABLED,
    /** 채널 조회 또는 WebSocket 연결/인증 진행 중. */
    CONNECTING,
    /** 연결·인증이 완료되어 메시지를 수신 중. */
    READY,
    /** 일시적 오류로 재연결을 대기 중(백오프). */
    RETRY_WAIT,
    /** 재연결로 복구할 수 없는 실패. 설정 리로드 전까지 재시도하지 않음. */
    FAILED
}
