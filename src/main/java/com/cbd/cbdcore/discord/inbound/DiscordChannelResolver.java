package com.cbd.cbdcore.discord.inbound;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletionStage;

/**
 * 웹훅 URL 자체에 HTTP GET을 보내 디스코드 공식 "웹훅 정보 조회"가 돌려주는 channel_id를
 * 알아낸다. 덕분에 수신할 채널을 위한 별도 config 키 없이도 이미 설정된 webhook-url만으로
 * 어느 채널을 들을지 결정할 수 있다 (config에 channel-id를 직접 지정하면 이 조회는 건너뛴다).
 */
public final class DiscordChannelResolver {

    private final HttpClient httpClient;

    public DiscordChannelResolver(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public CompletionStage<String> resolve(URI webhookUri) {
        HttpRequest request = HttpRequest.newBuilder(webhookUri)
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(response -> {
                    int status = response.statusCode();
                    if (status == 429 || status >= 500) {
                        throw new ChannelResolutionException(true, "웹훅 정보 조회 일시적 실패 (HTTP " + status + ")");
                    }
                    if (status != 200) {
                        throw new ChannelResolutionException(false, "웹훅 정보 조회 실패 (HTTP " + status + ")");
                    }
                    return parseChannelId(response.body());
                });
    }

    static String parseChannelId(String body) {
        JsonObject json;
        try {
            json = JsonParser.parseString(body).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new ChannelResolutionException(false, "웹훅 정보 응답을 해석할 수 없습니다.");
        }
        JsonElement channelId = json.get("channel_id");
        if (channelId == null || channelId.isJsonNull() || channelId.getAsString().isBlank()) {
            throw new ChannelResolutionException(false, "웹훅 정보에 channel_id가 없습니다.");
        }
        return channelId.getAsString();
    }

    /**
     * 채널 조회 실패. {@code recoverable}이 참이면 잠시 후 재시도로 성공할 수 있는 일시적
     * 오류(429, 5xx, 네트워크)이고, 거짓이면 재시도해도 소용없는 실패(잘못된/삭제된 웹훅 등)다.
     */
    public static final class ChannelResolutionException extends RuntimeException {

        private final boolean recoverable;

        public ChannelResolutionException(boolean recoverable, String message) {
            super(message);
            this.recoverable = recoverable;
        }

        public boolean isRecoverable() {
            return recoverable;
        }
    }
}
