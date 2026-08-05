package com.aewol.domain.support.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.aewol.domain.support.mapper.Gov24SyncMapper;
import com.aewol.external.gov24.Gov24Client;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class Gov24SyncServiceImplTest {

    @Mock Gov24Client gov24Client;
    @Mock Gov24SyncMapper gov24SyncMapper;

    private Gov24SyncServiceImpl service() {
        return new Gov24SyncServiceImpl(gov24Client, gov24SyncMapper);
    }

    @Test
    @DisplayName("service-key가 없으면 외부 호출 없이 동기화를 건너뛴다")
    void should_skipSync_when_serviceKeyIsMissing() {
        when(gov24Client.isConfigured()).thenReturn(false);

        int result = service().syncPetSupportPrograms();

        assertEquals(0, result);
        verify(gov24Client, never()).findServicesByName(anyString());
        verify(gov24SyncMapper, never()).upsertService(anyMap());
    }

    @Test
    @DisplayName("여러 키워드에 중복 등장한 서비스는 한 번만 적재한다")
    void should_deduplicateService_when_matchedByMultipleKeywords() {
        when(gov24Client.isConfigured()).thenReturn(true);
        Map<String, Object> row = listRow("305000000130", "취약계층 반려동물 중성화 지원", "서울특별시 동대문구");
        // '반려동물'과 '중성화' 두 키워드에 모두 걸리는 상황
        when(gov24Client.findServicesByName(anyString())).thenReturn(List.of());
        when(gov24Client.findServicesByName("반려동물")).thenReturn(List.of(row));
        when(gov24Client.findServicesByName("중성화")).thenReturn(List.of(row));
        when(gov24SyncMapper.findProgramIdBySourceServiceId("305000000130")).thenReturn(11L);

        int curated = service().syncPetSupportPrograms();

        assertEquals(1, curated);
        verify(gov24SyncMapper, times(1)).upsertService(anyMap());
        verify(gov24SyncMapper, times(1)).upsertCuratedProgram(anyMap());
    }

    @Test
    @DisplayName("소관기관명에서 광역 단위를 뽑아 지역 조건을 생성한다")
    void should_createRegionCondition_when_organizationIsLocalGovernment() {
        when(gov24Client.isConfigured()).thenReturn(true);
        when(gov24Client.findServicesByName(anyString())).thenReturn(List.of());
        when(gov24Client.findServicesByName("반려동물"))
                .thenReturn(List.of(listRow("A1", "반려동물 의료비 지원", "서울특별시 동대문구")));
        when(gov24SyncMapper.findProgramIdBySourceServiceId("A1")).thenReturn(7L);

        service().syncPetSupportPrograms();

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(gov24SyncMapper).insertProgramCondition(captor.capture());
        Map<String, Object> condition = captor.getValue();
        assertEquals("REGION", condition.get("conditionType"));
        assertEquals("서울특별시", condition.get("conditionValue"));
        assertEquals(7L, condition.get("programId"));
        // 재동기화 시 자동 생성 조건은 먼저 지운다
        verify(gov24SyncMapper).deleteGeneratedConditions(7L);
    }

    @Test
    @DisplayName("중앙행정기관 사업은 지역 조건을 만들지 않는다")
    void should_skipRegionCondition_when_organizationIsCentralAgency() {
        when(gov24Client.isConfigured()).thenReturn(true);
        when(gov24Client.findServicesByName(anyString())).thenReturn(List.of());
        when(gov24Client.findServicesByName("동물등록"))
                .thenReturn(List.of(listRow("B2", "동물등록 지원", "농림축산식품부")));
        when(gov24SyncMapper.findProgramIdBySourceServiceId("B2")).thenReturn(8L);

        service().syncPetSupportPrograms();

        verify(gov24SyncMapper, never()).insertProgramCondition(anyMap());
    }

    @Test
    @DisplayName("서비스명에 축종이 드러나면 target_species에 반영한다")
    void should_setTargetSpecies_when_serviceNameHasSpecies() {
        when(gov24Client.isConfigured()).thenReturn(true);
        when(gov24Client.findServicesByName(anyString())).thenReturn(List.of());
        when(gov24Client.findServicesByName("반려견"))
                .thenReturn(List.of(listRow("C3", "반려견 놀이터 이용 지원", "경기도 수원시")));
        when(gov24SyncMapper.findProgramIdBySourceServiceId("C3")).thenReturn(9L);

        service().syncPetSupportPrograms();

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(gov24SyncMapper).upsertCuratedProgram(captor.capture());
        assertEquals("DOG", captor.getValue().get("targetSpecies"));
        assertEquals("경기도", captor.getValue().get("region"));
    }

    @Test
    @DisplayName("한 건이 실패해도 나머지 서비스는 계속 동기화한다")
    void should_continueSync_when_oneServiceFails() {
        when(gov24Client.isConfigured()).thenReturn(true);
        when(gov24Client.findServicesByName(anyString())).thenReturn(List.of());
        when(gov24Client.findServicesByName("반려동물")).thenReturn(List.of(
                listRow("BAD", "실패 사업", "서울특별시"),
                listRow("OK", "정상 사업", "부산광역시")));
        when(gov24SyncMapper.upsertService(argThat(m -> "BAD".equals(m.get("serviceId")))))
                .thenThrow(new RuntimeException("DB 오류"));
        when(gov24SyncMapper.findProgramIdBySourceServiceId("OK")).thenReturn(12L);

        int curated = service().syncPetSupportPrograms();

        assertEquals(1, curated);
        verify(gov24SyncMapper).upsertCuratedProgram(argThat(m -> "OK".equals(m.get("sourceServiceId"))));
    }

    @Test
    @DisplayName("동기화 시작 시 기존 GOV24 사업을 일괄 비활성화한다")
    void should_deactivateAllGov24Programs_when_syncStarts() {
        when(gov24Client.isConfigured()).thenReturn(true);
        when(gov24Client.findServicesByName(anyString())).thenReturn(List.of());

        service().syncPetSupportPrograms();

        verify(gov24SyncMapper).deactivateAllGov24Programs();
    }

    @Test
    @DisplayName("비활성화는 큐레이션보다 먼저 실행되어야 방금 넣은 사업이 살아남는다")
    void should_deactivateBeforeCurating_when_syncRuns() {
        when(gov24Client.isConfigured()).thenReturn(true);
        when(gov24Client.findServicesByName(anyString())).thenReturn(List.of());
        when(gov24Client.findServicesByName("반려동물"))
                .thenReturn(List.of(listRow("E5", "반려동물 지원", "인천광역시")));
        when(gov24SyncMapper.findProgramIdBySourceServiceId("E5")).thenReturn(20L);

        service().syncPetSupportPrograms();

        InOrder order = inOrder(gov24SyncMapper);
        order.verify(gov24SyncMapper).deactivateAllGov24Programs();
        order.verify(gov24SyncMapper).upsertCuratedProgram(anyMap());
    }

    @Test
    @DisplayName("값이 없을 때 오는 문자열 None은 null로 저장한다")
    void should_treatNoneAsNull_when_fieldIsEmpty() {
        when(gov24Client.isConfigured()).thenReturn(true);
        when(gov24Client.findServicesByName(anyString())).thenReturn(List.of());
        Map<String, Object> row = listRow("D4", "반려동물 지원", "대구광역시");
        row.put("지원내용", "None");
        when(gov24Client.findServicesByName("반려동물")).thenReturn(List.of(row));
        when(gov24SyncMapper.findProgramIdBySourceServiceId("D4")).thenReturn(13L);

        service().syncPetSupportPrograms();

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(gov24SyncMapper).upsertCuratedProgram(captor.capture());
        assertNull(captor.getValue().get("description"));
    }

    private Map<String, Object> listRow(String serviceId, String serviceName, String organization) {
        Map<String, Object> row = new HashMap<>();
        row.put("서비스ID", serviceId);
        row.put("서비스명", serviceName);
        row.put("소관기관명", organization);
        row.put("지원유형", "현금");
        row.put("지원내용", "진료비 일부 지원");
        row.put("서비스목적요약", "취약계층 반려동물 진료비 지원");
        row.put("신청기한", "예산 소진 시까지");
        row.put("신청방법", "방문신청");
        row.put("상세조회URL", "https://www.gov.kr/portal/rcvfvrSvc/dtlEx/" + serviceId);
        row.put("수정일시", "20260129201825");
        return row;
    }
}
