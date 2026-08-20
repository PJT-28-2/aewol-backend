package com.aewol.common.util;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * 계좌번호 등 민감정보를 AES-256-GCM으로 암호화/복호화하고, 중복 체크용
 * HMAC-SHA256 해시를 만든다.
 *
 * ERD_수정안_계좌관리_고객센터.md 1.3에서 "결정 완료"로 명시했지만 실제 코드에는
 * 반영되지 않았던 부분(2026-08-13 코드리뷰 지적)을 구현한다.
 * - AES-256-GCM(호출마다 랜덤 IV): 같은 계좌번호를 암호화해도 매번 다른 암호문이
 *   나와서, 암호문끼리 직접 비교(중복 체크)는 할 수 없다.
 * - 그래서 중복 체크는 별도 HMAC-SHA256 해시로 한다. 예전에는 salt/키 없는
 *   sha256(accountNumber)를 썼는데, 계좌번호는 숫자 10~16자리라 경우의 수가
 *   제한적이라 레인보우테이블/무차별 대입으로 원문을 역산할 수 있었다. HMAC은
 *   비밀 키가 있어야 같은 해시를 만들 수 있어서 이 공격이 막힌다.
 *
 * 2026-08-14 PR #162 리뷰 반영: decrypt()가 너무 짧은 값을 만나면 의도치 않은
 * ArrayIndexOutOfBoundsException이 새던 문제, AES 키 길이가 32바이트를 초과해도
 * 시작 시점 검증을 통과하던 문제, encrypt/decrypt에 null이 들어오면 NPE가 그대로
 * 새던 문제를 고쳤다.
 */
@Component
public class AccountNumberCrypto {

    private static final String AES_TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH_BYTES = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final int MIN_KEY_BYTES = 32;
    private static final int AES_KEY_BYTES = 32;

    private final SecretKeySpec aesKey;
    private final SecretKeySpec hmacKey;
    private final SecureRandom secureRandom = new SecureRandom();

    public AccountNumberCrypto(
            @Value("${security.account.encryption-key}") String encryptionKeyBase64,
            @Value("${security.account.hash-key}") String hashKeyBase64) {
        byte[] aesKeyBytes = decodeKey(encryptionKeyBase64, "security.account.encryption-key");
        if (aesKeyBytes.length != AES_KEY_BYTES) {
            // MIN_KEY_BYTES(이상)만 체크하면 33바이트 이상인 키도 여기를 통과해버리고,
            // 실제로는 첫 encrypt() 호출 시점에야 Cipher.init()에서 실패한다. AES-256은
            // 정확히 32바이트여야 하므로 기동 시점에 바로 걸러낸다(PR #162 리뷰 반영).
            throw new IllegalStateException("security.account.encryption-key는 AES-256 기준으로 정확히 "
                    + AES_KEY_BYTES + "바이트(base64 인코딩 전)여야 해요. 현재 " + aesKeyBytes.length + "바이트예요.");
        }
        this.aesKey = new SecretKeySpec(aesKeyBytes, "AES");
        this.hmacKey = new SecretKeySpec(
                decodeKey(hashKeyBase64, "security.account.hash-key"), HMAC_ALGORITHM);
    }

    private byte[] decodeKey(String base64Value, String propertyName) {
        if (!StringUtils.hasText(base64Value)) {
            throw new IllegalStateException(propertyName + " 설정이 비어 있어요. "
                    + "`openssl rand -base64 32` 로 키를 생성해서 채워주세요.");
        }
        // 값이 아직 ${...} 모양이면 플레이스홀더가 치환되지 않은 것이다. 이 경우 base64
        // 디코딩은 '$'(0x24)에서 실패하는데, 그 메시지("올바른 base64 문자열이 아니에요")로는
        // 원인을 짚을 수 없다. 실제로는 값이 잘못된 게 아니라 환경변수가 없는 상태다.
        //
        // 2026-08-13에 이 설정이 추가돼서, 그 전에 만든 application-local.yml에는 항목
        // 자체가 없다. 예제 파일만 보고 있으면 눈치채기 어렵다.
        String trimmed = base64Value.trim();
        if (trimmed.startsWith("${") && trimmed.endsWith("}")) {
            String variable = trimmed.substring(2, trimmed.length() - 1).split(":", 2)[0];
            throw new IllegalStateException(propertyName + " 값이 치환되지 않았어요. "
                    + "환경변수 " + variable + " 를 설정하거나, application-local.yml에 값을 직접 넣어주세요. "
                    + "(`openssl rand -base64 32` 로 생성) "
                    + "application-local.yml.example 을 다시 비교해 보면 빠진 항목을 찾을 수 있어요.");
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(base64Value);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(propertyName + " 값이 올바른 base64 문자열이 아니에요.", e);
        }
        if (decoded.length < MIN_KEY_BYTES) {
            throw new IllegalStateException(propertyName + "는 최소 " + MIN_KEY_BYTES
                    + "바이트(base64 인코딩 전)여야 해요.");
        }
        return decoded;
    }

    /** AES-256-GCM으로 암호화한 뒤 base64(iv + ciphertext+tag) 문자열로 반환한다. */
    public String encrypt(String plainText) {
        if (plainText == null) {
            throw new IllegalArgumentException("plainText는 null일 수 없어요.");
        }
        try {
            byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(AES_TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            byte[] cipherText = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(cipherText, 0, combined, iv.length, cipherText.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("계좌번호 암호화에 실패했어요", e);
        }
    }

    /** encrypt()로 만든 값을 복호화해서 원문을 돌려준다. */
    public String decrypt(String encoded) {
        if (encoded == null) {
            throw new IllegalArgumentException("encoded는 null일 수 없어요.");
        }
        try {
            byte[] combined = Base64.getDecoder().decode(encoded);
            if (combined.length < GCM_IV_LENGTH_BYTES) {
                // combined 길이가 IV(12바이트)보다 짧으면 아래 Arrays.copyOfRange(cipherText
                // 슬라이싱)가 ArrayIndexOutOfBoundsException을 던지는데, 이 예외는
                // GeneralSecurityException/IllegalArgumentException이 아니라서 catch에
                // 안 잡히고 그대로 새어나갔다. 슬라이싱 전에 길이부터 확인해서 다른 손상
                // 케이스와 동일하게 IllegalStateException으로 통일한다(PR #162 리뷰 반영).
                throw new IllegalStateException("계좌번호 복호화에 실패했어요: 저장된 값의 길이가 올바르지 않아요.");
            }
            byte[] iv = Arrays.copyOfRange(combined, 0, GCM_IV_LENGTH_BYTES);
            byte[] cipherText = Arrays.copyOfRange(combined, GCM_IV_LENGTH_BYTES, combined.length);

            Cipher cipher = Cipher.getInstance(AES_TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, aesKey, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            byte[] plain = cipher.doFinal(cipherText);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            // 키가 바뀌었거나(예: 환경별 키 불일치) 저장된 값이 손상된 경우 — 호출부가
            // 500으로 처리하도록 런타임 예외로 던진다.
            throw new IllegalStateException("계좌번호 복호화에 실패했어요", e);
        }
    }

    /** 중복 체크 전용 HMAC-SHA256 해시(hex 64자). 원문 대신 이 값으로만 비교한다. */
    public String hash(String plainText) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(hmacKey);
            byte[] result = mac.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(result.length * 2);
            for (byte b : result) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("계좌번호 해시 생성에 실패했어요", e);
        }
    }

    /**
     * 뒤 4자리만 남기고 나머지는 *로 가린다. 프론트(stores/account.js
     * requestDepositAuth의 마스킹 로직)와 동일한 규칙 — 하나만 바꾸면 프론트가
     * 직접 계산하는 값(연동 중 화면)과 백엔드가 내려주는 값(연동 완료 후 화면)이
     * 서로 달라 보일 수 있으니 항상 같이 맞출 것.
     */
    public static String mask(String plainAccountNumber) {
        if (plainAccountNumber == null) {
            return null;
        }
        int length = plainAccountNumber.length();
        if (length <= 4) {
            return plainAccountNumber;
        }
        return "*".repeat(length - 4) + plainAccountNumber.substring(length - 4);
    }
}
