package com.cbd.cbdcore.discord.outbound;

import java.net.URI;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * 인게임 채팅/접속/퇴장을 디스코드 웹훅으로 전달하는 서비스 (게임 -&gt; 디스코드).
 *
 * <p>메시지는 <b>전송 시점의 웹훅 URI를 함께 스냅샷</b>해 제한된 크기의 FIFO 큐에 쌓이고,
 * 전용 워커 스레드 하나가 순서대로 하나씩 전송한다 (웹훅당 동시 전송 1개). 큐에 담을 때
 * URI를 고정하므로, 대기 중 설정이 바뀌어도 이미 큐에 든 메시지가 엉뚱한 새 웹훅으로
 * 가지 않는다. 429/5xx 응답은 지정된 대기 시간만큼 기다린 뒤 제한된 횟수 안에서 재시도하고,
 * 4xx 등 재시도해도 성공할 수 없는 실패는 즉시 포기한다.</p>
 *
 * <p>{@link #updateSettings(OutboundSettings)}는 메인 스레드(reload)에서만 호출되고,
 * 워커 스레드는 매 전송 시점에 {@code volatile} 필드를 통해 최신 설정을 읽으므로
 * 설정 리로드와 전송 로직 사이에 데이터 경쟁이 없다.</p>
 */
public final class DiscordBridgeService {

    private static final int QUEUE_CAPACITY = 200;
    /** 한 메시지당 최대 전송 시도 횟수 (최초 1회 + 재시도 2회 = 총 3회). */
    private static final int MAX_ATTEMPTS = 3;

    private final DiscordWebhookTransport transport;
    private final Logger logger;
    private final BlockingQueue<QueuedMessage> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicBoolean queueFullWarned = new AtomicBoolean(false);
    private final Thread worker;

    private volatile OutboundSettings settings = OutboundSettings.disabled();
    private volatile boolean acceptingNewMessages = true;

    /** 큐에 담긴 메시지 한 건과, 담기는 순간에 고정된 전송 대상 웹훅 URI. */
    private record QueuedMessage(URI webhookUri, DiscordMessage message) {
    }

    public DiscordBridgeService(DiscordWebhookTransport transport, Logger logger) {
        this.transport = transport;
        this.logger = logger;
        this.worker = new Thread(this::runWorkerLoop, "CBDCore-Discord-Webhook");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    public void updateSettings(OutboundSettings newSettings) {
        this.settings = newSettings;
    }

    public boolean sendChat(String playerName, String plainMessage, UUID playerId) {
        OutboundSettings current = settings;
        if (!current.canSendChat() || plainMessage == null || plainMessage.isBlank()) {
            return false;
        }
        return enqueue(current.webhookUri(),
                new DiscordMessage(plainMessage, playerName, resolveAvatarUrl(current, playerId)));
    }

    public boolean sendJoin(String playerName, UUID playerId) {
        return sendJoinLeave(playerName, playerId, OutboundSettings::joinFormat, OutboundSettings::joinColor);
    }

    public boolean sendLeave(String playerName, UUID playerId) {
        return sendJoinLeave(playerName, playerId, OutboundSettings::leaveFormat, OutboundSettings::leaveColor);
    }

    private boolean sendJoinLeave(
            String playerName,
            UUID playerId,
            java.util.function.Function<OutboundSettings, String> formatSelector,
            java.util.function.ToIntFunction<OutboundSettings> colorSelector
    ) {
        OutboundSettings current = settings;
        if (!current.canSendJoinLeave()) {
            return false;
        }
        String text = formatSelector.apply(current).replace("%player%", playerName);
        String avatarUrl = resolveAvatarUrl(current, playerId);
        DiscordEmbed embed = new DiscordEmbed(text, avatarUrl, colorSelector.applyAsInt(current));
        return enqueue(current.webhookUri(), new DiscordMessage("", null, null, embed));
    }

    /**
     * 관리자 명령어(/cbdcore discord test)에서 사용. 큐를 거치지 않고 즉시 전송하며,
     * 실제 전송 결과를 그대로 돌려주므로 호출자가 성공/실패를 판단할 수 있다.
     */
    public CompletionStage<DeliveryResult> sendTest(String content) {
        OutboundSettings current = settings;
        if (current.webhookUri() == null) {
            return CompletableFuture.completedFuture(DeliveryResult.permanentFailure(-1));
        }
        return transport.send(current.webhookUri(), new DiscordMessage(content, "CBDCore", null));
    }

    private boolean enqueue(URI webhookUri, DiscordMessage message) {
        if (!acceptingNewMessages) {
            return false;
        }
        boolean accepted = queue.offer(new QueuedMessage(webhookUri, message));
        if (accepted) {
            queueFullWarned.set(false);
        } else if (queueFullWarned.compareAndSet(false, true)) {
            logger.warning("디스코드 전송 대기열이 가득 차 메시지를 버렸습니다 (최대 " + QUEUE_CAPACITY + "개).");
        }
        return accepted;
    }

    private void runWorkerLoop() {
        while (acceptingNewMessages || !queue.isEmpty()) {
            QueuedMessage queued;
            try {
                queued = queue.poll(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (queued == null) {
                continue;
            }
            try {
                deliverWithRetry(queued);
            } catch (RuntimeException e) {
                // 전송 계층에서 예상치 못한 예외가 나더라도 워커 스레드가 죽어 큐가 영영
                // 멈추는 일이 없도록 한 건 실패로 삼고 다음 메시지로 넘어간다.
                logger.warning("디스코드 메시지 전송 중 예기치 못한 오류가 발생해 해당 메시지를 건너뜁니다: "
                        + e.getClass().getSimpleName());
            }
        }
    }

    private void deliverWithRetry(QueuedMessage queued) {
        URI webhookUri = queued.webhookUri();
        DiscordMessage message = queued.message();

        DeliveryResult result = joinUninterruptibly(transport.send(webhookUri, message));
        int attempts = 1;

        while (result.isRetryable() && attempts < MAX_ATTEMPTS) {
            try {
                Thread.sleep(result.retryAfterMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            result = joinUninterruptibly(transport.send(webhookUri, message));
            attempts++;
        }

        if (result.isRetryable()) {
            logger.warning("최대 전송 시도 횟수(" + MAX_ATTEMPTS + "회)를 초과하여 디스코드 메시지 전송을 포기합니다.");
        }
    }

    private static DeliveryResult joinUninterruptibly(CompletionStage<DeliveryResult> stage) {
        return stage.toCompletableFuture().join();
    }

    private static String resolveAvatarUrl(OutboundSettings settings, UUID playerId) {
        String template = settings.avatarUrlTemplate();
        if (template == null || template.isBlank() || playerId == null) {
            return null;
        }
        return template.replace("%uuid%", playerId.toString());
    }

    /**
     * 새 메시지 수신을 중단하고, 이미 쌓여있는 메시지를 워커가 마저 처리할 시간을 준 뒤 종료한다.
     */
    public void shutdown() {
        acceptingNewMessages = false;
        try {
            worker.join(TimeUnit.SECONDS.toMillis(5));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (worker.isAlive()) {
            worker.interrupt();
        }
    }
}
