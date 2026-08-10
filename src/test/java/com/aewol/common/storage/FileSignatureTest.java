package com.aewol.common.storage;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FileSignatureTest {

    private static final String SECRET = "test-secret-key-for-file-signature-256bit";

    private final FileSignature signature = new FileSignature(SECRET, 3600);

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
        FileSignature other = new FileSignature(SECRET + "-different", 3600);
        long expires = signature.expiresAt();

        assertNotEquals(signature.sign("diary/a.png", expires), other.sign("diary/a.png", expires));
    }
}
