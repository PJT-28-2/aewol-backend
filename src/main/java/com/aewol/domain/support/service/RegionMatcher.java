package com.aewol.domain.support.service;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 지원사업의 지역 조건과 회원 주소를 맞춰본다.
 *
 * <p>두 값의 표기가 서로 다르다는 것이 이 클래스가 있는 이유다. 지원사업 쪽은 정부24
 * 기관명이라 정식 표기({@code 경기도 수원시})이고, 회원 주소는 우편번호 서비스가 주는
 * 축약 표기({@code 경기 수원시 팔달구 …})다.
 *
 * <p>예전에는 공백을 지운 문자열끼리 접두 비교를 했는데, 문자열 끝의 {@code 시}/{@code 도}만
 * 떼는 방식이라 어절이 둘 이상이면 어긋났다. {@code 경기도수원시}는 끝의 {@code 시}만
 * 떨어져 {@code 경기도수원}이 되는데 회원 쪽은 {@code 경기수원시…}라 접두가 맞지 않았다.
 * 그래서 문자열이 아니라 (시도, 시군구) 두 조각으로 끊어 비교한다.
 */
final class RegionMatcher {

    /**
     * 시도 표기를 하나로 모으는 표.
     *
     * <p>17개로 고정된 목록이라 표로 두는 편이 규칙을 추측하는 것보다 정확하다.
     * 여기 없는 값은 알 수 없는 지역으로 보고 매칭시키지 않는다. 통과시키면 실재하지
     * 않는 지역의 사업이 모든 회원에게 노출된다.
     */
    private static final Map<String, String> SIDO_ALIASES = new LinkedHashMap<>();

    static {
        putSido("서울", "서울특별시", "서울시");
        putSido("부산", "부산광역시", "부산시");
        putSido("대구", "대구광역시", "대구시");
        putSido("인천", "인천광역시", "인천시");
        putSido("광주", "광주광역시", "광주시");
        putSido("대전", "대전광역시", "대전시");
        putSido("울산", "울산광역시", "울산시");
        putSido("세종", "세종특별자치시", "세종시");
        putSido("경기", "경기도");
        putSido("강원", "강원도", "강원특별자치도");
        putSido("충북", "충청북도");
        putSido("충남", "충청남도");
        putSido("전북", "전라북도", "전북특별자치도");
        putSido("전남", "전라남도");
        putSido("경북", "경상북도");
        putSido("경남", "경상남도");
        putSido("제주", "제주도", "제주특별자치도");
    }

    private RegionMatcher() {
    }

    private static void putSido(String canonical, String... aliases) {
        SIDO_ALIASES.put(canonical, canonical);
        for (String alias : aliases) {
            SIDO_ALIASES.put(alias, canonical);
        }
    }

    /** 시도와 시군구. 시군구는 광역 단위 사업이나 파악할 수 없는 주소에서 {@code null}이다. */
    record Region(String sido, String sigungu) {

        boolean hasSigungu() {
            return sigungu != null;
        }
    }

    /**
     * 회원 주소나 기관명에서 지역을 읽는다.
     *
     * <p>시도를 알아볼 수 없으면 {@code null}. 매칭 대상이 못 된다는 뜻이다.
     */
    static Region parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String[] tokens = value.trim().split("\\s+");
        String sido = SIDO_ALIASES.get(tokens[0]);
        if (sido == null) {
            return null;
        }
        return new Region(sido, tokens.length > 1 ? sigungu(tokens[1]) : null);
    }

    /**
     * 시군구로 인정할 수 있는 토큰만 추린다.
     *
     * <p>기관명 둘째 어절이 늘 시군구인 것은 아니다. {@code 서울특별시 동물복지과}처럼
     * 부서명이 오기도 한다. 이런 값은 시군구가 없는 것으로 보고 광역 사업으로 다룬다.
     * 별도의 예외 목록 없이 접미사만으로 걸러진다.
     */
    private static String sigungu(String token) {
        return token.matches(".+(시|군|구)$") ? token : null;
    }

    /**
     * 회원이 이 지역 조건을 충족하는지.
     *
     * <p>사업에 시군구가 없으면 그 시도 주민 전체가 대상이다. 시군구가 있으면 회원의
     * 시군구까지 같아야 한다. 회원 주소에서 시군구를 못 읽으면 충족으로 보지 않는다 —
     * 모르는 것을 맞다고 치면 다른 구 사업이 신청 가능으로 뜬다.
     */
    static boolean matches(String memberAddress, String expected, String operator) {
        Region member = parse(memberAddress);
        if (member == null) {
            return false;
        }
        return expectedRegions(expected, operator).stream()
                .filter(Objects::nonNull)
                .anyMatch(target -> covers(target, member));
    }

    private static boolean covers(Region target, Region member) {
        if (!target.sido().equals(member.sido())) {
            return false;
        }
        return !target.hasSigungu() || target.sigungu().equals(member.sigungu());
    }

    private static List<Region> expectedRegions(String expected, String operator) {
        if (expected == null || expected.isBlank()) {
            return List.of();
        }
        if ("IN".equals(Optional.ofNullable(operator).orElse("EQ").toUpperCase(Locale.ROOT))) {
            return Arrays.stream(expected.split(",")).map(RegionMatcher::parse).toList();
        }
        Region region = parse(expected);
        return region == null ? List.of() : List.of(region);
    }
}
