package com.aewol.external.sms;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SolapiProdConfigValidatorTest {

    @Test
    void rejectsBlankCredentialsWithParameterNames() {
        SolapiProdConfigValidator validator = new SolapiProdConfigValidator("", " ", "");

        IllegalStateException exception = assertThrows(IllegalStateException.class, validator::validate);

        assertTrue(exception.getMessage().contains("SOLAPI_API_KEY"));
        assertTrue(exception.getMessage().contains("SOLAPI_API_SECRET"));
        assertTrue(exception.getMessage().contains("SOLAPI_SENDER"));
    }

    @Test
    void acceptsCompleteCredentials() {
        assertDoesNotThrow(() ->
                new SolapiProdConfigValidator("key", "secret", "01000000000").validate());
    }
}
