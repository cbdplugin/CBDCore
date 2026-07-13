package com.cbd.cbdcore.discord.outbound;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdkDiscordWebhookTransportTest {

    private HttpServer server;
    private JdkDiscordWebhookTransport transport;
    private final List<String> receivedBodies = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        transport = new JdkDiscordWebhookTransport(Logger.getLogger("JdkDiscordWebhookTransportTest"));
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private URI startServerReturning(int statusCode, String retryAfterHeader) throws IOException {
        server.createContext("/api/webhooks/test", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            receivedBodies.add(new String(body, StandardCharsets.UTF_8));
            if (retryAfterHeader != null) {
                exchange.getResponseHeaders().add("Retry-After", retryAfterHeader);
            }
            exchange.sendResponseHeaders(statusCode, -1);
            exchange.close();
        });
        server.start();
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/webhooks/test");
    }

    @Test
    void status2xxIsSuccess() throws IOException {
        URI uri = startServerReturning(204, null);

        DeliveryResult result = transport.send(uri, new DiscordMessage("hello", "user", null))
                .toCompletableFuture().join();

        assertTrue(result.success());
        assertEquals(204, result.statusCode());
    }

    @Test
    void status400IsPermanentFailure() throws IOException {
        URI uri = startServerReturning(400, null);

        DeliveryResult result = transport.send(uri, new DiscordMessage("hello", null, null))
                .toCompletableFuture().join();

        assertFalse(result.success());
        assertFalse(result.isRetryable());
    }

    @Test
    void status429IsRetryableAndUsesRetryAfterHeader() throws IOException {
        URI uri = startServerReturning(429, "2");

        DeliveryResult result = transport.send(uri, new DiscordMessage("hello", null, null))
                .toCompletableFuture().join();

        assertFalse(result.success());
        assertTrue(result.isRetryable());
        assertEquals(2000L, result.retryAfterMillis());
    }

    @Test
    void status5xxIsRetryable() throws IOException {
        URI uri = startServerReturning(500, null);

        DeliveryResult result = transport.send(uri, new DiscordMessage("hello", null, null))
                .toCompletableFuture().join();

        assertFalse(result.success());
        assertTrue(result.isRetryable());
    }

    @Test
    void requestBodyBlocksMentionsAndEscapesSpecialCharacters() throws IOException {
        URI uri = startServerReturning(204, null);

        transport.send(uri, new DiscordMessage("hello \"world\"\n한글 @everyone", "user", null))
                .toCompletableFuture().join();

        String body = receivedBodies.get(0);
        assertTrue(body.contains("\"allowed_mentions\":{\"parse\":[]}"), body);
        assertTrue(body.contains("\\\"world\\\""), body);
        assertTrue(body.contains("\\n"), body);
        assertTrue(body.contains("한글"), body);
    }

    @Test
    void blankMessageIsNeverSentOverTheNetwork() {
        // 서버를 시작하지 않고, 연결 자체가 불가능한 주소를 준다.
        // 실제로 요청을 시도했다면 연결 실패로 실패 결과가 왔을 것이다.
        DeliveryResult result = transport.send(
                URI.create("http://127.0.0.1:1/api/webhooks/unused"),
                new DiscordMessage("   ", null, null)
        ).toCompletableFuture().join();

        assertTrue(result.success());
    }

    @Test
    void embedOnlyMessageIsSentEvenWithBlankContent() throws IOException {
        URI uri = startServerReturning(204, null);

        DiscordEmbed embed = new DiscordEmbed("player 님이 접속했습니다.", "https://example.com/avatar.png", 0x57F287);
        DeliveryResult result = transport.send(uri, new DiscordMessage("", null, null, embed))
                .toCompletableFuture().join();

        assertTrue(result.success());
        String body = receivedBodies.get(0);
        assertTrue(body.contains("\"embeds\":[{\"color\":5763719"), body);
        assertTrue(body.contains("\"author\":{\"name\":\"player 님이 접속했습니다.\""), body);
        assertTrue(body.contains("\"icon_url\":\"https://example.com/avatar.png\""), body);
    }

    @Test
    void retryAfterHeaderSupportsDecimalSeconds() {
        HttpHeaders headers = HttpHeaders.of(Map.of("Retry-After", List.of("1.5")), (k, v) -> true);

        assertEquals(1500L, JdkDiscordWebhookTransport.parseRetryAfterMillis(headers));
    }

    @Test
    void retryAfterFallsBackToResetAfterHeader() {
        HttpHeaders headers = HttpHeaders.of(
                Map.of("X-RateLimit-Reset-After", List.of("0.25")), (k, v) -> true);

        assertEquals(250L, JdkDiscordWebhookTransport.parseRetryAfterMillis(headers));
    }

    @Test
    void retryAfterDefaultsToOneSecondWhenAbsent() {
        HttpHeaders headers = HttpHeaders.of(Map.of(), (k, v) -> true);

        assertEquals(1000L, JdkDiscordWebhookTransport.parseRetryAfterMillis(headers));
    }

    @Test
    void contentLongerThanDiscordLimitIsTruncatedByCodePoint() {
        String longText = "a".repeat(3000);

        String truncated = JdkDiscordWebhookTransport.truncateToCodePointLimit(
                longText, JdkDiscordWebhookTransport.MAX_CONTENT_LENGTH);

        assertEquals(JdkDiscordWebhookTransport.MAX_CONTENT_LENGTH, truncated.codePointCount(0, truncated.length()));
    }
}
