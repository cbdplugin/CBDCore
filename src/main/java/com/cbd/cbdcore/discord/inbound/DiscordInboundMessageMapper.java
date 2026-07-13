package com.cbd.cbdcore.discord.inbound;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 디스코드 MESSAGE_CREATE 데이터를 인게임에 방송할 (표시 이름, 본문) 쌍으로 변환한다.
 *
 * <p>이 클래스는 순수 함수만 담아 어떤 Bukkit/adventure API에도 의존하지 않으므로
 * 단위 테스트로 검증할 수 있다. 채널/봇/웹훅 필터링 같은 "이 메시지를 처리할지" 판단은
 * {@link DiscordGatewayClient}가 담당하고, 여기서는 넘어온 메시지를 안전한 문자열로
 * 정제(멘션/커스텀 이모지 치환, 제어문자 제거, 길이·줄 수 제한)하는 일만 한다.</p>
 */
public final class DiscordInboundMessageMapper {

    private static final Pattern MENTION_PATTERN = Pattern.compile("<@!?(\\d+)>");
    private static final Pattern CUSTOM_EMOJI_PATTERN = Pattern.compile("<a?:(\\w+):\\d+>");
    /** 길이/줄 수 제한으로 잘렸음을 나타내는 말줄임 표시. */
    private static final String TRUNCATION_MARKER = " …";

    /** 인게임 방송에 필요한 최소 정보. */
    public record InboundMessage(String name, String content) {
    }

    /**
     * MESSAGE_CREATE 데이터를 방송용 메시지로 변환한다. 표시할 내용이 없으면
     * (본문이 비고 첨부파일 표시도 하지 않는 경우) 빈 값을 돌려준다.
     */
    public Optional<InboundMessage> map(JsonObject data, InboundSettings settings) {
        JsonObject author = data.getAsJsonObject("author");
        String name = resolveDisplayName(author);

        String rawContent = data.has("content") && !data.get("content").isJsonNull()
                ? data.get("content").getAsString()
                : "";
        String resolved = resolveMentions(rawContent, data.has("mentions") ? data.getAsJsonArray("mentions") : null);
        resolved = stripCustomEmoji(resolved);
        String sanitized = sanitize(resolved, settings.maxLength(), settings.maxLines());

        if (sanitized.isBlank()) {
            int attachmentCount = countAttachments(data);
            if (settings.showAttachments() && attachmentCount > 0) {
                sanitized = "[첨부파일 " + attachmentCount + "개]";
            } else {
                return Optional.empty();
            }
        }
        return Optional.of(new InboundMessage(name, sanitized));
    }

    static int countAttachments(JsonObject data) {
        if (!data.has("attachments") || data.get("attachments").isJsonNull()) {
            return 0;
        }
        return data.getAsJsonArray("attachments").size();
    }

    static String resolveDisplayName(JsonObject author) {
        JsonElement globalName = author.get("global_name");
        if (globalName != null && !globalName.isJsonNull() && !globalName.getAsString().isBlank()) {
            return globalName.getAsString();
        }
        return author.get("username").getAsString();
    }

    static String resolveMentions(String content, JsonArray mentions) {
        if (mentions == null || mentions.isEmpty()) {
            return content;
        }
        Map<String, String> idToName = new HashMap<>();
        for (JsonElement mentionElement : mentions) {
            JsonObject mention = mentionElement.getAsJsonObject();
            idToName.put(mention.get("id").getAsString(), resolveDisplayName(mention));
        }
        Matcher matcher = MENTION_PATTERN.matcher(content);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String name = idToName.get(matcher.group(1));
            matcher.appendReplacement(result, Matcher.quoteReplacement("@" + (name != null ? name : "user")));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    static String stripCustomEmoji(String content) {
        return CUSTOM_EMOJI_PATTERN.matcher(content).replaceAll(":$1:");
    }

    /**
     * 인게임 채팅에 안전하게 방송할 수 있도록 본문을 정제한다.
     * 제어문자를 제거하고, 줄 수와 길이(코드포인트 기준)를 제한한다.
     * 잘린 경우 말줄임 표시를 덧붙인다.
     */
    static String sanitize(String content, int maxLength, int maxLines) {
        String normalized = normalizeControlChars(content);
        String[] lines = normalized.split("\n", -1);

        boolean truncatedLines = lines.length > maxLines;
        int keepLines = Math.min(lines.length, Math.max(maxLines, 1));
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < keepLines; i++) {
            if (i > 0) {
                joined.append('\n');
            }
            joined.append(lines[i]);
        }

        String limited = truncateToCodePointLimit(joined.toString(), maxLength);
        boolean truncatedLength = limited.length() < joined.length();

        String result = limited.strip();
        if ((truncatedLines || truncatedLength) && !result.isEmpty()) {
            result = result + TRUNCATION_MARKER;
        }
        return result;
    }

    /**
     * 줄바꿈(\n)은 유지하고 탭은 공백으로 바꾸며, 그 외 C0 제어문자와 DEL(0x7F)은 제거한다.
     * \r\n / \r 은 모두 \n 으로 통일한다.
     */
    static String normalizeControlChars(String text) {
        String unified = text.replace("\r\n", "\n").replace('\r', '\n');
        StringBuilder sb = new StringBuilder(unified.length());
        unified.codePoints().forEach(cp -> {
            if (cp == '\n') {
                sb.append('\n');
            } else if (cp == '\t') {
                sb.append(' ');
            } else if (cp < 0x20 || cp == 0x7F) {
                // 제어문자 제거
            } else {
                sb.appendCodePoint(cp);
            }
        });
        return sb.toString();
    }

    static String truncateToCodePointLimit(String text, int maxCodePoints) {
        if (text == null) {
            return "";
        }
        int codePointCount = text.codePointCount(0, text.length());
        if (codePointCount <= maxCodePoints) {
            return text;
        }
        int cutIndex = text.offsetByCodePoints(0, Math.max(maxCodePoints, 0));
        return text.substring(0, cutIndex);
    }
}
