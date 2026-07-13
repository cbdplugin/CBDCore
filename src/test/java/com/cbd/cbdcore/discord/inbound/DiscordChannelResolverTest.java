package com.cbd.cbdcore.discord.inbound;

import com.cbd.cbdcore.discord.inbound.DiscordChannelResolver.ChannelResolutionException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DiscordChannelResolverTest {

    @Test
    void parseChannelIdReadsChannelIdField() {
        String body = "{\"id\":\"1\",\"channel_id\":\"987654321\",\"name\":\"hook\"}";

        assertEquals("987654321", DiscordChannelResolver.parseChannelId(body));
    }

    @Test
    void parseChannelIdRejectsMissingFieldAsNonRecoverable() {
        String body = "{\"id\":\"1\",\"name\":\"hook\"}";

        ChannelResolutionException e = assertThrows(ChannelResolutionException.class,
                () -> DiscordChannelResolver.parseChannelId(body));
        assertFalse(e.isRecoverable());
    }

    @Test
    void parseChannelIdRejectsMalformedBodyAsNonRecoverable() {
        ChannelResolutionException e = assertThrows(ChannelResolutionException.class,
                () -> DiscordChannelResolver.parseChannelId("not json"));
        assertFalse(e.isRecoverable());
    }
}
