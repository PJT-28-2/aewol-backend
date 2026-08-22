package com.aewol.domain.grouppurchase.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.aewol.common.exception.BusinessException;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GroupPurchaseCursorTest {

    @Test
    @DisplayName("encode한 커서를 decode하면 원래 필드 값을 그대로 복원한다")
    void should_roundTripFields_when_encodeThenDecode() {
        LocalDateTime deadline = LocalDateTime.of(2026, 9, 1, 23, 59, 59);
        LocalDateTime createdAt = LocalDateTime.of(2026, 8, 20, 10, 30, 0);
        GroupPurchaseCursor original = GroupPurchaseCursor.of(1, deadline, createdAt, 42L);

        GroupPurchaseCursor decoded = GroupPurchaseCursor.decode(original.encode());

        assertEquals(1, decoded.isUrgentActive());
        assertEquals(deadline, decoded.deadline());
        assertEquals(createdAt, decoded.createdAt());
        assertEquals(42L, decoded.gpId());
    }

    @Test
    @DisplayName("정렬 키를 그대로 노출하지 않는 불투명 토큰이다 — 원본 필드 값이 문자열에 그대로 보이지 않는다")
    void should_notExposeRawFields_inEncodedToken() {
        GroupPurchaseCursor cursor = GroupPurchaseCursor.of(1,
                LocalDateTime.of(2026, 9, 1, 23, 59, 59), LocalDateTime.of(2026, 8, 20, 10, 30, 0), 999L);

        String encoded = cursor.encode();

        assertEquals(-1, encoded.indexOf("999"));
        assertEquals(-1, encoded.indexOf("2026"));
    }

    @Test
    @DisplayName("깨진 토큰을 decode하면 BusinessException을 던진다")
    void should_throwException_when_tokenIsMalformed() {
        assertThrows(BusinessException.class, () -> GroupPurchaseCursor.decode("not-a-valid-cursor"));
    }

    @Test
    @DisplayName("필드 개수가 다른 토큰을 decode하면 BusinessException을 던진다")
    void should_throwException_when_tokenHasWrongFieldCount() {
        String tampered = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("1|2026-09-01T23:59:59".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertThrows(BusinessException.class, () -> GroupPurchaseCursor.decode(tampered));
    }
}
