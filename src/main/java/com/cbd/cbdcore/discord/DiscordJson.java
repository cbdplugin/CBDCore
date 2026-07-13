package com.cbd.cbdcore.discord;

/**
 * 외부 JSON 직렬화 라이브러리 없이 문자열을 JSON에 안전하게 넣기 위한 최소 유틸.
 * (JSON 파싱은 Gson을 쓰지만, 전송용 payload는 직접 조립하므로 이스케이프만 공유한다.)
 */
public final class DiscordJson {

    private DiscordJson() {
    }

    public static String escapeJson(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
