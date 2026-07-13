package com.cbd.cbdcore.discord.inbound;

import com.cbd.cbdcore.discord.DiscordJson;
import com.cbd.cbdcore.discord.inbound.DiscordChannelResolver.ChannelResolutionException;
import com.cbd.cbdcore.discord.inbound.DiscordInboundMessageMapper.InboundMessage;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.plugin.Plugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * 디스코드 채널의 채팅을 인게임으로 중계하는 Discord Gateway(WebSocket) 클라이언트.
 *
 * <p>웹훅은 전송 전용이라 채널 메시지를 받을 수 없으므로, 디스코드 -&gt; 게임 방향은 봇 토큰으로
 * Gateway에 연결해 MESSAGE_CREATE 이벤트를 구독한다. 외부 디스코드 라이브러리 대신 JDK 내장
 * {@link HttpClient} WebSocket API만 사용한다.</p>
 *
 * <p>연결 안정성을 위해 다음을 구현한다:
 * <ul>
 *   <li>하트비트 ACK 감시: ACK를 받지 못한 채 다음 하트비트 시점이 오면 좀비 연결로 보고 재연결.</li>
 *   <li>지수 백오프 + 지터: 재연결 대기 시간을 5초에서 최대 5분까지 늘리며 무작위 지터를 더한다.</li>
 *   <li>세션 재개(RESUME): 끊긴 뒤에도 가능하면 세션을 이어받아 놓친 메시지를 재전송받는다.</li>
 *   <li>상태 기계: {@link GatewayState#FAILED}(복구 불가)에서는 재시도를 멈추고 {@code /cbdcore reload}로만
 *       복구한다.</li>
 * </ul>
 *
 * <p>순수 로직(메시지 정제, 채널 조회 파싱)은 {@link DiscordInboundMessageMapper},
 * {@link DiscordChannelResolver}로 분리해 단위 테스트로 검증한다. 이 클래스는 연결 수명주기와
 * 스레드 조율만 담당한다.</p>
 */
public final class DiscordGatewayClient {

    private static final URI GATEWAY_URI = URI.create("wss://gateway.discord.gg/?v=10&encoding=json");

    /** GUILDS | GUILD_MESSAGES | MESSAGE_CONTENT */
    private static final int INTENTS = (1) | (1 << 9) | (1 << 15);

    private static final long MIN_BACKOFF_MILLIS = Duration.ofSeconds(5).toMillis();
    private static final long MAX_BACKOFF_MILLIS = Duration.ofMinutes(5).toMillis();

    /** 재연결/재개해도 성공할 수 없는 종료 코드 (인증 실패, 허용되지 않은 intent 등). */
    private static final Set<Integer> NON_RECOVERABLE_CLOSE_CODES =
            Set.of(4004, 4010, 4011, 4012, 4013, 4014);
    /** 세션을 이어받을 수 없어 새로 인증해야 하는 종료 코드 (잘못된 시퀀스, 세션 타임아웃). */
    private static final Set<Integer> SESSION_INVALIDATING_CLOSE_CODES = Set.of(4007, 4009);

    /** 재연결 방식. */
    private enum ReconnectMode {
        /** 복구 불가 - 재시도하지 않고 FAILED 상태로 전환. */
        FAIL,
        /** 세션을 버리고 처음부터 새로 인증. */
        FRESH,
        /** 기존 세션을 이어받아 재개 시도. */
        RESUME
    }

    private final Plugin plugin;
    private final Logger logger;
    private final HttpClient httpClient;
    private final DiscordChannelResolver channelResolver;
    private final DiscordInboundMessageMapper mapper;
    private final DiscordInboundRelay relay;
    private final DiscordGatewaySession session = new DiscordGatewaySession();

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "CBDCore-Discord-Gateway");
                thread.setDaemon(true);
                return thread;
            });

    /** 재연결 세대. 설정 변경/재연결마다 증가하며, 뒤늦게 도착한 옛 연결의 콜백을 무시하는 데 쓴다. */
    private final AtomicInteger generation = new AtomicInteger(0);
    private volatile InboundSettings settings = InboundSettings.disabled();
    private volatile GatewayState state = GatewayState.DISABLED;
    private volatile WebSocket currentWebSocket;
    private volatile boolean shuttingDown = false;
    /** 웹훅에서 한 번 알아낸 채널 ID는 재연결마다 다시 조회하지 않고 재사용한다. */
    private volatile String resolvedChannelId;
    private volatile int backoffAttempt = 0;

    public DiscordGatewayClient(Plugin plugin, Logger logger, HttpClient httpClient) {
        this.plugin = plugin;
        this.logger = logger;
        this.httpClient = httpClient;
        this.channelResolver = new DiscordChannelResolver(httpClient);
        this.mapper = new DiscordInboundMessageMapper();
        this.relay = new DiscordInboundRelay(plugin);
    }

    public GatewayState state() {
        return state;
    }

    /**
     * 메인 스레드(reload)에서만 호출된다. 봇 토큰/채널이 바뀌었거나 이전에 복구 불가(FAILED)
     * 상태였으면 기존 연결을 끊고 새로 연결한다. 그 외에 이미 정상 연결 중이면 그대로 둔다.
     */
    public synchronized void updateSettings(InboundSettings newSettings) {
        InboundSettings previous = settings;
        settings = newSettings;

        if (!newSettings.canRelay()) {
            disconnect();
            state = GatewayState.DISABLED;
            return;
        }

        boolean wasDisabled = !previous.canRelay();
        boolean tokenChanged = !Objects.equals(previous.botToken(), newSettings.botToken());
        boolean channelChanged = !Objects.equals(previous.channelId(), newSettings.channelId())
                || !Objects.equals(previous.webhookUri(), newSettings.webhookUri());
        boolean recoverFromFailure = state == GatewayState.FAILED;

        if (wasDisabled || tokenChanged || channelChanged || recoverFromFailure) {
            if (channelChanged) {
                resolvedChannelId = null;
            }
            session.invalidate();
            restart();
        }
    }

    private void restart() {
        disconnect();
        backoffAttempt = 0;
        int myGeneration = generation.get();
        connect(myGeneration);
    }

    /** 진행 중인 연결을 무효화한다. 세대를 올려 이후 도착하는 옛 콜백을 모두 무시하게 만든다. */
    private void disconnect() {
        generation.incrementAndGet();
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

    private boolean isStale(int myGeneration) {
        return myGeneration != generation.get() || shuttingDown;
    }

    private void connect(int myGeneration) {
        InboundSettings target = settings;
        if (!target.canRelay()) {
            state = GatewayState.DISABLED;
            return;
        }
        state = GatewayState.CONNECTING;

        if (target.hasExplicitChannel()) {
            openWebSocket(target, target.channelId(), myGeneration);
            return;
        }
        if (resolvedChannelId != null) {
            openWebSocket(target, resolvedChannelId, myGeneration);
            return;
        }

        channelResolver.resolve(target.webhookUri()).whenComplete((channelId, throwable) -> {
            if (isStale(myGeneration)) {
                return;
            }
            if (throwable != null) {
                boolean recoverable = !(unwrap(throwable) instanceof ChannelResolutionException failure)
                        || failure.isRecoverable();
                if (recoverable) {
                    logger.warning("디스코드 채널 정보 조회에 실패해 잠시 후 다시 시도합니다: " + throwable.getMessage());
                    state = GatewayState.RETRY_WAIT;
                    scheduleReconnect(myGeneration, "채널 조회 실패");
                } else {
                    logger.warning("디스코드 채널 정보를 확인할 수 없어 채팅 중계를 중단합니다 ("
                            + throwable.getMessage() + "). webhook-url 설정을 확인한 뒤 /cbdcore reload 하세요.");
                    state = GatewayState.FAILED;
                }
                return;
            }
            resolvedChannelId = channelId;
            openWebSocket(target, channelId, myGeneration);
        });
    }

    private void openWebSocket(InboundSettings target, String channelId, int myGeneration) {
        boolean resuming = session.canResume();
        URI uri = resuming ? session.resumeUri() : GATEWAY_URI;
        GatewayListener listener = new GatewayListener(target, channelId, myGeneration, resuming);

        httpClient.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .buildAsync(uri, listener)
                .whenComplete((webSocket, throwable) -> {
                    if (isStale(myGeneration)) {
                        if (webSocket != null) {
                            webSocket.abort();
                        }
                        return;
                    }
                    if (throwable != null) {
                        logger.warning("디스코드 Gateway 연결 실패: " + throwable.getClass().getSimpleName());
                        state = GatewayState.RETRY_WAIT;
                        scheduleReconnect(myGeneration, "연결 실패");
                        return;
                    }
                    currentWebSocket = webSocket;
                });
    }

    private void scheduleReconnect(int myGeneration, String reason) {
        if (shuttingDown) {
            return;
        }
        long delay = nextBackoffMillis();
        logger.info("디스코드 Gateway 재연결을 약 " + delay + "ms 후 시도합니다 (" + reason + ").");
        try {
            scheduler.schedule(() -> {
                if (!isStale(myGeneration)) {
                    connect(myGeneration);
                }
            }, delay, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            // shutdown()과 경합해 스케줄러가 이미 닫힌 경우 - 무시.
        }
    }

    /** 지수 백오프(5초 -> 최대 5분)에 절반 크기의 무작위 지터를 더한 대기 시간. */
    private long nextBackoffMillis() {
        int attempt = Math.min(backoffAttempt++, 6);
        long base = Math.min(MIN_BACKOFF_MILLIS << attempt, MAX_BACKOFF_MILLIS);
        long half = base / 2;
        return half + ThreadLocalRandom.current().nextLong(half + 1);
    }

    private void handleDisconnect(GatewayListener listener, ReconnectMode mode, String reason) {
        if (!listener.closeHandled.compareAndSet(false, true)) {
            return;
        }
        listener.cancelHeartbeat();
        if (isStale(listener.myGeneration)) {
            return;
        }
        currentWebSocket = null;
        WebSocket webSocket = listener.webSocket;
        if (webSocket != null) {
            webSocket.abort();
        }

        if (mode == ReconnectMode.FAIL) {
            session.invalidate();
            state = GatewayState.FAILED;
            logger.warning("디스코드 Gateway 연결이 복구 불가능한 이유로 종료되었습니다 (" + reason
                    + "). bot-token과 Discord Developer Portal의 MESSAGE CONTENT INTENT 설정을 확인한 뒤"
                    + " /cbdcore reload 하세요.");
            return;
        }
        if (mode == ReconnectMode.FRESH) {
            session.invalidate();
        }
        state = GatewayState.RETRY_WAIT;
        scheduleReconnect(listener.myGeneration, reason);
    }

    private static ReconnectMode classifyClose(int statusCode) {
        if (NON_RECOVERABLE_CLOSE_CODES.contains(statusCode)) {
            return ReconnectMode.FAIL;
        }
        if (SESSION_INVALIDATING_CLOSE_CODES.contains(statusCode)) {
            return ReconnectMode.FRESH;
        }
        return ReconnectMode.RESUME;
    }

    private static Throwable unwrap(Throwable throwable) {
        return throwable.getCause() != null ? throwable.getCause() : throwable;
    }

    /**
     * 하나의 Gateway 연결(하나의 WebSocket 세션)에 대한 상태와 콜백을 담당한다.
     * 연결이 끊길 때마다 새 인스턴스가 만들어지므로 인스턴스 필드는 재사용되지 않는다.
     */
    private final class GatewayListener implements WebSocket.Listener {

        private final InboundSettings target;
        private final String channelId;
        private final int myGeneration;
        private final boolean resuming;
        private final StringBuilder messageBuffer = new StringBuilder();
        private final AtomicBoolean awaitingAck = new AtomicBoolean(false);
        final AtomicBoolean closeHandled = new AtomicBoolean(false);
        private volatile ScheduledFuture<?> heartbeatFuture;
        volatile WebSocket webSocket;

        GatewayListener(InboundSettings target, String channelId, int myGeneration, boolean resuming) {
            this.target = target;
            this.channelId = channelId;
            this.myGeneration = myGeneration;
            this.resuming = resuming;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            this.webSocket = webSocket;
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
            if (isStale(myGeneration)) {
                return null;
            }
            try {
                handlePayload(webSocket, text);
            } catch (RuntimeException e) {
                logger.warning("디스코드 Gateway 메시지 처리 중 오류: " + e.getClass().getSimpleName());
            }
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            handleDisconnect(this, classifyClose(statusCode), "종료 코드 " + statusCode);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            handleDisconnect(this, ReconnectMode.RESUME, "연결 오류 " + error.getClass().getSimpleName());
        }

        private void handlePayload(WebSocket webSocket, String text) {
            JsonObject json = JsonParser.parseString(text).getAsJsonObject();
            int op = json.get("op").getAsInt();

            if (json.has("s") && !json.get("s").isJsonNull()) {
                session.updateSequence(json.get("s").getAsLong());
            }

            switch (op) {
                case 10 -> handleHello(webSocket, json);
                case 0 -> handleDispatch(json);
                case 1 -> sendHeartbeat(webSocket);
                case 7 -> handleDisconnect(this, ReconnectMode.RESUME, "서버 재연결 요청(op7)");
                case 9 -> handleInvalidSession(json);
                case 11 -> awaitingAck.set(false);
                default -> { /* 그 외 opcode는 무시 */ }
            }
        }

        private void handleHello(WebSocket webSocket, JsonObject json) {
            long heartbeatIntervalMillis = json.getAsJsonObject("d").get("heartbeat_interval").getAsLong();
            startHeartbeat(webSocket, heartbeatIntervalMillis);
            if (resuming && session.canResume()) {
                sendResume(webSocket);
            } else {
                sendIdentify(webSocket);
            }
        }

        private void handleInvalidSession(JsonObject json) {
            boolean resumable = !json.get("d").isJsonNull() && json.get("d").getAsBoolean();
            handleDisconnect(this, resumable ? ReconnectMode.RESUME : ReconnectMode.FRESH, "세션 무효(op9)");
        }

        private void sendIdentify(WebSocket webSocket) {
            String token = DiscordJson.escapeJson(target.botToken());
            String payload = "{\"op\":2,\"d\":{\"token\":\"" + token + "\",\"intents\":" + INTENTS
                    + ",\"properties\":{\"os\":\"java\",\"browser\":\"cbdcore\",\"device\":\"cbdcore\"}}}";
            webSocket.sendText(payload, true);
        }

        private void sendResume(WebSocket webSocket) {
            String token = DiscordJson.escapeJson(target.botToken());
            String sessionId = DiscordJson.escapeJson(session.sessionId());
            String payload = "{\"op\":6,\"d\":{\"token\":\"" + token + "\",\"session_id\":\"" + sessionId
                    + "\",\"seq\":" + session.sequence() + "}}";
            webSocket.sendText(payload, true);
        }

        private void startHeartbeat(WebSocket webSocket, long intervalMillis) {
            // 디스코드 권장: 첫 하트비트는 interval * random 만큼 지연시켜 다수 클라이언트가
            // 동시에 하트비트를 보내지 않도록 분산한다.
            long firstDelay = (long) (ThreadLocalRandom.current().nextDouble() * intervalMillis);
            awaitingAck.set(false);
            heartbeatFuture = scheduler.scheduleAtFixedRate(
                    () -> beat(webSocket), firstDelay, intervalMillis, TimeUnit.MILLISECONDS);
        }

        private void beat(WebSocket webSocket) {
            if (isStale(myGeneration)) {
                cancelHeartbeat();
                return;
            }
            // 이전 하트비트의 ACK를 아직 못 받았는데 다음 주기가 왔다면 좀비 연결로 판단한다.
            if (!awaitingAck.compareAndSet(false, true)) {
                logger.warning("디스코드 Gateway 하트비트 ACK를 받지 못했습니다. 연결을 재개합니다.");
                handleDisconnect(this, ReconnectMode.RESUME, "하트비트 ACK 없음");
                return;
            }
            sendHeartbeat(webSocket);
        }

        private void cancelHeartbeat() {
            ScheduledFuture<?> future = heartbeatFuture;
            if (future != null) {
                future.cancel(false);
            }
        }

        private void sendHeartbeat(WebSocket webSocket) {
            long sequence = session.sequence();
            String payload = "{\"op\":1,\"d\":" + (sequence < 0 ? "null" : sequence) + "}";
            webSocket.sendText(payload, true);
        }

        private void handleDispatch(JsonObject json) {
            if (json.get("t").isJsonNull()) {
                return;
            }
            String eventType = json.get("t").getAsString();
            switch (eventType) {
                case "READY" -> {
                    JsonObject data = json.getAsJsonObject("d");
                    session.recordReady(
                            data.get("session_id").getAsString(),
                            data.get("resume_gateway_url").getAsString());
                    onConnected("연결 완료");
                }
                case "RESUMED" -> onConnected("세션 재개 완료");
                case "MESSAGE_CREATE" -> handleMessageCreate(json.getAsJsonObject("d"));
                default -> { /* 관심 없는 이벤트 무시 */ }
            }
        }

        private void onConnected(String reason) {
            state = GatewayState.READY;
            backoffAttempt = 0;
            logger.info("디스코드 채팅 중계 " + reason + ".");
        }

        private void handleMessageCreate(JsonObject data) {
            if (isStale(myGeneration)) {
                return;
            }
            if (!channelId.equals(data.get("channel_id").getAsString())) {
                return;
            }
            if (data.has("webhook_id") && !data.get("webhook_id").isJsonNull()) {
                return; // 우리가 게임 -> 디스코드로 보낸 웹훅 메시지 자기 자신은 무시
            }
            JsonObject author = data.getAsJsonObject("author");
            if (author.has("bot") && !author.get("bot").isJsonNull() && author.get("bot").getAsBoolean()) {
                return;
            }

            InboundSettings current = settings;
            Optional<InboundMessage> mapped = mapper.map(data, current);
            mapped.ifPresent(message -> relay.broadcast(message, current.format()));
        }
    }
}
