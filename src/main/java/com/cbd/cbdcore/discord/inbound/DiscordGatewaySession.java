package com.cbd.cbdcore.discord.inbound;

import java.net.URI;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 하나의 Gateway 세션에 대해 재개(RESUME)에 필요한 상태를 담는다.
 * READY 이벤트에서 받은 session_id / resume_gateway_url 과 마지막으로 처리한 이벤트
 * 시퀀스(s)를 보관하며, 재연결 시 이 정보로 세션을 이어받아 끊긴 동안의 메시지를
 * 재전송받을 수 있다.
 *
 * <p>필드는 Gateway 스케줄러 스레드와 WebSocket 콜백 스레드가 함께 읽고 쓰므로
 * {@code volatile}/{@link AtomicLong}로 가시성을 보장한다.</p>
 */
final class DiscordGatewaySession {

    private static final String GATEWAY_QUERY = "/?v=10&encoding=json";

    private volatile String sessionId;
    private volatile String resumeGatewayUrl;
    private final AtomicLong sequence = new AtomicLong(-1);

    void recordReady(String sessionId, String resumeGatewayUrl) {
        this.sessionId = sessionId;
        this.resumeGatewayUrl = resumeGatewayUrl;
    }

    void updateSequence(long value) {
        sequence.set(value);
    }

    long sequence() {
        return sequence.get();
    }

    String sessionId() {
        return sessionId;
    }

    boolean canResume() {
        return sessionId != null && resumeGatewayUrl != null && sequence.get() >= 0;
    }

    void invalidate() {
        sessionId = null;
        resumeGatewayUrl = null;
        sequence.set(-1);
    }

    URI resumeUri() {
        return URI.create(resumeGatewayUrl + GATEWAY_QUERY);
    }
}
