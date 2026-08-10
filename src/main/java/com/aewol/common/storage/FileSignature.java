package com.aewol.common.storage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 파일 조회 URL에 붙일 서명을 만들고 검증한다.
 *
 * <p>{@code <img>} 태그는 Authorization 헤더를 실을 수 없어 JWT로 보호할 수 없다.
 * 대신 "키 + 만료시각"을 HMAC으로 서명해 URL에 담는다. 서명이 맞고 만료 전이면
 * 통과시킨다. S3 presigned URL과 같은 원리다.
 */
@Slf4j
@Component
public class FileSignature {

    private final byte[] secret;
    private final long ttlSeconds;

    public FileSignature(@Value("${jwt.secret}") String jwtSecret,
                         @Value("${file.signed-url-ttl-seconds:3600}") long ttlSeconds) {
        // 파일 서명 전용 키를 따로 두지 않고 JWT 시크릿에서 파생시킨다. 같은 값을 그대로
        // 쓰면 한쪽이 유출됐을 때 다른 쪽까지 위조할 수 있어 용도 문자열을 섞어 분리한다.
        this.secret = hmac(jwtSecret.getBytes(StandardCharsets.UTF_8), "aewol-file-signature");
        this.ttlSeconds = ttlSeconds;
    }

    public long expiresAt() {
        return Instant.now().getEpochSecond() + ttlSeconds;
    }

    public String sign(String key, long expiresAt) {
        return encode(hmac(secret, key + ":" + expiresAt));
    }

    /** 서명이 유효하고 아직 만료되지 않았는지 확인한다. */
    public boolean isValid(String key, long expiresAt, String signature) {
        if (Instant.now().getEpochSecond() > expiresAt) {
            return false;
        }
        // 문자열 비교 시간으로 서명을 유추할 수 없도록 상수 시간 비교를 쓴다.
        return MessageDigest.isEqual(
                encode(hmac(secret, key + ":" + expiresAt)).getBytes(StandardCharsets.UTF_8),
                signature == null ? new byte[0] : signature.getBytes(StandardCharsets.UTF_8));
    }

    private static String encode(byte[] raw) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    private static byte[] hmac(byte[] key, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("파일 서명을 만들지 못했습니다.", e);
        }
    }
}
