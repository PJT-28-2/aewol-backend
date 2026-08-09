package com.aewol.common.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PhoneNumberUtilTest {

    @Test
    void removesNonNumericCharacters() {
        assertEquals("01012345678", PhoneNumberUtil.normalize("010-1234-5678"));
        assertEquals("01012345678", PhoneNumberUtil.normalize("010 1234 5678"));
        assertEquals("01012345678", PhoneNumberUtil.normalize("01012345678"));
        assertEquals("01012345678", PhoneNumberUtil.normalize("tel:(010)1234.5678"));
    }

    @Test
    void preservesNull() {
        assertNull(PhoneNumberUtil.normalize(null));
    }
}
