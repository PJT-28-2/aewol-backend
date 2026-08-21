package com.aewol.common.util;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.StandardEnvironment;
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
        try (AnnotationConfigApplicationContext context = isolatedContext()) {
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
    void loadsKeysFromSpringProperties_whenApplicationLocalYmlDefinesThem() {
        try (AnnotationConfigApplicationContext context = isolatedContext()) {
            TestPropertySourceUtils.addInlinedPropertiesToEnvironment(
                    context,
                    "security.account.encryption-key=" + KEY,
                    "security.account.hash-key=" + KEY);
            context.register(AccountNumberCrypto.class);
            context.refresh();

            AccountNumberCrypto crypto = context.getBean(AccountNumberCrypto.class);
            String encrypted = crypto.encrypt("110123456789");
            assertEquals("110123456789", crypto.decrypt(encrypted));
        }
    }

    @Test
    void namesActualEnvironmentVariable_whenSpringPropertiesAndEnvAreMissing() {
        try (AnnotationConfigApplicationContext context = isolatedContext()) {
            context.register(AccountNumberCrypto.class);

            BeanCreationException exception = assertThrows(BeanCreationException.class, context::refresh);
            Throwable rootCause = rootCauseOf(exception);

            assertTrue(rootCause.getMessage().contains("ACCOUNT_ENCRYPTION_KEY"), rootCause.getMessage());
            assertFalse(rootCause.getMessage().contains("환경변수 security.account.encryption-key"),
                    rootCause.getMessage());
        }
    }

    /**
     * 호스트의 환경변수/시스템 프로퍼티를 걷어낸 컨텍스트.
     *
     * SETUP.md는 ACCOUNT_ENCRYPTION_KEY를 셸에 export하라고 안내한다. 그대로 따른
     * 개발자의 머신에서는 systemEnvironment 프로퍼티 소스로 키가 그대로 새어 들어와서
     * "설정이 없을 때"를 검증하는 테스트가 오히려 실패한다. 안내대로 설정한 사람만
     * 빨간불을 보는 셈이라, 이 테스트에서는 두 소스를 명시적으로 제거한다.
     */
    private static AnnotationConfigApplicationContext isolatedContext() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);

        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.setEnvironment(environment);
        return context;
    }

    private static Throwable rootCauseOf(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
