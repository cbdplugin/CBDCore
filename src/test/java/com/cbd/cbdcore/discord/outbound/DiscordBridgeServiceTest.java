package com.cbd.cbdcore.discord.outbound;

import com.cbd.cbdcore.discord.DiscordSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class DiscordBridgeServiceTest {

    private static final URI WEBHOOK_URI = URI.create("https://discord.com/api/webhooks/1/token");
    private static final UUID PLAYER_ID = UUID.randomUUID();

    private FakeDiscordWebhookTransport transport;
    private Logger logger;
    private CapturingHandler capturingHandler;
    private DiscordBridgeService service;

    @BeforeEach
    void setUp() {
        transport = new FakeDiscordWebhookTransport();
        logger = Logger.getLogger("DiscordBridgeServiceTest-" + UUID.randomUUID());
        logger.setUseParentHandlers(false);
        capturingHandler = new CapturingHandler();
        logger.addHandler(capturingHandler);
        service = new DiscordBridgeService(transport, logger);
        service.updateSettings(new OutboundSettings(
                true, true, WEBHOOK_URI, "",
                "%player% 님이 접속했습니다.", "%player% 님이 퇴장했습니다.",
                DiscordSettings.DEFAULT_JOIN_COLOR, DiscordSettings.DEFAULT_LEAVE_COLOR));
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    @Test
    void messagesAreDeliveredInFifoOrder() {
        service.sendChat("player", "first", PLAYER_ID);
        service.sendChat("player", "second", PLAYER_ID);
        service.sendChat("player", "third", PLAYER_ID);

        awaitReceivedCount(3);

        List<String> contents = transport.receivedMessages().stream().map(DiscordMessage::content).toList();
        assertEquals(List.of("first", "second", "third"), contents);
    }

    @Test
    void joinSendsGreenEmbedAndLeaveSendsRedEmbed() {
        service.sendJoin("player", PLAYER_ID);
        service.sendLeave("player", PLAYER_ID);

        awaitReceivedCount(2);
        List<DiscordMessage> messages = transport.receivedMessages();

        DiscordEmbed joinEmbed = messages.get(0).embed();
        assertEquals(DiscordSettings.DEFAULT_JOIN_COLOR, joinEmbed.color());
        assertTrue(joinEmbed.authorName().contains("player"));

        DiscordEmbed leaveEmbed = messages.get(1).embed();
        assertEquals(DiscordSettings.DEFAULT_LEAVE_COLOR, leaveEmbed.color());
        assertTrue(leaveEmbed.authorName().contains("player"));
    }

    @Test
    void sendTestGoesThroughQueueAndReturnsResult() {
        DeliveryResult result = service.sendTest("ping").toCompletableFuture().join();

        assertTrue(result.success());
        // 테스트 메시지도 일반 메시지와 동일하게 실제 전송 경로(transport)를 거쳐야 한다.
        assertTrue(transport.receivedMessages().stream().anyMatch(m -> "ping".equals(m.content())));
    }

    @Test
    void sendTestReportsFailureResultFromTransport() {
        transport.enqueueResponse(() -> CompletableFuture.completedFuture(DeliveryResult.permanentFailure(400)));

        DeliveryResult result = service.sendTest("ping").toCompletableFuture().join();

        assertFalse(result.success());
    }

    @Test
    void retryableFailureIsRetriedUntilSuccess() {
        transport.enqueueResponse(() -> CompletableFuture.completedFuture(DeliveryResult.retryable(429, 50)));
        transport.enqueueResponse(() -> CompletableFuture.completedFuture(DeliveryResult.success(204)));

        service.sendChat("player", "retry-me", PLAYER_ID);

        awaitReceivedCount(2);
        assertEquals(List.of("retry-me", "retry-me"), transport.receivedMessages().stream().map(DiscordMessage::content).toList());
    }

    @Test
    void permanentFailureIsNotRetried() throws InterruptedException {
        transport.enqueueResponse(() -> CompletableFuture.completedFuture(DeliveryResult.permanentFailure(400)));

        service.sendChat("player", "will-fail", PLAYER_ID);

        awaitReceivedCount(1);
        // 재시도가 없다는 것을 확인하기 위해 잠시 더 기다린 뒤에도 호출 횟수가 늘지 않는지 확인한다.
        Thread.sleep(300);
        assertEquals(1, transport.receivedMessages().size());
    }

    @Test
    void queueDropsMessagesBeyondCapacityAndWarnsOnce() throws InterruptedException {
        CountDownLatch workerStarted = new CountDownLatch(1);
        CompletableFuture<DeliveryResult> blocker = new CompletableFuture<>();
        transport.enqueueResponse(() -> {
            workerStarted.countDown();
            return blocker;
        });

        assertTrue(service.sendChat("player", "blocking-message", PLAYER_ID));
        assertTrue(workerStarted.await(2, TimeUnit.SECONDS), "워커 스레드가 첫 메시지를 처리 중이어야 한다");

        for (int i = 0; i < 200; i++) {
            assertTrue(service.sendChat("player", "queued-" + i, PLAYER_ID), "큐 용량(200) 이내의 메시지는 성공해야 한다");
        }

        assertFalse(service.sendChat("player", "overflow-1", PLAYER_ID), "큐가 가득 차면 추가 메시지는 거부되어야 한다");
        assertFalse(service.sendChat("player", "overflow-2", PLAYER_ID), "큐가 계속 가득 찬 상태에서는 계속 거부되어야 한다");

        assertEquals(1, capturingHandler.records().stream()
                .filter(r -> r.getLevel() == Level.WARNING)
                .count(), "큐가 가득 찼다는 경고는 한 번만 기록되어야 한다");

        blocker.complete(DeliveryResult.success(204));
    }

    private void awaitReceivedCount(int expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (transport.receivedMessages().size() >= expected) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("대기 중 인터럽트됨");
            }
        }
        fail("기대한 전송 횟수(" + expected + ")에 도달하지 못했습니다. 실제: " + transport.receivedMessages().size());
    }

    private static final class FakeDiscordWebhookTransport implements DiscordWebhookTransport {
        private final List<DiscordMessage> receivedMessages = new CopyOnWriteArrayList<>();
        private final LinkedBlockingQueue<Supplier<CompletionStage<DeliveryResult>>> responses = new LinkedBlockingQueue<>();

        void enqueueResponse(Supplier<CompletionStage<DeliveryResult>> supplier) {
            responses.add(supplier);
        }

        List<DiscordMessage> receivedMessages() {
            return receivedMessages;
        }

        @Override
        public CompletionStage<DeliveryResult> send(URI webhookUri, DiscordMessage message) {
            receivedMessages.add(message);
            Supplier<CompletionStage<DeliveryResult>> supplier = responses.poll();
            if (supplier == null) {
                return CompletableFuture.completedFuture(DeliveryResult.success(204));
            }
            return supplier.get();
        }
    }

    private static final class CapturingHandler extends Handler {
        private final List<LogRecord> records = new CopyOnWriteArrayList<>();

        List<LogRecord> records() {
            return records;
        }

        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }
}
