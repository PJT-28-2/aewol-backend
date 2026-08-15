package com.aewol.common.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class EmailMaskingUtilTest {

    @Test
    void masksLocalPartWithoutExposingMalformedInput() {
        assertEquals("a****@example.com", EmailMaskingUtil.mask("a@example.com"));
        assertEquals("a****@example.com", EmailMaskingUtil.mask("ab@example.com"));
        assertEquals("a****@example.com", EmailMaskingUtil.mask("abc@example.com"));
        assertEquals("hong****@naver.com", EmailMaskingUtil.mask("honggildong@naver.com"));
        assertEquals("****", EmailMaskingUtil.mask("not-an-email"));
        assertEquals("****", EmailMaskingUtil.mask(null));
    }
}
