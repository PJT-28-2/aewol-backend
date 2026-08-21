package com.aewol.domain.support.service;

import com.aewol.common.exception.BusinessException;
import com.aewol.domain.member.mapper.MemberMapper;
import com.aewol.domain.pet.mapper.PetMapper;
import com.aewol.domain.support.dto.*;
import com.aewol.domain.support.mapper.SupportMapper;
import java.sql.Date;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SupportServiceImpl implements SupportService {

    private final SupportMapper supportMapper;
    private final MemberMapper memberMapper;
    private final PetMapper petMapper;

    @Override
    public SupportProgramsResponse getMatchedPrograms(String memberId, String petId) {
        String resolvedMemberId = requireMemberId(memberId);
        Map<String, Object> member = memberMapper.findById(resolvedMemberId);
        if (member == null) throw BusinessException.notFound("회원 정보를 찾을 수 없습니다.");
        Map<String, Object> pet = resolvePet(resolvedMemberId, petId);
        if (pet == null) {
            return SupportProgramsResponse.builder()
                    .programs(Collections.emptyList())
                    .build();
        }

        String resolvedPetId = text(pet, "pet_id", "petId");
        List<SupportProgramResponse> programs = supportMapper.findActivePrograms().stream()
                .map(program -> toResponse(resolvedMemberId, member, pet, program))
                .collect(Collectors.toList());
        return SupportProgramsResponse.builder()
                .petId(resolvedPetId)
                .petName(text(pet, "name"))
                .programs(programs)
                .build();
    }

    @Override
    @Transactional
    public void markApplyPageOpened(String memberId, String programId, String petId) {
        memberId = requireMemberId(memberId);
        Map<String, Object> member = memberMapper.findById(memberId);
        if (member == null) throw BusinessException.notFound("회원 정보를 찾을 수 없습니다.");
        Map<String, Object> pet = resolvePet(memberId, petId);
        if (pet == null) throw BusinessException.notFound("반려동물을 찾을 수 없습니다.");
        Map<String, Object> program = supportMapper.findProgramById(programId);
        if (program == null) throw BusinessException.notFound("지원사업을 찾을 수 없습니다.");
        SupportProgramResponse evaluated = toResponse(memberId, member, pet, program);
        if (!evaluated.isEligible()) {
            throw BusinessException.forbidden("현재 조건으로 신청할 수 없는 지원사업입니다.");
        }

        Map<String, Object> interest = new HashMap<>();
        interest.put("memberId", memberId);
        interest.put("programId", programId);
        interest.put("petId", text(pet, "pet_id", "petId"));
        interest.put("status", "APPLY_PAGE_OPENED");
        supportMapper.upsertInterest(interest);
    }

    private SupportProgramResponse toResponse(String memberId, Map<String, Object> member,
                                               Map<String, Object> pet, Map<String, Object> program) {
        String programId = text(program, "id");
        List<Map<String, Object>> conditionRows = supportMapper.findConditions(programId);
        List<SupportConditionResponse> conditions = conditionRows.stream()
                .map(condition -> SupportConditionResponse.builder()
                        .met(isConditionMet(condition, member, pet))
                        .title(text(condition, "title"))
                        .description(text(condition, "description"))
                        .build())
                .collect(Collectors.toList());

        boolean speciesMatches = speciesMatches(text(program, "targetSpecies", "target_species"),
                text(pet, "species"));
        boolean requiredConditionsMet = true;
        for (int index = 0; index < conditionRows.size(); index++) {
            if (bool(conditionRows.get(index), "isRequired", "is_required") && !conditions.get(index).isMet()) {
                requiredConditionsMet = false;
                break;
            }
        }
        Map<String, Object> interest = supportMapper.findInterest(memberId, programId);
        boolean applied = interest != null
                && "APPLY_PAGE_OPENED".equals(text(interest, "status"));

        return SupportProgramResponse.builder()
                .id(programId)
                .title(text(program, "title"))
                .summary(text(program, "summary"))
                .agency(text(program, "agency"))
                .benefit(text(program, "benefit"))
                .period(resolvePeriod(program))
                .applyUrl(text(program, "applyUrl", "apply_url"))
                .eligible(speciesMatches && requiredConditionsMet)
                .applied(applied)
                .conditions(conditions)
                .build();
    }

    private boolean isConditionMet(Map<String, Object> condition, Map<String, Object> member,
                                   Map<String, Object> pet) {
        String type = Optional.ofNullable(text(condition, "conditionType", "condition_type"))
                .orElse("MANUAL").toUpperCase(Locale.ROOT);
        String operator = Optional.ofNullable(text(condition, "operator"))
                .orElse("EQ").toUpperCase(Locale.ROOT);
        String expected = Optional.ofNullable(text(condition, "conditionValue", "condition_value"))
                .orElse("").trim();

        switch (type) {
            case "REGION":
                // member에는 region 컬럼이 없어 주소를 쓴다. 사업 쪽은 정부24 기관명이라
                // 정식 표기고 회원 주소는 축약 표기여서, 문자열이 아니라
                // (시도, 시군구)로 끊어 맞춘다.
                return RegionMatcher.matches(text(member, "region", "address"), expected, operator);
            case "PET_SPECIES":
                return compareText(text(pet, "species"), expected, operator);
            case "PET_AGE_MONTHS":
                LocalDate birthDate = localDate(value(pet, "birth_date", "birthDate"));
                return birthDate != null && compareNumber(ChronoUnit.MONTHS.between(birthDate, LocalDate.now()), expected, operator);
            case "NEUTERED":
                return compareText(text(pet, "neutered"), expected, operator);
            case "PET_REGISTERED":
                boolean registered = text(pet, "reg_number", "regNumber") != null
                        && !text(pet, "reg_number", "regNumber").isBlank();
                return compareText(registered ? "Y" : "N", expected, operator);
            case "MANUAL":
                return !bool(condition, "isRequired", "is_required");
            default:
                return false;
        }
    }

    private Map<String, Object> resolvePet(String memberId, String petId) {
        if (petId != null && !petId.isBlank()) {
            Map<String, Object> pet = petMapper.findById(petId);
            assertOwnedPet(memberId, pet);
            return pet;
        }
        List<Map<String, Object>> pets = petMapper.findByMemberId(memberId);
        return pets.isEmpty() ? null : pets.get(0);
    }

    private void assertOwnedPet(String memberId, Map<String, Object> pet) {
        if (pet == null) throw BusinessException.notFound("반려동물을 찾을 수 없습니다.");
        if (!memberId.equals(text(pet, "member_id", "memberId"))) {
            throw BusinessException.forbidden("내 반려동물의 지원사업만 조회할 수 있습니다.");
        }
    }

    private boolean speciesMatches(String targetSpecies, String petSpecies) {
        return targetSpecies == null || "ALL".equalsIgnoreCase(targetSpecies)
                || targetSpecies.equalsIgnoreCase(petSpecies);
    }

    // 지역 비교는 RegionMatcher로 옮겼다. 문자열 접두 비교로는 표기가 다른 같은 지역을
    // 걸러내고(경기 수원시 vs 경기도 수원시), 시군구를 붙이는 순간 어긋났다.

    private boolean compareText(String actual, String expected, String operator) {
        if (actual == null) return false;
        if ("IN".equals(operator)) {
            return Arrays.stream(expected.split(","))
                    .map(String::trim)
                    .anyMatch(value -> value.equalsIgnoreCase(actual));
        }
        return actual.equalsIgnoreCase(expected);
    }

    private boolean compareNumber(long actual, String expected, String operator) {
        try {
            if ("BETWEEN".equals(operator)) {
                String[] range = expected.split(",");
                return range.length == 2 && actual >= Long.parseLong(range[0].trim())
                        && actual <= Long.parseLong(range[1].trim());
            }
            long value = Long.parseLong(expected);
            if ("LTE".equals(operator)) return actual <= value;
            if ("GTE".equals(operator)) return actual >= value;
            return actual == value;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private String resolvePeriod(Map<String, Object> program) {
        String period = text(program, "period", "period_text");
        if (period != null && !period.isBlank()) return period;
        LocalDate start = localDate(value(program, "startDate", "start_date"));
        LocalDate end = localDate(value(program, "endDate", "end_date"));
        if (start != null && end != null) return start + " ~ " + end;
        if (end != null) return end + "까지";
        return "상시 신청";
    }

    private String requireMemberId(String memberId) {
        if (memberId == null || memberId.isBlank()) throw BusinessException.unauthorized("로그인이 필요합니다.");
        return memberId;
    }

    private static Object value(Map<String, Object> map, String... keys) {
        for (String key : keys) if (map.containsKey(key)) return map.get(key);
        return null;
    }

    private static String text(Map<String, Object> map, String... keys) {
        Object value = value(map, keys);
        return value == null ? null : String.valueOf(value);
    }

    private static boolean bool(Map<String, Object> map, String... keys) {
        Object value = value(map, keys);
        return value instanceof Boolean ? (Boolean) value
                : value instanceof Number ? ((Number) value).intValue() == 1
                : "Y".equalsIgnoreCase(String.valueOf(value)) || "true".equalsIgnoreCase(String.valueOf(value));
    }

    private static LocalDate localDate(Object value) {
        if (value instanceof LocalDate) return (LocalDate) value;
        if (value instanceof Date) return ((Date) value).toLocalDate();
        return value == null ? null : LocalDate.parse(String.valueOf(value));
    }
}
