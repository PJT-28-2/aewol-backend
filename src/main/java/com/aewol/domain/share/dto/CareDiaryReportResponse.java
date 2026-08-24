package com.aewol.domain.share.dto;

import lombok.Builder;
import lombok.Getter;

/** 신고 접수 결과. 접수번호로 고객센터 문의 내역에서 진행 상태를 볼 수 있다. */
@Getter
@Builder
public class CareDiaryReportResponse {

    private final String reportId;
    /** 연결된 고객센터 문의 접수번호. 문의 생성이 실패하면 null. */
    private final String inquiryNumber;
    /** 이 신고로 게시물 노출이 멈췄는지. 임계치 미만이거나 이미 내려가 있었다면 false. */
    private final boolean hidden;
}
