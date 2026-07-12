package com.cbd.cbdcore.discord;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.logging.Logger;

/**
 * JDK 내장 {@link HttpClient}만으로 디스코드 웹훅에 메시지를 전송하는 구현.
 * 외부 디스코드 라이브러리 의존성을 추가하지 않기 위해 JSON을 직접 문자열로 조립한다.
 */
public final class JdkDiscordWebhookTransport implements DiscordWebhookTransport {

    /** 디스코드 웹훅 content 필드의 최대 길이 (공식 제한). */
    static final int MAX_CONTENT_LENGTH = 2_000;

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final Logger logger;

    public JdkDiscordWebhookTransport(Logger logger) {
        this.logger = logger;
    }

    @Override
    public CompletionStage<DeliveryResult> send(URI webhookUri, DiscordMessage message) {
        String content = truncateToCodePointLimit(message.content(), MAX_CONTENT_LENGTH);
        if (content.isBlank()) {
            return CompletableFuture.completedFuture(DeliveryResult.success(0));
        }

        String json = buildJson(content, message.username(), message.avatarUrl());

        HttpRequest request = HttpRequest.newBuilder()
                .uri(webhookUri)
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();

        return HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .handle((response, throwable) -> {
                    if (throwable != null) {
                        logger.warning("디스코드 웹훅 전송 실패: " + throwable.getMessage());
                        return DeliveryResult.retryable(-1, Duration.ofSeconds(5).toMillis());
                    }
                    return toDeliveryResult(response);
                });
    }

    private DeliveryResult toDeliveryResult(HttpResponse<Void> response) {
        int status = response.statusCode();

        if (status >= 200 && status < 300) {
            return DeliveryResult.success(status);
        }

        if (status == 429) {
            long retryAfterMillis = response.headers().firstValueAsLong("Retry-After").orElse(1L) * 1000L;
            logger.warning("디스코드 웹훅 전송이 속도 제한(429)에 걸렸습니다. " + retryAfterMillis + "ms 후 재시도합니다.");
            return DeliveryResult.retryable(status, retryAfterMillis);
        }

        if (status >= 500) {
            logger.warning("디스코드 웹훅 전송 실패 (HTTP " + status + "), 잠시 후 재시도합니다.");
            return DeliveryResult.retryable(status, Duration.ofSeconds(5).toMillis());
        }

        logger.warning("디스코드 웹훅 전송 실패 (HTTP " + status + ")");
        return DeliveryResult.permanentFailure(status);
    }

    static String buildJson(String content, String username, String avatarUrl) {
        StringBuilder json = new StringBuilder("{");
        json.append("\"content\":\"").append(escapeJson(content)).append('"');
        if (username != null && !username.isBlank()) {
            json.append(",\"username\":\"").append(escapeJson(username)).append('"');
        }
        if (avatarUrl != null && !avatarUrl.isBlank()) {
            json.append(",\"avatar_url\":\"").append(escapeJson(avatarUrl)).append('"');
        }
        // 채팅 내용에 @everyone, @here, 사용자/역할 멘션이 포함되어 있어도 실제로 알림이 가지 않도록
        // 문자열을 변형하는 대신 디스코드가 공식 지원하는 allowed_mentions로 전부 차단한다.
        json.append(",\"allowed_mentions\":{\"parse\":[]}");
        json.append('}');
        return json.toString();
    }

    static String truncateToCodePointLimit(String text, int maxCodePoints) {
        if (text == null) {
            return "";
        }
        int codePointCount = text.codePointCount(0, text.length());
        if (codePointCount <= maxCodePoints) {
            return text;
        }
        int cutIndex = text.offsetByCodePoints(0, maxCodePoints);
        return text.substring(0, cutIndex);
    }

    static String escapeJson(String text) {
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
