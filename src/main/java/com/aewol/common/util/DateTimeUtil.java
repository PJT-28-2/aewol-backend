package com.aewol.common.util;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** MyBatis map 결과의 DATETIME 컬럼 값을 응답 DTO에 실을 수 있는 형태로 맞춘다. */
public final class DateTimeUtil {

    private static final DateTimeFormatter ISO_LOCAL_DATE_TIME_FIXED =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private DateTimeUtil() {
    }

    /** 드라이버에 따라 LocalDateTime 또는 Timestamp로 오는 DATETIME 컬럼 값을 LocalDateTime으로 맞춘다. */
    public static LocalDateTime toLocalDateTime(Object value) {
        if (value == null) return null;
        if (value instanceof LocalDateTime) return (LocalDateTime) value;
        if (value instanceof Timestamp) return ((Timestamp) value).toLocalDateTime();
        return LocalDateTime.parse(String.valueOf(value));
    }

    /**
     * DATETIME 컬럼 값을 초 단위까지 항상 포함하는 ISO-8601 문자열로 변환한다.
     * LocalDateTime#toString()은 초·나노초가 0이면 그 부분을 생략해 값에 따라 자릿수가
     * 들쭉날쭉해지므로(예: "2026-08-07T10:00" vs "2026-08-07T10:00:05"), 고정 포맷터를 쓴다.
     */
    public static String toIsoString(Object value) {
        LocalDateTime localDateTime = toLocalDateTime(value);
        return localDateTime == null ? null : localDateTime.format(ISO_LOCAL_DATE_TIME_FIXED);
    }
}
