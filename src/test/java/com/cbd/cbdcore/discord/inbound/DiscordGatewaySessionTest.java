package com.cbd.cbdcore.discord.inbound;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscordGatewaySessionTest {

    @Test
    void freshSessionCannotResume() {
        assertFalse(new DiscordGatewaySession().canResume());
    }

    @Test
    void sessionCanResumeOnceReadyAndSequenceRecorded() {
        DiscordGatewaySession session = new DiscordGatewaySession();
        session.recordReady("sess-1", "wss://resume.example");
        session.updateSequence(42);

        assertTrue(session.canResume());
        assertEquals("sess-1", session.sessionId());
        assertEquals(42, session.sequence());
        assertEquals("wss://resume.example/?v=10&encoding=json", session.resumeUri().toString());
    }

    @Test
    void readyWithoutSequenceCannotResume() {
        DiscordGatewaySession session = new DiscordGatewaySession();
        session.recordReady("sess-1", "wss://resume.example");

        assertFalse(session.canResume());
    }

    @Test
    void invalidateClearsResumeState() {
        DiscordGatewaySession session = new DiscordGatewaySession();
        session.recordReady("sess-1", "wss://resume.example");
        session.updateSequence(42);

        session.invalidate();

        assertFalse(session.canResume());
        assertEquals(-1, session.sequence());
    }
}
