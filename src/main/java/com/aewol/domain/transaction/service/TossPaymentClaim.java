package com.aewol.domain.transaction.service;

import com.aewol.common.exception.BusinessException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.TimeUnit;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Toss 결제 승인 요청의 멱등성을 보장하는 Redis 클레임.
 *
 * <p>{@code CodefClient.requestAccountTransferAuth}(외부 실결제 호출을 보호하는 락)와 동일한
 * "setIfAbsent + TTL, 성공 시 미해제" 패턴을 따른다. {@code Gov24SyncLock}처럼 {@code finally}에서
 * 해제하면 동시 요청만 막는 뮤텍스가 되어버려, 브라우저 새로고침/뒤로가기로 인한 순차 재시도(먼저
 * 보낸 요청이 끝난 뒤 같은 orderId로 다시 보내는 경우)를 전혀 막지 못한다. 이 클레임은 성공 시
 * 해제하지 않고 TTL(10분) 만료로만 소멸시켜 순차 재시도까지 차단한다.
 *
 * <p>{@link #release}는 {@code confirmPayment} 호출 이전에 실패했거나, 확정적으로 거부되었거나,
 * 설정 오류(401/403)로 판명된 경로에서만 호출한다 — Toss가 승인을 보유했을 가능성이 조금이라도
 * 있으면 클레임을 유지해야 한다.
 */
@Component
public class TossPaymentClaim {

    private static final String CLAIM_PREFIX = "claim:toss:";
    private static final long CLAIM_TTL_MINUTES = 10;

    private final RedisTemplate<String, String> redisTemplate;

    public TossPaymentClaim(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * memberId+orderId 조합에 대한 멱등성 클레임을 확보한다.
     *
     * @throws BusinessException {@link HttpStatus#CONFLICT} — 이미 처리 중이거나 TTL 내에
     *         처리된 동일 요청이 있는 경우
     * @throws BusinessException {@link HttpStatus#SERVICE_UNAVAILABLE} — Redis 장애로 멱등성을
     *         보장할 수 없는 경우(fail-closed, Toss를 호출하지 않는다)
     */
    public void acquire(String memberId, String orderId) {
        String key = claimKey(memberId, orderId);
        Boolean acquired;
        try {
            acquired = redisTemplate.opsForValue()
                    .setIfAbsent(key, "1", CLAIM_TTL_MINUTES, TimeUnit.MINUTES);
        } catch (RuntimeException e) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                    "결제 중복 확인을 할 수 없어요. 잠시 후 다시 시도해주세요");
        }
        if (!Boolean.TRUE.equals(acquired)) {
            throw new BusinessException(HttpStatus.CONFLICT,
                    "이미 처리 중이거나 최근에 처리된 결제 요청입니다. 새 주문번호로 다시 시도해 주세요.");
        }
    }

    /**
     * 클레임을 명시적으로 해제한다. {@code confirmPayment} 호출 이전 실패, 확정적 거부, 설정 오류
     * (401/403) 경로에서만 호출한다. 성공 경로에서는 절대 호출하지 않는다 — TTL 만료가 유일한
     * 해제 수단이다.
     */
    public void release(String memberId, String orderId) {
        redisTemplate.delete(claimKey(memberId, orderId));
    }

    private String claimKey(String memberId, String orderId) {
        return CLAIM_PREFIX + hashForClaimKey(memberId, orderId);
    }

    /**
     * Redis 키에 orderId 원문을 그대로 쓰면 검증되지 않은 클라이언트 입력이 키 길이를 좌우하고
     * {@code :} 구분자와 충돌할 수 있다({@code CodefClient.hashForLockKey}와 동일한 이유,
     * CodefClient.java:211-226). SHA-256으로 해시해 같은 조합이면 항상 같은 값이 나오게 하면서
     * 길이를 고정한다.
     */
    private static String hashForClaimKey(String memberId, String orderId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((memberId + ":" + orderId).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 미지원 환경", e);
        }
    }
}
