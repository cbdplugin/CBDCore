package com.cbd.cbdcore.discord.inbound;

import com.cbd.cbdcore.discord.inbound.DiscordInboundMessageMapper.InboundMessage;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscordInboundMessageMapperTest {

    private final DiscordInboundMessageMapper mapper = new DiscordInboundMessageMapper();

    private static InboundSettings settings(int maxLength, int maxLines, boolean showAttachments) {
        return new InboundSettings(true, "token", null, "", maxLength, maxLines, showAttachments,
                InboundSettings.DEFAULT_FORMAT);
    }

    @Test
    void resolveMentionsReplacesKnownUserIdWithDisplayName() {
        JsonArray mentions = JsonParser.parseString(
                "[{\"id\":\"123\",\"username\":\"raw_name\",\"global_name\":\"예쁜이름\"}]"
        ).getAsJsonArray();

        assertEquals("안녕 @예쁜이름!",
                DiscordInboundMessageMapper.resolveMentions("안녕 <@123>!", mentions));
    }

    @Test
    void resolveMentionsHandlesNicknameMentionSyntax() {
        JsonArray mentions = JsonParser.parseString(
                "[{\"id\":\"456\",\"username\":\"raw_name\"}]"
        ).getAsJsonArray();

        assertEquals("hi @raw_name",
                DiscordInboundMessageMapper.resolveMentions("hi <@!456>", mentions));
    }

    @Test
    void resolveMentionsFallsBackToGenericNameWhenUnresolved() {
        JsonArray mentions = JsonParser.parseString(
                "[{\"id\":\"111\",\"username\":\"someone_else\"}]"
        ).getAsJsonArray();

        assertEquals("hi @user",
                DiscordInboundMessageMapper.resolveMentions("hi <@999>", mentions));
    }

    @Test
    void resolveMentionsWithNullMentionsReturnsContentUnchanged() {
        assertEquals("no mentions here",
                DiscordInboundMessageMapper.resolveMentions("no mentions here", null));
    }

    @Test
    void stripCustomEmojiReplacesTagWithColonName() {
        assertEquals("nice :pepe: job",
                DiscordInboundMessageMapper.stripCustomEmoji("nice <:pepe:123456789012345678> job"));
    }

    @Test
    void stripCustomEmojiHandlesAnimatedEmoji() {
        assertEquals(":dance: time",
                DiscordInboundMessageMapper.stripCustomEmoji("<a:dance:987654321098765432> time"));
    }

    @Test
    void sanitizeStripsControlCharactersButKeepsNewlines() {
        String result = DiscordInboundMessageMapper.sanitize("a\u0000b\u0007c\td", 256, 5);
        assertEquals("abc d", result);
    }

    @Test
    void sanitizeLimitsLineCountAndMarksTruncation() {
        String result = DiscordInboundMessageMapper.sanitize("l1\nl2\nl3\nl4", 256, 2);
        assertEquals("l1\nl2 …", result);
    }

    @Test
    void sanitizeLimitsLengthByCodePointAndMarksTruncation() {
        String result = DiscordInboundMessageMapper.sanitize("a".repeat(10), 4, 5);
        assertEquals("aaaa …", result);
    }

    @Test
    void mapReturnsDisplayNameAndSanitizedContent() {
        JsonObject data = JsonParser.parseString(
                "{\"content\":\"hello\",\"author\":{\"username\":\"raw\",\"global_name\":\"닉네임\"}}"
        ).getAsJsonObject();

        Optional<InboundMessage> mapped = mapper.map(data, settings(256, 2, true));

        assertTrue(mapped.isPresent());
        assertEquals("닉네임", mapped.get().name());
        assertEquals("hello", mapped.get().content());
    }

    @Test
    void mapShowsAttachmentPlaceholderWhenBodyBlank() {
        JsonObject data = JsonParser.parseString(
                "{\"content\":\"\",\"author\":{\"username\":\"raw\"},\"attachments\":[{},{}]}"
        ).getAsJsonObject();

        Optional<InboundMessage> mapped = mapper.map(data, settings(256, 2, true));

        assertTrue(mapped.isPresent());
        assertEquals("[첨부파일 2개]", mapped.get().content());
    }

    @Test
    void mapDropsBlankMessageWithoutDisplayableAttachments() {
        JsonObject data = JsonParser.parseString(
                "{\"content\":\"   \",\"author\":{\"username\":\"raw\"}}"
        ).getAsJsonObject();

        assertFalse(mapper.map(data, settings(256, 2, true)).isPresent());
    }

    @Test
    void mapDropsAttachmentOnlyMessageWhenShowAttachmentsDisabled() {
        JsonObject data = JsonParser.parseString(
                "{\"content\":\"\",\"author\":{\"username\":\"raw\"},\"attachments\":[{}]}"
        ).getAsJsonObject();

        assertFalse(mapper.map(data, settings(256, 2, false)).isPresent());
    }
}
