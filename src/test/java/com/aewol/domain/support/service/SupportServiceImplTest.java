package com.aewol.domain.support.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.aewol.common.exception.BusinessException;
import com.aewol.domain.member.mapper.MemberMapper;
import com.aewol.domain.pet.mapper.PetMapper;
import com.aewol.domain.support.mapper.SupportMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SupportServiceImplTest {

    @Mock SupportMapper supportMapper;
    @Mock MemberMapper memberMapper;
    @Mock PetMapper petMapper;

    @Test
    @DisplayName("회원 지역과 동물등록 조건을 모두 충족하면 신청 가능으로 반환한다")
    void should_markEligible_when_allRequiredConditionsAreMet() {
        SupportServiceImpl service = service();
        stubMemberAndPet("서울특별시", "TEST-REG-001");
        when(supportMapper.findActivePrograms()).thenReturn(List.of(program("program-1")));
        when(supportMapper.findConditions("program-1")).thenReturn(List.of(
                condition("REGION", "EQ", "서울시", true),
                condition("PET_REGISTERED", "EQ", "Y", true)));

        var result = service.getMatchedPrograms("member-1", "pet-1");

        assertTrue(result.getPrograms().get(0).isEligible());
        assertTrue(result.getPrograms().get(0).getConditions().get(0).isMet());
        assertEquals("보리", result.getPetName());
    }

    @Test
    @DisplayName("필수 지역 조건을 충족하지 못하면 신청 불가로 반환한다")
    void should_markIneligible_when_requiredRegionConditionIsNotMet() {
        SupportServiceImpl service = service();
        stubMemberAndPet("서울특별시", "TEST-REG-001");
        when(supportMapper.findActivePrograms()).thenReturn(List.of(program("program-1")));
        when(supportMapper.findConditions("program-1"))
                .thenReturn(List.of(condition("REGION", "EQ", "제주도", true)));

        var result = service.getMatchedPrograms("member-1", "pet-1");

        assertFalse(result.getPrograms().get(0).isEligible());
    }

    @Test
    @DisplayName("신청 가능한 정책의 신청 버튼을 누르면 신청 페이지 이동 상태를 저장한다")
    void should_saveInterest_when_programIsEligible() {
        SupportServiceImpl service = service();
        stubMemberAndPet("서울특별시", "TEST-REG-001");
        when(supportMapper.findProgramById("program-1")).thenReturn(program("program-1"));
        when(supportMapper.findConditions("program-1"))
                .thenReturn(List.of(condition("REGION", "EQ", "서울시", true)));

        service.markApplyPageOpened("member-1", "program-1", "pet-1");

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(supportMapper).upsertInterest(captor.capture());
        assertEquals("APPLY_PAGE_OPENED", captor.getValue().get("status"));
        assertEquals("pet-1", captor.getValue().get("petId"));
    }

    @Test
    @DisplayName("신청 조건을 충족하지 못하면 신청 상태를 저장하지 않는다")
    void should_throwForbidden_when_applyingToIneligibleProgram() {
        SupportServiceImpl service = service();
        stubMemberAndPet("서울특별시", "TEST-REG-001");
        when(supportMapper.findProgramById("program-1")).thenReturn(program("program-1"));
        when(supportMapper.findConditions("program-1"))
                .thenReturn(List.of(condition("REGION", "EQ", "제주도", true)));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.markApplyPageOpened("member-1", "program-1", "pet-1"));

        assertEquals(403, exception.getStatus().value());
        verify(supportMapper, never()).upsertInterest(anyMap());
    }

    private void stubMemberAndPet(String region, String registrationNumber) {
        when(memberMapper.findById("member-1")).thenReturn(map("member_id", "member-1", "region", region));
        when(petMapper.findById("pet-1")).thenReturn(map(
                "pet_id", "pet-1", "member_id", "member-1", "name", "보리",
                "species", "DOG", "reg_number", registrationNumber));
    }

    private Map<String, Object> program(String id) {
        return map("id", id, "title", "중성화 지원", "summary", "최대 15만원",
                "agency", "서울시", "benefit", "15만원", "period", "상시",
                "targetSpecies", "DOG");
    }

    private Map<String, Object> condition(String type, String operator, String value, boolean required) {
        return map("conditionType", type, "operator", operator, "conditionValue", value,
                "title", "조건", "description", "조건 설명", "isRequired", required);
    }

    private SupportServiceImpl service() {
        return new SupportServiceImpl(supportMapper, memberMapper, petMapper);
    }

    private static Map<String, Object> map(Object... values) {
        Map<String, Object> result = new HashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            result.put(String.valueOf(values[index]), values[index + 1]);
        }
        return result;
    }
}
