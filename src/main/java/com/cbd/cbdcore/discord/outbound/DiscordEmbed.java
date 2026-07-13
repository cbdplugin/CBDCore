package com.cbd.cbdcore.discord.outbound;

/**
 * 디스코드 웹훅 메시지에 첨부하는 간단한 임베드 한 건.
 * 접속/퇴장 알림처럼 "아이콘 + 굵은 글씨 한 줄"만 필요한 경우를 위한 최소 형태로,
 * title/description 대신 author 필드(icon_url 포함)만 사용한다.
 *
 * @param authorName    임베드에 표시할 굵은 글씨 한 줄 (예: "Steve 님이 접속했습니다.")
 * @param authorIconUrl authorName 왼쪽에 표시할 작은 아이콘 URL. null이면 아이콘 없이 텍스트만 표시.
 * @param color         임베드 좌측 세로 줄 색상 (0xRRGGBB 형식의 24비트 정수)
 */
public record DiscordEmbed(String authorName, String authorIconUrl, int color) {
}
