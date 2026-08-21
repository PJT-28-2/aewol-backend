package com.aewol.common.util;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.context.support.TestPropertySourceUtils;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountNumberCryptoSpringConfigTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    @Test
    void loadsKeysFromEnvironmentVariables_whenSpringPropertiesAreMissing() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            TestPropertySourceUtils.addInlinedPropertiesToEnvironment(
                    context,
                    "ACCOUNT_ENCRYPTION_KEY=" + KEY,
                    "ACCOUNT_HASH_KEY=" + KEY);
            context.register(AccountNumberCrypto.class);
            context.refresh();

            AccountNumberCrypto crypto = context.getBean(AccountNumberCrypto.class);
            String encrypted = crypto.encrypt("110123456789");
            assertEquals("110123456789", crypto.decrypt(encrypted));
        }
    }

    @Test
    void namesActualEnvironmentVariable_whenSpringPropertiesAndEnvAreMissing() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(AccountNumberCrypto.class);

            BeanCreationException exception = assertThrows(BeanCreationException.class, context::refresh);
            Throwable rootCause = rootCauseOf(exception);

            assertTrue(rootCause.getMessage().contains("ACCOUNT_ENCRYPTION_KEY"), rootCause.getMessage());
            assertFalse(rootCause.getMessage().contains("환경변수 security.account.encryption-key"),
                    rootCause.getMessage());
        }
    }

    private static Throwable rootCauseOf(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
