package com.cbd.cbdcore.discord;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DiscordSettingsTest {

    private static final String SECRET_TOKEN = "abcSECRETtoken123";

    @Test
    void validDiscordComUrlIsAccepted() {
        String url = "https://discord.com/api/webhooks/123456/" + SECRET_TOKEN;

        URI uri = assertDoesNotThrow(() -> DiscordSettings.validateWebhookUrl(url));

        assertEquals(url, uri.toString());
    }

    @Test
    void validDiscordappComUrlIsAccepted() {
        String url = "https://discordapp.com/api/webhooks/123456/" + SECRET_TOKEN;

        URI uri = assertDoesNotThrow(() -> DiscordSettings.validateWebhookUrl(url));

        assertEquals(url, uri.toString());
    }

    @Test
    void nonHttpsSchemeIsRejected() {
        String url = "http://discord.com/api/webhooks/123456/" + SECRET_TOKEN;

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> DiscordSettings.validateWebhookUrl(url));
        assertFalse(e.getMessage().contains(SECRET_TOKEN));
    }

    @Test
    void disallowedHostIsRejected() {
        String url = "https://evil.example.com/api/webhooks/123456/" + SECRET_TOKEN;

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> DiscordSettings.validateWebhookUrl(url));
        assertFalse(e.getMessage().contains(SECRET_TOKEN));
    }

    @Test
    void wrongPathIsRejected() {
        String url = "https://discord.com/not-a-webhook-path/" + SECRET_TOKEN;

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> DiscordSettings.validateWebhookUrl(url));
        assertFalse(e.getMessage().contains(SECRET_TOKEN));
    }

    @Test
    void userInfoIsRejected() {
        String url = "https://user:pass@discord.com/api/webhooks/123456/" + SECRET_TOKEN;

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> DiscordSettings.validateWebhookUrl(url));
        assertFalse(e.getMessage().contains(SECRET_TOKEN));
    }

    @Test
    void fragmentIsRejected() {
        String url = "https://discord.com/api/webhooks/123456/" + SECRET_TOKEN + "#fragment";

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> DiscordSettings.validateWebhookUrl(url));
        assertFalse(e.getMessage().contains(SECRET_TOKEN));
    }

    @Test
    void malformedUrlIsRejectedWithoutLeakingInput() {
        String malformed = "https://dis cord.com/api/webhooks/ " + SECRET_TOKEN;

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> DiscordSettings.validateWebhookUrl(malformed));
        assertFalse(e.getMessage().contains(SECRET_TOKEN));
    }

    @Test
    void disabledSettingsHaveBothDirectionsOff() {
        DiscordSettings disabled = DiscordSettings.disabled();
        assertFalse(disabled.outbound().canSendChat());
        assertFalse(disabled.outbound().canSendJoinLeave());
        assertFalse(disabled.inbound().canRelay());
    }

    @Test
    void parseColorAcceptsHexWithOrWithoutHash() {
        assertEquals(0x57F287, DiscordSettings.parseColor("#57F287", 0));
        assertEquals(0x57F287, DiscordSettings.parseColor("57F287", 0));
    }

    @Test
    void parseColorFallsBackOnBlankOrInvalidInput() {
        assertEquals(0x123456, DiscordSettings.parseColor("", 0x123456));
        assertEquals(0x123456, DiscordSettings.parseColor(null, 0x123456));
        assertEquals(0x123456, DiscordSettings.parseColor("not-a-color", 0x123456));
    }
}
