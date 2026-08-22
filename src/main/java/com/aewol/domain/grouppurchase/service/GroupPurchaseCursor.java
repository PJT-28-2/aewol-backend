package com.aewol.domain.grouppurchase.service;

import com.aewol.common.exception.BusinessException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;

/**
 * GroupPurchaseMapper#findList의 keyset 페이지네이션 커서를 불투명(opaque) 토큰으로
 * 인코딩/디코딩한다. is_urgent_active/deadline/created_at/gp_id 정렬 키를 그대로 응답에
 * 노출하지 않는다 — 프론트는 이 문자열의 내부 구조를 몰라도 되고, 정렬 기준이 바뀌어도
 * 이 클래스만 바꾸면 된다.
 */
final class GroupPurchaseCursor {

    private static final String DELIMITER = "|";
    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final int isUrgentActive;
    private final LocalDateTime deadline;
    private final LocalDateTime createdAt;
    private final long gpId;

    private GroupPurchaseCursor(int isUrgentActive, LocalDateTime deadline, LocalDateTime createdAt, long gpId) {
        this.isUrgentActive = isUrgentActive;
        this.deadline = deadline;
        this.createdAt = createdAt;
        this.gpId = gpId;
    }

    /**
     * deadline/createdAt을 초 단위로 잘라 인코딩한다. keyset 페이지네이션은 이 값들을
     * DB의 deadline/created_at 컬럼과 완전 일치(=) 비교하는데, group_purchase의 두 컬럼은
     * 현재 DATETIME(초 단위, 마이크로초 없음)이라 자를 필요가 없지만, 나중에 컬럼이
     * DATETIME(6)으로 바뀌더라도(또는 JDBC 드라이버가 나노초를 다르게 반환하더라도) 이
     * 잘라내기가 그 어긋남을 흡수한다. gp_id가 항상 마지막 동점 처리 기준이라 초 단위로
     * 잘라도 정렬/커서의 유일성은 깨지지 않는다.
     */
    static GroupPurchaseCursor of(int isUrgentActive, LocalDateTime deadline, LocalDateTime createdAt, long gpId) {
        return new GroupPurchaseCursor(isUrgentActive, deadline.withNano(0), createdAt.withNano(0), gpId);
    }

    static GroupPurchaseCursor decode(String token) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            String[] parts = raw.split("\\" + DELIMITER, -1);
            if (parts.length != 4) {
                throw new IllegalArgumentException("커서 필드 개수가 올바르지 않습니다.");
            }
            return new GroupPurchaseCursor(Integer.parseInt(parts[0]), LocalDateTime.parse(parts[1], FORMAT),
                    LocalDateTime.parse(parts[2], FORMAT), Long.parseLong(parts[3]));
        } catch (RuntimeException e) {
            throw new BusinessException("잘못된 커서 값입니다.");
        }
    }

    String encode() {
        String raw = isUrgentActive + DELIMITER + deadline.format(FORMAT) + DELIMITER
                + createdAt.format(FORMAT) + DELIMITER + gpId;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    int isUrgentActive() {
        return isUrgentActive;
    }

    LocalDateTime deadline() {
        return deadline;
    }

    LocalDateTime createdAt() {
        return createdAt;
    }

    long gpId() {
        return gpId;
    }
}
