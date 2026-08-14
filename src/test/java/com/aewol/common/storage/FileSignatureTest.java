package com.aewol.common.storage;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FileSignatureTest {

    private static final String SECRET = "test-secret-key-for-file-signature-256bit";
    private static final long TTL = 3600;
    private static final long BUCKET = 600;

    private final FileSignature signature = new FileSignature(SECRET, TTL, BUCKET);

    @Test
    @DisplayName("직접 만든 서명은 통과한다")
    void should_acceptOwnSignature() {
        long expires = signature.expiresAt();

        assertTrue(signature.isValid("diary/a.png", expires, signature.sign("diary/a.png", expires)));
    }

    @Test
    @DisplayName("만료된 서명은 거절한다")
    void should_rejectExpiredSignature() {
        long expired = Instant.now().getEpochSecond() - 1;

        assertFalse(signature.isValid("diary/a.png", expired, signature.sign("diary/a.png", expired)));
    }

    @Test
    @DisplayName("다른 키로 발급한 서명으로 이 파일을 열 수 없다")
    void should_rejectSignatureIssuedForAnotherKey() {
        long expires = signature.expiresAt();
        String issuedForOther = signature.sign("diary/other.png", expires);

        assertFalse(signature.isValid("diary/a.png", expires, issuedForOther));
    }

    @Test
    @DisplayName("만료 시각을 뒤로 미루면 서명이 깨진다")
    void should_rejectWhenExpiryIsTampered() {
        long expires = signature.expiresAt();
        String valid = signature.sign("diary/a.png", expires);

        assertFalse(signature.isValid("diary/a.png", expires + 86400, valid));
    }

    @Test
    @DisplayName("서명이 없거나 엉뚱하면 거절한다")
    void should_rejectMissingOrGarbageSignature() {
        long expires = signature.expiresAt();

        assertFalse(signature.isValid("diary/a.png", expires, null));
        assertFalse(signature.isValid("diary/a.png", expires, ""));
        assertFalse(signature.isValid("diary/a.png", expires, "not-a-signature"));
    }

    @Test
    @DisplayName("JWT 시크릿이 그대로 서명 키로 쓰이지 않는다")
    void should_deriveKeyFromJwtSecret_notReuseIt() {
        // 같은 시크릿을 쓰더라도 용도가 다르면 서명이 달라야 한다.
        // 한쪽이 유출됐을 때 다른 쪽까지 위조되는 것을 막기 위함이다.
        FileSignature other = new FileSignature(SECRET + "-different", TTL, BUCKET);
        long expires = signature.expiresAt();

        assertNotEquals(signature.sign("diary/a.png", expires), other.sign("diary/a.png", expires));
    }

    @Test
    @DisplayName("같은 구간 안에서 발급하면 만료 시각과 서명이 동일하다")
    void should_returnSameSignature_when_issuedWithinSameBucket() {
        // URL이 매번 바뀌면 브라우저 캐시가 URL을 키로 쓰기 때문에 항상 다시 내려받는다.
        long first = signature.expiresAt();
        long second = signature.expiresAt();

        assertEquals(first, second);
        assertEquals(signature.sign("diary/a.png", first), signature.sign("diary/a.png", second));
    }

    @Test
    @DisplayName("만료 시각은 구간 경계에 정렬된다")
    void should_alignExpiryToBucketBoundary() {
        assertEquals(0, signature.expiresAt() % BUCKET);
    }

    @Test
    @DisplayName("구간으로 끊어도 남은 유효시간이 TTL보다 짧아지지 않는다")
    void should_keepRemainingTtlAtLeast_when_bucketed() {
        long remaining = signature.expiresAt() - Instant.now().getEpochSecond();

        assertTrue(remaining >= TTL, "남은 유효시간이 TTL보다 짧다: " + remaining);
        assertTrue(remaining < TTL + BUCKET, "유효시간이 구간 상한을 넘었다: " + remaining);
    }

    @Test
    @DisplayName("구간을 0으로 두면 기존처럼 호출 시각 기준으로 만료된다")
    void should_notBucket_when_bucketSecondsIsZero() {
        FileSignature unbucketed = new FileSignature(SECRET, TTL, 0);

        long remaining = unbucketed.expiresAt() - Instant.now().getEpochSecond();

        assertEquals(TTL, remaining);
    }

    @Test
    @DisplayName("구간으로 끊어도 만료된 서명은 여전히 거절한다")
    void should_stillRejectExpired_when_bucketed() {
        long expired = Instant.now().getEpochSecond() - 1;

        assertFalse(signature.isValid("diary/a.png", expired, signature.sign("diary/a.png", expired)));
    }
}
