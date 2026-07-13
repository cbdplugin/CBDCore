package com.cbd.cbdcore.discord.outbound;

/**
 * 디스코드 웹훅으로 보낼 메시지 한 건.
 *
 * @param content   본문 (2,000자 제한은 전송 직전 {@link JdkDiscordWebhookTransport}에서 검증). 임베드만 보낼 때는 빈 문자열 가능.
 * @param username  표시할 사용자 이름 override. null이면 웹훅 기본값 사용.
 * @param avatarUrl 표시할 아바타 URL override. null이면 웹훅 기본값 사용.
 * @param embed     함께 보낼 임베드. null이면 임베드 없이 본문만 전송.
 */
public record DiscordMessage(String content, String username, String avatarUrl, DiscordEmbed embed) {

    public DiscordMessage(String content, String username, String avatarUrl) {
        this(content, username, avatarUrl, null);
    }
}
