package com.cbd.cbdcore.discord.outbound;

/**
 * 디스코드 웹훅 전송 시도 하나의 결과.
 *
 * @param success        2xx 응답을 받았는지 여부
 * @param statusCode     HTTP 상태 코드 (전송 자체가 실패한 경우 -1)
 * @param retryAfterMillis 재시도까지 대기해야 할 시간(ms).
 *                         재시도가 불가능/불필요한 실패(4xx 등)는 음수(-1)를 사용한다.
 */
public record DeliveryResult(boolean success, int statusCode, long retryAfterMillis) {

    public static DeliveryResult success(int statusCode) {
        return new DeliveryResult(true, statusCode, 0L);
    }

    /** 429/5xx 등 잠시 후 다시 시도해볼 가치가 있는 실패. */
    public static DeliveryResult retryable(int statusCode, long retryAfterMillis) {
        return new DeliveryResult(false, statusCode, Math.max(retryAfterMillis, 0L));
    }

    /** 400/401/404 등 재시도해도 성공할 수 없는 실패. */
    public static DeliveryResult permanentFailure(int statusCode) {
        return new DeliveryResult(false, statusCode, -1L);
    }

    public boolean isRetryable() {
        return !success && retryAfterMillis >= 0;
    }
}
