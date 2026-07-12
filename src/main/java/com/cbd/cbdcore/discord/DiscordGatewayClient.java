package com.cbd.cbdcore.discord;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 디스코드 채널의 채팅 메시지를 인게임으로 중계하는 Discord Gateway(WebSocket) 클라이언트.
 *
 * <p>웹훅은 전송 전용이라 채널 메시지를 "받을" 수 없으므로, 디스코드 -> 게임 방향은
 * 봇 토큰으로 Gateway에 연결해 MESSAGE_CREATE 이벤트를 직접 구독하는 방식으로 구현한다.
 * (외부 디스코드 라이브러리 대신 JDK 내장 {@link HttpClient} WebSocket API만 사용 -
 * {@link JdkDiscordWebhookTransport}와 같은 방침.)</p>
 *
 * <p>어느 채널을 들을지는 별도의 config 키를 요구하지 않고, 이미 설정된 {@code webhook-url}
 * 자체에 HTTP GET을 보내 디스코드 공식 "웹훅 정보 조회" 엔드포인트가 돌려주는 channel_id를
 * 그대로 사용한다.</p>
 *
 * <p>세션 재개(RESUME, op 6)는 구현하지 않는다 - 연결이 끊기면 항상 새로 연결 + 재인증하며,
 * 그 사이 짧은 공백 동안의 메시지 몇 개를 놓칠 수 있음을 감수한다 (개인 서버 규모의 채팅
 * 중계에는 충분한 단순화).</p>
 */
public final class DiscordGatewayClient {

    private static final URI GATEWAY_URI = URI.create("wss://gateway.discord.gg/?v=10&encoding=json");
    private static final Duration RECONNECT_DELAY = Duration.ofSeconds(5);

    /** GUILDS | GUILD_MESSAGES | MESSAGE_CONTENT */
    private static final int INTENTS = (1) | (1 << 9) | (1 << 15);

    /** 재연결해도 성공할 수 없는 Gateway 종료 코드 (인증 실패, 허용되지 않은 intent 등). */
    private static final java.util.Set<Integer> NON_RECOVERABLE_CLOSE_CODES =
            java.util.Set.of(4004, 4010, 4011, 4012, 4013, 4014);

    private static final Pattern MENTION_PATTERN = Pattern.compile("<@!?(\\d+)>");
    private static final Pattern CUSTOM_EMOJI_PATTERN = Pattern.compile("<a?:(\\w+):\\d+>");

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final Plugin plugin;
    private final Logger logger;
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "CBDCore-Discord-Gateway");
                thread.setDaemon(true);
                return thread;
            });

    private final AtomicInteger connectionGeneration = new AtomicInteger(0);
    private volatile DiscordSettings settings = DiscordSettings.disabled();
    private volatile WebSocket currentWebSocket;
    private volatile boolean shuttingDown = false;

    public DiscordGatewayClient(Plugin plugin, Logger logger) {
        this.plugin = plugin;
        this.logger = logger;
    }

    /**
     * 메인 스레드(reload)에서만 호출된다. 봇 토큰/웹훅 URL이 바뀌었으면 기존 연결을 끊고 새로 연결한다.
     */
    public synchronized void updateSettings(DiscordSettings newSettings) {
        DiscordSettings previous = this.settings;
        this.settings = newSettings;

        if (!newSettings.canRelayToGame()) {
            disconnect();
            return;
        }

        boolean needsRestart = !previous.canRelayToGame()
                || !Objects.equals(previous.webhookUri(), newSettings.webhookUri())
                || !Objects.equals(previous.botToken(), newSettings.botToken());

        if (needsRestart) {
            disconnect();
            int myGeneration = connectionGeneration.incrementAndGet();
            beginConnect(newSettings, myGeneration);
        }
    }

    private void disconnect() {
        connectionGeneration.incrementAndGet();
        WebSocket webSocket = currentWebSocket;
        currentWebSocket = null;
        if (webSocket != null) {
            webSocket.abort();
        }
    }

    public void shutdown() {
        shuttingDown = true;
        disconnect();
        scheduler.shutdownNow();
    }

    private void beginConnect(DiscordSettings target, int myGeneration) {
        HttpRequest request = HttpRequest.newBuilder(target.webhookUri())
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(response -> {
                    if (response.statusCode() != 200) {
                        throw new RuntimeException("웹훅 정보 조회 실패 (HTTP " + response.statusCode() + ")");
                    }
                    JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                    return json.get("channel_id").getAsString();
                })
                .whenComplete((channelId, throwable) -> {
                    if (myGeneration != connectionGeneration.get() || shuttingDown) {
                        return;
                    }
                    if (throwable != null) {
                        logger.warning("디스코드 채널 정보를 확인하지 못해 채팅 중계 연결을 시작할 수 없습니다: "
                                + throwable.getMessage());
                        scheduleReconnect(target, myGeneration);
                        return;
                    }
                    connectGateway(target, channelId, myGeneration);
                });
    }

    private void connectGateway(DiscordSettings target, String channelId, int myGeneration) {
        GatewayListener listener = new GatewayListener(target, channelId, myGeneration);
        HTTP_CLIENT.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .buildAsync(GATEWAY_URI, listener)
                .whenComplete((webSocket, throwable) -> {
                    if (myGeneration != connectionGeneration.get() || shuttingDown) {
                        if (webSocket != null) {
                            webSocket.abort();
                        }
                        return;
                    }
                    if (throwable != null) {
                        logger.warning("디스코드 Gateway 연결 실패: " + throwable.getMessage());
                        scheduleReconnect(target, myGeneration);
                        return;
                    }
                    currentWebSocket = webSocket;
                });
    }

    private void scheduleReconnect(DiscordSettings target, int myGeneration) {
        if (shuttingDown) {
            return;
        }
        try {
            scheduler.schedule(() -> {
                if (myGeneration == connectionGeneration.get() && !shuttingDown) {
                    beginConnect(target, myGeneration);
                }
            }, RECONNECT_DELAY.toMillis(), TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // shutdown()과 경합해 스케줄러가 이미 닫힌 경우 - 무시.
        }
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
        java.util.Map<String, String> idToName = new java.util.HashMap<>();
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
     * 하나의 Gateway 연결(하나의 WebSocket 세션)에 대한 상태와 콜백을 담당한다.
     * 연결이 끊길 때마다 새 인스턴스가 만들어지므로 인스턴스 필드는 재사용되지 않는다.
     */
    private final class GatewayListener implements WebSocket.Listener {

        private final DiscordSettings target;
        private final String channelId;
        private final int myGeneration;
        private final StringBuilder messageBuffer = new StringBuilder();
        private final AtomicLong lastSequence = new AtomicLong(-1);
        private volatile ScheduledFuture<?> heartbeatFuture;

        GatewayListener(DiscordSettings target, String channelId, int myGeneration) {
            this.target = target;
            this.channelId = channelId;
            this.myGeneration = myGeneration;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            messageBuffer.append(data);
            webSocket.request(1);
            if (!last) {
                return null;
            }
            String text = messageBuffer.toString();
            messageBuffer.setLength(0);
            try {
                handlePayload(webSocket, text);
            } catch (RuntimeException e) {
                logger.warning("디스코드 Gateway 메시지 처리 중 오류: " + e.getMessage());
            }
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            cancelHeartbeat();
            if (isStale()) {
                return null;
            }
            currentWebSocket = null;
            if (NON_RECOVERABLE_CLOSE_CODES.contains(statusCode)) {
                logger.warning("디스코드 Gateway 연결이 복구 불가능한 이유로 종료되었습니다 (코드 " + statusCode + ", " + reason
                        + "). bot-token과 Discord Developer Portal의 MESSAGE CONTENT INTENT 설정을 확인하세요.");
                return null;
            }
            logger.warning("디스코드 Gateway 연결이 끊어졌습니다 (코드 " + statusCode + "). 재연결을 시도합니다.");
            scheduleReconnect(target, myGeneration);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            cancelHeartbeat();
            if (isStale()) {
                return;
            }
            currentWebSocket = null;
            logger.warning("디스코드 Gateway 연결 오류: " + error.getMessage());
            scheduleReconnect(target, myGeneration);
        }

        private boolean isStale() {
            return myGeneration != connectionGeneration.get() || shuttingDown;
        }

        private void handlePayload(WebSocket webSocket, String text) {
            JsonObject json = JsonParser.parseString(text).getAsJsonObject();
            int op = json.get("op").getAsInt();

            if (!json.get("s").isJsonNull()) {
                lastSequence.set(json.get("s").getAsLong());
            }

            switch (op) {
                case 10 -> { // Hello
                    long heartbeatIntervalMillis = json.getAsJsonObject("d").get("heartbeat_interval").getAsLong();
                    startHeartbeat(webSocket, heartbeatIntervalMillis);
                    identify(webSocket);
                }
                case 0 -> handleDispatch(json); // Dispatch
                case 1 -> sendHeartbeat(webSocket); // 서버가 즉시 하트비트 요청
                case 7 -> webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "reconnect requested"); // Reconnect
                case 9 -> webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "invalid session"); // Invalid Session
                default -> { /* 11 (Heartbeat ACK) 등은 무시 */ }
            }
        }

        private void identify(WebSocket webSocket) {
            String token = JdkDiscordWebhookTransport.escapeJson(target.botToken());
            String payload = "{\"op\":2,\"d\":{\"token\":\"" + token + "\",\"intents\":" + INTENTS
                    + ",\"properties\":{\"os\":\"java\",\"browser\":\"cbdcore\",\"device\":\"cbdcore\"}}}";
            webSocket.sendText(payload, true);
        }

        private void startHeartbeat(WebSocket webSocket, long intervalMillis) {
            heartbeatFuture = scheduler.scheduleAtFixedRate(
                    () -> sendHeartbeat(webSocket), intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
        }

        private void cancelHeartbeat() {
            ScheduledFuture<?> future = heartbeatFuture;
            if (future != null) {
                future.cancel(false);
            }
        }

        private void sendHeartbeat(WebSocket webSocket) {
            long sequence = lastSequence.get();
            String payload = "{\"op\":1,\"d\":" + (sequence < 0 ? "null" : sequence) + "}";
            webSocket.sendText(payload, true);
        }

        private void handleDispatch(JsonObject json) {
            if (json.get("t").isJsonNull()) {
                return;
            }
            String eventType = json.get("t").getAsString();
            if (!"MESSAGE_CREATE".equals(eventType)) {
                return;
            }

            JsonObject data = json.getAsJsonObject("d");
            if (!channelId.equals(data.get("channel_id").getAsString())) {
                return;
            }
            if (data.has("webhook_id") && !data.get("webhook_id").isJsonNull()) {
                return; // 우리가 게임 -> 디스코드로 보낸 웹훅 메시지 자기 자신은 무시
            }

            JsonObject author = data.getAsJsonObject("author");
            if (author.has("bot") && author.get("bot").getAsBoolean()) {
                return;
            }

            String content = data.get("content").getAsString();
            content = resolveMentions(content, data.getAsJsonArray("mentions"));
            content = stripCustomEmoji(content);
            if (content.isBlank()) {
                return;
            }

            String displayName = resolveDisplayName(author);
            relayToGame(displayName, content);
        }

        private void relayToGame(String displayName, String content) {
            Component component = Component.text("[디스코드] ", NamedTextColor.GRAY)
                    .append(Component.text(displayName, NamedTextColor.WHITE))
                    .append(Component.text(" : ", NamedTextColor.GRAY))
                    .append(Component.text(content, NamedTextColor.WHITE));
            Bukkit.getScheduler().runTask(plugin, () -> Bukkit.broadcast(component));
        }
    }
}
