package com.cbd.cbdcore.discord;

import java.net.URI;
import java.util.concurrent.CompletionStage;

/**
 * 실제 디스코드 웹훅 HTTP 호출을 담당하는 계층. 테스트에서는 가짜 구현으로 교체할 수 있다.
 */
public interface DiscordWebhookTransport {

    CompletionStage<DeliveryResult> send(URI webhookUri, DiscordMessage message);
}
