package com.aewol.domain.insight.dto;

import java.math.BigDecimal;
import lombok.Builder;
import lombok.Getter;

/** 인사이트 카드에서 카테고리별 비중을 보여주기 위한 한 조각(도넛 차트 슬라이스 하나에 대응). */
@Getter
@Builder
public class CategoryShare {

    private final String label;
    private final BigDecimal amount;
    /** 전체 대비 비중(%). 정수로 반올림해서 내려준다 — 소수점은 화면에서 의미가 없다. */
    private final int percentage;
}
