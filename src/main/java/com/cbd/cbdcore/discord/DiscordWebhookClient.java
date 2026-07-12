package com.cbd.cbdcore.discord;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.logging.Logger;

/**
 * 디스코드 웹훅으로 메시지를 비동기 전송하는 유틸리티.
 * 봇 토큰 없이 채널에 지정된 웹훅 URL만으로 동작한다 (게임 -> 디스코드 단방향).
 */
public final class DiscordWebhookClient {

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private DiscordWebhookClient() {
    }

    /**
     * @param webhookUrl 디스코드 웹훅 URL
     * @param content    전송할 본문 (일반 텍스트)
     * @param username   웹훅 메시지에 표시할 이름. null/빈 값이면 웹훅 기본 이름 사용
     * @param avatarUrl  웹훅 메시지에 표시할 아바타 URL. null/빈 값이면 웹훅 기본 아바타 사용
     * @param logger     전송 실패 시 경고를 남길 로거
     */
    public static void send(String webhookUrl, String content, String username, String avatarUrl, Logger logger) {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            return;
        }

        StringBuilder json = new StringBuilder("{");
        json.append("\"content\":\"").append(escapeJson(content)).append('"');
        if (username != null && !username.isBlank()) {
            json.append(",\"username\":\"").append(escapeJson(username)).append('"');
        }
        if (avatarUrl != null && !avatarUrl.isBlank()) {
            json.append(",\"avatar_url\":\"").append(escapeJson(avatarUrl)).append('"');
        }
        json.append('}');

        HttpRequest request;
        try {
            request = HttpRequest.newBuilder()
                    .uri(URI.create(webhookUrl))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.toString(), StandardCharsets.UTF_8))
                    .build();
        } catch (IllegalArgumentException e) {
            logger.warning("디스코드 웹훅 URL이 올바르지 않습니다: " + e.getMessage());
            return;
        }

        HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .whenComplete((response, throwable) -> {
                    if (throwable != null) {
                        logger.warning("디스코드 웹훅 전송 실패: " + throwable.getMessage());
                        return;
                    }
                    int status = response.statusCode();
                    if (status >= 300) {
                        logger.warning("디스코드 웹훅 전송 실패 (HTTP " + status + ")");
                    }
                });
    }

    /**
     * @everyone, @here 및 <@id>/<@&id> 형태의 사용자/역할 멘션이 실제로 동작하지 않도록
     * '@' 바로 뒤에 폭 없는 공백을 삽입한다.
     */
    public static String sanitizeMentions(String text) {
        return text.replace("@", "@\u200B");
    }

    private static String escapeJson(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
