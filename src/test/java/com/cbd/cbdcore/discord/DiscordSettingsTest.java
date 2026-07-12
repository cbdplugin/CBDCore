package com.cbd.cbdcore.discord;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void disabledSettingsCannotSend() {
        assertFalse(DiscordSettings.disabled().canSend());
    }

    @Test
    void enabledSettingsWithWebhookCanSend() {
        DiscordSettings settings = new DiscordSettings(
                true, true, true,
                URI.create("https://discord.com/api/webhooks/1/" + SECRET_TOKEN),
                "", "%player% 님이 접속했습니다.", "%player% 님이 퇴장했습니다.");

        assertTrue(settings.canSend());
    }
}
