package com.aewol.domain.support.service;

import com.aewol.domain.support.mapper.Gov24SyncMapper;
import com.aewol.external.gov24.Gov24Client;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

@Slf4j
@Service
@RequiredArgsConstructor
public class Gov24SyncServiceImpl implements Gov24SyncService {

    /**
     * 반려동물 사업 선별 키워드. 서비스명 기준으로만 매칭해 오탐을 줄인다.
     * (지원내용/지원대상 본문까지 넓히면 "동물병원 인근 주민" 같은 무관한 건이 섞인다)
     */
    private static final List<String> KEYWORDS = List.of(
            "반려동물", "반려견", "반려묘", "유기동물", "유기견", "동물등록", "중성화", "펫");

    /** 정부24 등록/수정일시는 yyyyMMddHHmmss 또는 yyyy-MM-dd 두 형태로 온다. */
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private static final int SERVICE_NAME_MAX = 300;
    private static final int SUMMARY_MAX = 500;
    private static final int AGENCY_MAX = 200;
    private static final int REGION_MAX = 50;
    private static final int PERIOD_MAX = 500;
    private static final int APPLY_URL_MAX = 500;
    private static final int APPLICATION_METHOD_MAX = 500;

    /**
     * `전화문의`는 다른 기관 필드와 성격이 다르다. 여러 지자체 연락처를
     * `시명/번호||시명/번호` 형태로 한 필드에 이어붙여 내려주기 때문에
     * 기관명 상한(200)을 적용하면 전화번호가 반토막 난다(#128).
     * 컬럼이 TEXT(65535바이트)라 한글 3바이트 기준 한도 안쪽으로 잡는다.
     */
    private static final int CONTACT_PHONE_MAX = 20000;

    /** 정부24가 목록형 값을 이어붙일 때 쓰는 구분자. */
    private static final String LIST_DELIMITER = "||";

    private final Gov24Client gov24Client;
    private final Gov24SyncMapper gov24SyncMapper;
    private final Gov24SyncLock gov24SyncLock;
    private final TransactionOperations transactionOperations;

    @Override
    public int syncPetSupportPrograms() {
        if (!gov24Client.isConfigured()) {
            log.warn("정부24 service-key 미설정 — 동기화를 건너뜁니다.");
            return 0;
        }

        return gov24SyncLock.execute(this::syncConfiguredPrograms);
    }

    private int syncConfiguredPrograms() {
        List<Gov24ServiceSnapshot> snapshots = collectSnapshots();
        Integer curated = transactionOperations.execute(status -> persistSnapshots(snapshots));
        return Objects.requireNonNull(curated, "정부24 동기화 트랜잭션 결과가 없습니다.");
    }

    /** 외부 HTTP 호출은 DB 트랜잭션을 열기 전에 모두 완료한다. */
    private List<Gov24ServiceSnapshot> collectSnapshots() {
        Map<String, Map<String, Object>> unique = new LinkedHashMap<>();
        for (String keyword : KEYWORDS) {
            for (Map<String, Object> row : gov24Client.findServicesByName(keyword)) {
                String serviceId = text(row, "서비스ID");
                if (serviceId != null && !serviceId.isBlank()) {
                    unique.putIfAbsent(serviceId, row);
                }
            }
        }
        log.info("[Gov24] 반려동물 관련 서비스 {}건 수집", unique.size());

        List<Gov24ServiceSnapshot> snapshots = new ArrayList<>(unique.size());
        for (Map.Entry<String, Map<String, Object>> entry : unique.entrySet()) {
            String serviceId = entry.getKey();
            snapshots.add(new Gov24ServiceSnapshot(
                    serviceId,
                    entry.getValue(),
                    gov24Client.findServiceDetail(serviceId),
                    gov24Client.findSupportConditions(serviceId)));
        }
        return snapshots;
    }

    /** 선수집이 성공한 스냅샷만 하나의 DB 트랜잭션으로 저장한다. */
    private int persistSnapshots(List<Gov24ServiceSnapshot> snapshots) {
        // 기존 GOV24 사업을 일괄 비활성화한다. 이번 동기화에 포함된 건은
        //    아래 큐레이션에서 다시 is_active=1로 되살아나고, 사라진 건만 0으로 남는다.
        //    같은 트랜잭션이라 조회 측에 중간 상태가 노출되지 않는다.
        int deactivated = gov24SyncMapper.deactivateAllGov24Programs();

        int curated = 0;
        for (Gov24ServiceSnapshot snapshot : snapshots) {
            curated += persistOne(snapshot);
        }

        log.info("[Gov24] 큐레이션 {}건 (직전 활성 {}건 초기화 후 재활성)", curated, deactivated);
        return curated;
    }

    private int persistOne(Gov24ServiceSnapshot snapshot) {
        String serviceId = snapshot.serviceId();
        Map<String, Object> listRow = snapshot.listRow();
        // ---- 원본 적재: 목록 ----
        Map<String, Object> service = new HashMap<>();
        service.put("serviceId", serviceId);
        service.put("supportType", cut(text(listRow, "지원유형"), 100));
        service.put("serviceName", cut(text(listRow, "서비스명"), SERVICE_NAME_MAX));
        service.put("servicePurposeSummary", text(listRow, "서비스목적요약"));
        service.put("supportTarget", text(listRow, "지원대상"));
        service.put("selectionCriteria", text(listRow, "선정기준"));
        service.put("supportContent", text(listRow, "지원내용"));
        service.put("applicationMethod", text(listRow, "신청방법"));
        service.put("applicationDeadline", cut(text(listRow, "신청기한"), PERIOD_MAX));
        service.put("detailUrl", cut(text(listRow, "상세조회URL"), 1000));
        service.put("organizationCode", cut(text(listRow, "소관기관코드"), 30));
        service.put("organizationName", cut(text(listRow, "소관기관명"), AGENCY_MAX));
        service.put("departmentName", cut(text(listRow, "부서명"), AGENCY_MAX));
        service.put("viewCount", integer(listRow, "조회수"));
        service.put("organizationType", cut(text(listRow, "소관기관유형"), 100));
        service.put("userType", cut(text(listRow, "사용자구분"), 100));
        service.put("serviceCategory", cut(text(listRow, "서비스분야"), 100));
        service.put("receptionAgency", cut(text(listRow, "접수기관"), AGENCY_MAX));
        service.put("contactPhone", cutList(text(listRow, "전화문의"), CONTACT_PHONE_MAX));
        service.put("sourceRegisteredAt", dateTime(text(listRow, "등록일시")));
        service.put("sourceUpdatedAt", dateTime(text(listRow, "수정일시")));
        gov24SyncMapper.upsertService(service);

        // ---- 원본 적재: 상세 ----
        Map<String, Object> detailRow = snapshot.detailRow();
        if (detailRow != null) {
            Map<String, Object> detail = new HashMap<>();
            detail.put("serviceId", serviceId);
            detail.put("servicePurpose", text(detailRow, "서비스목적"));
            detail.put("requiredDocuments", text(detailRow, "구비서류"));
            detail.put("officialVerifiedDocuments", text(detailRow, "공무원확인구비서류"));
            detail.put("identityVerifiedDocuments", text(detailRow, "본인확인필요구비서류"));
            detail.put("receptionAgencyName", cut(text(detailRow, "접수기관명"), 300));
            detail.put("contactInformation", text(detailRow, "문의처"));
            detail.put("onlineApplicationUrl", cut(text(detailRow, "온라인신청사이트URL"), 1000));
            detail.put("administrativeRules", text(detailRow, "행정규칙"));
            detail.put("localRegulations", text(detailRow, "자치법규"));
            detail.put("laws", text(detailRow, "법령"));
            detail.put("sourceUpdatedAt", dateTime(text(detailRow, "수정일시")));
            gov24SyncMapper.upsertServiceDetail(detail);
        }

        // ---- 원본 적재: 지원조건 (JA 코드) ----
        Map<String, Object> conditionRow = snapshot.conditionRow();
        if (conditionRow != null) {
            for (Map.Entry<String, Object> e : conditionRow.entrySet()) {
                if (!e.getKey().startsWith("JA") || e.getValue() == null) continue;
                Map<String, Object> condition = new HashMap<>();
                condition.put("serviceId", serviceId);
                condition.put("conditionCode", cut(e.getKey(), 20));
                condition.put("conditionValue", cut(String.valueOf(e.getValue()), 255));
                gov24SyncMapper.upsertSupportCondition(condition);
            }
        }

        // ---- 큐레이션: local_support_program ----
        return curate(serviceId, listRow, detailRow);
    }

    private record Gov24ServiceSnapshot(
            String serviceId,
            Map<String, Object> listRow,
            Map<String, Object> detailRow,
            Map<String, Object> conditionRow) {
    }

    private int curate(String serviceId, Map<String, Object> listRow, Map<String, Object> detailRow) {
        String organizationName = text(listRow, "소관기관명");
        String region = extractRegion(organizationName);

        Map<String, Object> program = new HashMap<>();
        program.put("sourceServiceId", serviceId);
        program.put("region", cut(region, REGION_MAX));
        program.put("programName", cut(text(listRow, "서비스명"), SERVICE_NAME_MAX));
        program.put("description", text(listRow, "지원내용"));
        program.put("summary", cut(text(listRow, "서비스목적요약"), SUMMARY_MAX));
        program.put("agencyName", cut(organizationName, AGENCY_MAX));
        program.put("benefitSummary", cut(text(listRow, "지원유형"), SUMMARY_MAX));
        // 정부24는 대상 축종을 구분해 주지 않는다. 서비스명으로 추정하고 기본은 ALL.
        program.put("targetSpecies", guessTargetSpecies(text(listRow, "서비스명")));
        program.put("applyUrl", cut(resolveApplyUrl(listRow, detailRow), APPLY_URL_MAX));
        program.put("periodText", cut(text(listRow, "신청기한"), PERIOD_MAX));
        program.put("applicationMethod", cut(text(listRow, "신청방법"), APPLICATION_METHOD_MAX));
        program.put("sourceUpdatedAt", dateTime(text(listRow, "수정일시")));
        gov24SyncMapper.upsertCuratedProgram(program);

        Long programId = gov24SyncMapper.findProgramIdBySourceServiceId(serviceId);
        if (programId == null) {
            log.warn("[Gov24] 큐레이션 후 program_id를 찾지 못했습니다 - serviceId={}", serviceId);
            return 0;
        }

        // 자동 생성 조건만 초기화 (수동 큐레이션 MANUAL은 보존)
        gov24SyncMapper.deleteGeneratedConditions(programId);

        List<Map<String, Object>> conditions = buildConditions(programId, region);
        for (Map<String, Object> condition : conditions) {
            gov24SyncMapper.insertProgramCondition(condition);
        }
        return 1;
    }

    /**
     * 화면에 노출할 조건을 만든다.
     *
     * <p>정부24 지원조건(JA 코드)은 연령·소득·가구유형 체계라 중성화·동물등록 같은
     * 반려동물 조건이 없다. 자동 생성은 지역 조건까지만 하고, 나머지는 운영자가
     * MANUAL 조건으로 보강한다.
     */
    private List<Map<String, Object>> buildConditions(Long programId, String region) {
        List<Map<String, Object>> conditions = new ArrayList<>();
        if (region == null || region.isBlank()) {
            return conditions;
        }
        Map<String, Object> regionCondition = new HashMap<>();
        regionCondition.put("programId", programId);
        regionCondition.put("conditionType", "REGION");
        regionCondition.put("operator", "EQ");
        regionCondition.put("conditionValue", region);
        regionCondition.put("title", region + " 거주자");
        regionCondition.put("description", "회원 주소가 " + region + "이어야 합니다.");
        regionCondition.put("isRequired", 1);
        regionCondition.put("displayOrder", 1);
        conditions.add(regionCondition);
        return conditions;
    }

    /** "서울특별시 동대문구" -> "서울특별시". 광역 단위만 남긴다. */
    private String extractRegion(String organizationName) {
        if (organizationName == null || organizationName.isBlank()) return null;
        String first = organizationName.trim().split("\\s+")[0];
        // 중앙행정기관(교육부 등)은 지역 조건을 만들지 않는다
        if (!first.matches(".*(특별시|광역시|특별자치시|특별자치도|도)$")) return null;
        return first;
    }

    /** 서비스명에 축종이 드러나면 반영하고, 아니면 ALL. */
    private String guessTargetSpecies(String serviceName) {
        if (serviceName == null) return "ALL";
        boolean dog = serviceName.contains("반려견") || serviceName.contains("유기견");
        boolean cat = serviceName.contains("반려묘") || serviceName.contains("길고양이");
        if (dog && !cat) return "DOG";
        if (cat && !dog) return "CAT";
        return "ALL";
    }

    private String resolveApplyUrl(Map<String, Object> listRow, Map<String, Object> detailRow) {
        String online = detailRow == null ? null : text(detailRow, "온라인신청사이트URL");
        return (online != null && !online.isBlank()) ? online : text(listRow, "상세조회URL");
    }

    private static String text(Map<String, Object> map, String key) {
        if (map == null) return null;
        Object value = map.get(key);
        if (value == null) return null;
        String s = String.valueOf(value).trim();
        // 이 API는 값이 없을 때 문자열 "None"을 반환하는 필드가 있다
        return (s.isEmpty() || "None".equals(s) || "null".equals(s)) ? null : s;
    }

    /**
     * 코드포인트 경계에서 자른다. char 인덱스로 자르면 보조 평면 문자(이모지 등)의
     * 서로게이트 페어가 쪼개져 고아 서로게이트가 남고, 그 값은 UTF-8로 인코딩할 수
     * 없어 JDBC 단계에서 예외나 '?' 치환이 발생한다.
     */
    private static String cut(String value, int max) {
        if (value == null || value.length() <= max) return value;
        if (max <= 0) return "";
        // 마지막 자리가 하이 서로게이트면 짝이 되는 로우 서로게이트는 상한 밖에 있다.
        // 그대로 두면 고아 서로게이트가 되므로 한 칸 더 줄인다.
        int end = Character.isHighSurrogate(value.charAt(max - 1)) ? max - 1 : max;
        return value.substring(0, end);
    }

    /**
     * 구분자로 이어붙인 목록값을 자른다. 상한을 넘기면 마지막으로 온전한 항목까지만
     * 남긴다. 항목 중간에서 자르면 `시흥시/031-310-2` 처럼 반토막 난 전화번호가
     * 유효한 값인 척 저장되기 때문이다(#128).
     */
    private static String cutList(String value, int max) {
        if (value == null || value.length() <= max) return value;
        StringBuilder kept = new StringBuilder();
        for (String item : value.split(Pattern.quote(LIST_DELIMITER))) {
            int cost = kept.length() == 0 ? item.length() : LIST_DELIMITER.length() + item.length();
            if (kept.length() + cost > max) break;
            if (kept.length() > 0) kept.append(LIST_DELIMITER);
            kept.append(item);
        }
        // 첫 항목 하나가 이미 상한을 넘으면 남길 항목이 없다. 이때만 문자 단위로 자른다.
        return kept.length() == 0 ? cut(value, max) : kept.toString();
    }

    private static Integer integer(Map<String, Object> map, String key) {
        String s = text(map, key);
        if (s == null) return null;
        try {
            return Integer.valueOf(s.replaceAll("[^0-9-]", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static LocalDateTime dateTime(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        try {
            if (s.matches("\\d{14}")) return LocalDateTime.parse(s, TS);
            if (s.matches("\\d{4}-\\d{2}-\\d{2}")) return LocalDate.parse(s).atStartOfDay();
        } catch (Exception ignored) {
            // 형식이 다르면 날짜 없이 저장한다
        }
        return null;
    }
}
