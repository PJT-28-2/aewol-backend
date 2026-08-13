package com.aewol.common.util;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
    void blankKey_failsFastAtConstruction() {
        assertThrows(IllegalStateException.class, () -> new AccountNumberCrypto("", randomKey()));
        assertThrows(IllegalStateException.class, () -> new AccountNumberCrypto(randomKey(), ""));
    }

    private static String randomKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }
}
