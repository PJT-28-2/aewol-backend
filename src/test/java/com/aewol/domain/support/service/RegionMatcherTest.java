package com.aewol.domain.support.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RegionMatcherTest {

    private boolean match(String memberAddress, String expected) {
        return RegionMatcher.matches(memberAddress, expected, "EQ");
    }

    // 이 버그를 잡으려고 만든 클래스다. 예전에는 시도만 봐서 통과했다.
    @Test
    @DisplayName("다른 구 사업은 신청 대상이 아니다")
    void should_reject_when_sigunguDiffers() {
        assertFalse(match("서울 관악구 국회단지5길 25", "서울특별시 동대문구"));
    }

    @Test
    @DisplayName("같은 구면 신청 대상이다")
    void should_accept_when_sigunguMatches() {
        assertTrue(match("서울 관악구 국회단지5길 25", "서울특별시 관악구"));
    }

    // 사업 쪽은 정부24 기관명이라 정식 표기, 회원 주소는 우편번호 서비스가 주는 축약
    // 표기다. 예전 문자열 접두 비교는 여기서 깨졌다("경기도수원" vs "경기수원시…").
    @Test
    @DisplayName("시도 표기가 축약형이어도 같은 지역으로 본다")
    void should_accept_when_sidoWrittenShort() {
        assertTrue(match("경기 수원시 팔달구 인계동 1", "경기도 수원시"));
        assertTrue(match("경기도 수원시 팔달구 인계동 1", "경기도 수원시"));
        assertTrue(match("전북 정읍시 시기동 1", "전북특별자치도 정읍시"));
        assertTrue(match("강원 영월군 영월읍 1", "강원특별자치도 영월군"));
    }

    @Test
    @DisplayName("광역 단위 사업은 그 시도 주민 전체가 대상이다")
    void should_accept_anySigungu_when_programIsSidoWide() {
        assertTrue(match("서울 관악구 국회단지5길 25", "서울특별시"));
        assertTrue(match("서울 동대문구 왕산로 1", "서울특별시"));
    }

    @Test
    @DisplayName("시도가 다르면 대상이 아니다")
    void should_reject_when_sidoDiffers() {
        assertFalse(match("서울 관악구 국회단지5길 25", "경기도"));
        assertFalse(match("경기 수원시 팔달구 1", "서울특별시 관악구"));
    }

    // 모르는 것을 맞다고 치면 다른 구 사업이 신청 가능으로 뜬다.
    @Test
    @DisplayName("주소를 알아볼 수 없으면 대상이 아니다")
    void should_reject_when_addressUnreadable() {
        assertFalse(match(null, "서울특별시 관악구"));
        assertFalse(match("", "서울특별시 관악구"));
        assertFalse(match("어딘가 이상한 주소", "서울특별시 관악구"));
    }

    // 구민 전용 사업인데 회원 주소에 구가 없으면 판단할 근거가 없다.
    @Test
    @DisplayName("회원 주소에 시군구가 없으면 구 단위 사업은 대상이 아니다")
    void should_reject_when_memberAddressHasNoSigungu() {
        assertFalse(match("서울특별시", "서울특별시 관악구"));
        assertTrue(match("서울특별시", "서울특별시"));
    }

    @Test
    @DisplayName("여러 지역을 허용하는 조건은 하나만 맞아도 된다")
    void should_accept_when_anyOfListedRegionsMatches() {
        assertTrue(RegionMatcher.matches("서울 관악구 1", "경기도,서울특별시 관악구", "IN"));
        assertFalse(RegionMatcher.matches("서울 강남구 1", "경기도,서울특별시 관악구", "IN"));
    }

    // 실재하지 않는 행정구역이 원본에 섞여 있다. 통과시키면 모든 회원에게 노출된다.
    @Test
    @DisplayName("표에 없는 시도는 아무와도 맞지 않는다")
    void should_reject_when_sidoIsUnknown() {
        assertNull(RegionMatcher.parse("전남광주통합특별시"));
        assertFalse(match("전남 해남군 1", "전남광주통합특별시 해남군"));
    }

    // 기관명 둘째 어절이 부서명일 때가 있다. 이것을 시군구로 쓰면 아무와도 맞지 않는다.
    @Test
    @DisplayName("부서명은 시군구로 보지 않는다")
    void should_treatAsSidoWide_when_secondTokenIsDepartment() {
        assertNull(RegionMatcher.parse("서울특별시 동물복지과").sigungu());
        assertTrue(match("서울 관악구 1", "서울특별시 동물복지과"));
    }
}
