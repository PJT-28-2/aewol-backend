package com.aewol.common.util;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountNumberCryptoTest {

    private final AccountNumberCrypto crypto = new AccountNumberCrypto(randomKey(), randomKey());

    @Test
    void encryptThenDecrypt_returnsOriginalPlainText() {
        String plain = "110123456789";
        String encrypted = crypto.encrypt(plain);

        assertNotEquals(plain, encrypted);
        assertEquals(plain, crypto.decrypt(encrypted));
    }

    @Test
    void encryptingSameValueTwice_producesDifferentCiphertext() {
        // AES-GCM은 호출마다 랜덤 IV를 쓰기 때문에 같은 평문도 암호문이 매번 달라야 한다
        // — 그래서 중복 체크는 암호문이 아니라 별도 hash()로 해야 한다(2026-08-13).
        String plain = "110123456789";
        assertNotEquals(crypto.encrypt(plain), crypto.encrypt(plain));
    }

    @Test
    void hash_isDeterministic_forDuplicateCheck() {
        String plain = "110123456789";
        assertEquals(crypto.hash(plain), crypto.hash(plain));
        assertNotEquals(crypto.hash(plain), crypto.hash("110123456780"));
    }

    @Test
    void hash_differsFromPlainSha256_becauseItUsesASecretKey() {
        // 예전 구현(AccountServiceImpl.sha256)은 키 없는 SHA-256이라, 계좌번호처럼
        // 경우의 수가 제한된(숫자 10~16자리) 값은 레인보우테이블로 역산될 수 있었다.
        // HMAC은 다른 키로 만들면 같은 평문이라도 다른 해시가 나와야 한다.
        AccountNumberCrypto otherKeyCrypto = new AccountNumberCrypto(randomKey(), randomKey());
        String plain = "110123456789";
        assertNotEquals(crypto.hash(plain), otherKeyCrypto.hash(plain));
    }

    @Test
    void mask_keepsLastFourDigitsOnly() {
        assertEquals("******7890", AccountNumberCrypto.mask("1234567890"));
        assertEquals("1234", AccountNumberCrypto.mask("1234"));
        assertNull(AccountNumberCrypto.mask(null));
    }

    @Test
    void decryptingTamperedCiphertext_throws() {
        String encrypted = crypto.encrypt("110123456789");
        String tampered = encrypted.substring(0, encrypted.length() - 4) + "abcd";
        assertThrows(IllegalStateException.class, () -> crypto.decrypt(tampered));
    }

    @Test
    void decryptingTooShortValue_throwsIllegalState_notArrayIndexOutOfBounds() {
        // PR #162 리뷰 반영: combined.length가 IV 길이(12바이트)보다 짧으면 예전엔
        // Arrays.copyOfRange에서 ArrayIndexOutOfBoundsException이 그대로 새어나갔다.
        // 손상되거나 잘린 값도 다른 케이스와 동일하게 IllegalStateException으로 통일돼야 한다.
        String tooShort = Base64.getEncoder().encodeToString(new byte[]{1, 2, 3});
        assertThrows(IllegalStateException.class, () -> crypto.decrypt(tooShort));
    }

    @Test
    void encryptNull_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () -> crypto.encrypt(null));
    }

    @Test
    void decryptNull_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () -> crypto.decrypt(null));
    }

    @Test
    void blankKey_failsFastAtConstruction() {
        assertThrows(IllegalStateException.class, () -> new AccountNumberCrypto("", randomKey()));
        assertThrows(IllegalStateException.class, () -> new AccountNumberCrypto(randomKey(), ""));
    }

    @Test
    void encryptionKeyLongerThan32Bytes_failsFastAtConstruction() {
        // PR #162 리뷰 반영: 예전엔 MIN_KEY_BYTES(32 이상)만 체크해서 33바이트 이상인
        // 키도 기동 시점 검증을 통과하고, 첫 encrypt() 호출 때 Cipher.init()에서야
        // 실패했다. AES-256은 정확히 32바이트여야 하므로 기동 시점에 바로 걸러야 한다.
        String oversizedKey = randomKey(40);
        assertThrows(IllegalStateException.class, () -> new AccountNumberCrypto(oversizedKey, randomKey()));
    }

    private static String randomKey() {
        return randomKey(32);
    }

    private static String randomKey(int byteLength) {
        byte[] key = new byte[byteLength];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    // 환경변수가 없으면 ${...} 문자열이 그대로 들어온다. base64 오류로만 알려주면
    // 값이 잘못된 줄 알고 키를 다시 만드는데, 실제로는 설정이 빠진 것이다.
    @Test
    void should_nameTheMissingEnvVariable_when_placeholderNotResolved() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new AccountNumberCrypto("${ACCOUNT_ENCRYPTION_KEY}", randomKey()));

        assertTrue(e.getMessage().contains("ACCOUNT_ENCRYPTION_KEY"), e.getMessage());
        assertTrue(e.getMessage().contains("application-local.yml"), e.getMessage());
    }

    @Test
    void should_stripDefaultValue_when_placeholderHasFallback() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new AccountNumberCrypto("${ACCOUNT_ENCRYPTION_KEY:}", randomKey()));

        assertTrue(e.getMessage().contains("ACCOUNT_ENCRYPTION_KEY"), e.getMessage());
        assertFalse(e.getMessage().contains("ACCOUNT_ENCRYPTION_KEY:"), e.getMessage());
    }

    // 진짜로 형식이 깨진 값은 기존 메시지를 그대로 써야 한다.
    @Test
    void should_keepBase64Message_when_valueIsNotPlaceholder() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new AccountNumberCrypto("not-base64!!", randomKey()));

        assertTrue(e.getMessage().contains("base64"), e.getMessage());
    }
}
