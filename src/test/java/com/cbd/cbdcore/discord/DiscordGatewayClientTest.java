package com.cbd.cbdcore.discord;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DiscordGatewayClientTest {

    @Test
    void resolveMentionsReplacesKnownUserIdWithDisplayName() {
        JsonArray mentions = JsonParser.parseString(
                "[{\"id\":\"123\",\"username\":\"raw_name\",\"global_name\":\"예쁜이름\"}]"
        ).getAsJsonArray();

        String result = DiscordGatewayClient.resolveMentions("안녕 <@123>!", mentions);

        assertEquals("안녕 @예쁜이름!", result);
    }

    @Test
    void resolveMentionsHandlesNicknameMentionSyntax() {
        JsonArray mentions = JsonParser.parseString(
                "[{\"id\":\"456\",\"username\":\"raw_name\"}]"
        ).getAsJsonArray();

        String result = DiscordGatewayClient.resolveMentions("hi <@!456>", mentions);

        assertEquals("hi @raw_name", result);
    }

    @Test
    void resolveMentionsFallsBackToGenericNameWhenUnresolved() {
        // 실제로는 없을 상황(멘션 표기가 있는데 그 id가 mentions 배열에 없는 경우)에 대비한 방어 로직 검증.
        JsonArray mentions = JsonParser.parseString(
                "[{\"id\":\"111\",\"username\":\"someone_else\"}]"
        ).getAsJsonArray();

        String result = DiscordGatewayClient.resolveMentions("hi <@999>", mentions);

        assertEquals("hi @user", result);
    }

    @Test
    void resolveMentionsWithNullMentionsReturnsContentUnchanged() {
        String result = DiscordGatewayClient.resolveMentions("no mentions here", null);

        assertEquals("no mentions here", result);
    }

    @Test
    void stripCustomEmojiReplacesTagWithColonName() {
        String result = DiscordGatewayClient.stripCustomEmoji("nice <:pepe:123456789012345678> job");

        assertEquals("nice :pepe: job", result);
    }

    @Test
    void stripCustomEmojiHandlesAnimatedEmoji() {
        String result = DiscordGatewayClient.stripCustomEmoji("<a:dance:987654321098765432> time");

        assertEquals(":dance: time", result);
    }
}
