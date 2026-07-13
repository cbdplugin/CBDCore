package com.cbd.cbdcore.discord.outbound;

import com.cbd.cbdcore.discord.DiscordJson;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JDK 내장 {@link HttpClient}만으로 디스코드 웹훅에 메시지를 전송하는 구현.
 * 외부 디스코드 라이브러리 의존성을 추가하지 않기 위해 JSON을 직접 문자열로 조립한다.
 *
 * <p>웹훅 URL에는 토큰이 포함되어 있으므로, 예외 로그에는 원본 예외 메시지(URL이 섞여
 * 들어올 수 있음) 대신 예외 클래스 이름만 남긴다.</p>
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
        if (content.isBlank() && message.embed() == null) {
            return CompletableFuture.completedFuture(DeliveryResult.success(0));
        }

        String json = buildJson(content, message.username(), message.avatarUrl(), message.embed());

        HttpRequest request = HttpRequest.newBuilder()
                .uri(webhookUri)
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();

        return HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .handle((response, throwable) -> {
                    if (throwable != null) {
                        logger.warning("디스코드 웹훅 전송 실패: " + throwable.getClass().getSimpleName());
                        return DeliveryResult.retryable(-1, Duration.ofSeconds(5).toMillis());
                    }
                    return toDeliveryResult(response);
                });
    }

    private DeliveryResult toDeliveryResult(HttpResponse<String> response) {
        int status = response.statusCode();

        if (status >= 200 && status < 300) {
            return DeliveryResult.success(status);
        }

        if (status == 429) {
            long retryAfterMillis = parseRetryAfterMillis(response.headers(), response.body());
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

    private static final Pattern BODY_RETRY_AFTER_PATTERN =
            Pattern.compile("\"retry_after\"\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)");

    /**
     * 429 응답의 재시도 대기 시간을 밀리초로 계산한다. 디스코드는 {@code Retry-After}를 초 단위
     * <b>소수</b>(예: {@code 1.5})로 주기도 하므로 정수 파싱({@code firstValueAsLong})으로는
     * 소수점 값을 놓친다. 소수를 지원해 초를 밀리초로 올림 처리한다. 우선순위는
     * {@code Retry-After} 헤더 → {@code X-RateLimit-Reset-After} 헤더 → 응답 본문의
     * {@code retry_after}(역시 소수 초) 순이며, 모두 없으면 1초를 사용한다.
     */
    static long parseRetryAfterMillis(HttpHeaders headers, String body) {
        OptionalDouble seconds = firstHeaderAsSeconds(headers, "Retry-After");
        if (seconds.isEmpty()) {
            seconds = firstHeaderAsSeconds(headers, "X-RateLimit-Reset-After");
        }
        if (seconds.isEmpty()) {
            seconds = retryAfterFromBody(body);
        }
        double value = seconds.orElse(1.0);
        if (Double.isNaN(value) || value < 0) {
            value = 1.0;
        }
        return (long) Math.ceil(value * 1000.0);
    }

    private static OptionalDouble retryAfterFromBody(String body) {
        if (body == null || body.isBlank()) {
            return OptionalDouble.empty();
        }
        Matcher matcher = BODY_RETRY_AFTER_PATTERN.matcher(body);
        if (!matcher.find()) {
            return OptionalDouble.empty();
        }
        try {
            return OptionalDouble.of(Double.parseDouble(matcher.group(1)));
        } catch (NumberFormatException e) {
            return OptionalDouble.empty();
        }
    }

    private static OptionalDouble firstHeaderAsSeconds(HttpHeaders headers, String name) {
        Optional<String> raw = headers.firstValue(name);
        if (raw.isEmpty() || raw.get().isBlank()) {
            return OptionalDouble.empty();
        }
        try {
            return OptionalDouble.of(Double.parseDouble(raw.get().trim()));
        } catch (NumberFormatException e) {
            return OptionalDouble.empty();
        }
    }

    static String buildJson(String content, String username, String avatarUrl, DiscordEmbed embed) {
        StringBuilder json = new StringBuilder("{");
        json.append("\"content\":\"").append(DiscordJson.escapeJson(content)).append('"');
        if (username != null && !username.isBlank()) {
            json.append(",\"username\":\"").append(DiscordJson.escapeJson(username)).append('"');
        }
        if (avatarUrl != null && !avatarUrl.isBlank()) {
            json.append(",\"avatar_url\":\"").append(DiscordJson.escapeJson(avatarUrl)).append('"');
        }
        if (embed != null) {
            json.append(",\"embeds\":[").append(buildEmbedJson(embed)).append(']');
        }
        // 채팅 내용에 @everyone, @here, 사용자/역할 멘션이 포함되어 있어도 실제로 알림이 가지 않도록
        // 문자열을 변형하는 대신 디스코드가 공식 지원하는 allowed_mentions로 전부 차단한다.
        json.append(",\"allowed_mentions\":{\"parse\":[]}");
        json.append('}');
        return json.toString();
    }

    private static String buildEmbedJson(DiscordEmbed embed) {
        StringBuilder json = new StringBuilder("{");
        json.append("\"color\":").append(embed.color());
        if (embed.authorName() != null && !embed.authorName().isBlank()) {
            json.append(",\"author\":{\"name\":\"").append(DiscordJson.escapeJson(embed.authorName())).append('"');
            if (embed.authorIconUrl() != null && !embed.authorIconUrl().isBlank()) {
                json.append(",\"icon_url\":\"").append(DiscordJson.escapeJson(embed.authorIconUrl())).append('"');
            }
            json.append('}');
        }
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
}
