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

    /**
     * "불투명(opaque)"의 의미를 정확히 하기 위한 테스트다 — 이 커서는 암호화가 아니라 Base64
     * 인코딩일 뿐이라 디코딩해서 값을 직접 조작하는 게 얼마든지 가능하다(변조 방지 목적이
     * 아니다). 목적은 딱 하나, 정렬 키(deadline/created_at/gp_id)가 URL/응답 바디에 평문
     * 숫자로 그대로 찍혀서 API 소비자가 무심코 그 값에 의미를 부여하거나 직접 조립하게
     * 되는 걸 막는 것 — Base64로 한 번 감싸는 것만으로 이 목적은 충분히 달성된다. 그래서
     * 이 테스트도 "위조 방지"가 아니라 "평문 노출 여부"만 확인한다.
     */
    @Test
    @DisplayName("정렬 키를 평문으로 노출하지 않는다 — 위조 방지가 아니라 API 계약에 정렬 키를 드러내지 않기 위한 인코딩일 뿐이다")
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
