package com.aewol.domain.support.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.aewol.domain.support.mapper.Gov24SyncMapper;
import com.aewol.external.gov24.Gov24Client;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.web.client.RestClientException;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class Gov24SyncServiceImplTest {

    @Mock Gov24Client gov24Client;
    @Mock Gov24SyncMapper gov24SyncMapper;
    @Mock Gov24SyncLock gov24SyncLock;
    @Mock TransactionOperations transactionOperations;

    @BeforeEach
    void setUp() {
        lenient().doAnswer(invocation -> ((Supplier<?>) invocation.getArgument(0)).get())
                .when(gov24SyncLock).execute(any());
        lenient().doAnswer(invocation ->
                        ((TransactionCallback<?>) invocation.getArgument(0)).doInTransaction(null))
                .when(transactionOperations).execute(any());
    }

    private Gov24SyncServiceImpl service() {
        return new Gov24SyncServiceImpl(
                gov24Client, gov24SyncMapper, gov24SyncLock, transactionOperations);
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
        // 구민 전용 사업이 그 시도 주민 전체에게 열리지 않도록 시군구까지 남긴다.
        assertEquals("서울특별시 동대문구", condition.get("conditionValue"));
        assertEquals(7L, condition.get("programId"));
        // 재동기화 시 자동 생성 조건은 먼저 지운다
        verify(gov24SyncMapper).deleteGeneratedConditions(7L);
    }

    // 기관명 둘째 어절이 늘 시군구인 것은 아니다. 부서명을 지역으로 쓰면 그 조건은
    // 아무와도 맞지 않아 사업이 조용히 묻힌다.
    @Test
    @DisplayName("기관명 둘째 어절이 부서명이면 광역 단위로 남긴다")
    void should_keepSidoOnly_when_secondTokenIsDepartment() {
        when(gov24Client.isConfigured()).thenReturn(true);
        when(gov24Client.findServicesByName(anyString())).thenReturn(List.of());
        when(gov24Client.findServicesByName("반려동물"))
                .thenReturn(List.of(listRow("D1", "반려동물 의료비 지원", "서울특별시 동물복지과")));
        when(gov24SyncMapper.findProgramIdBySourceServiceId("D1")).thenReturn(11L);

        service().syncPetSupportPrograms();

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(gov24SyncMapper).insertProgramCondition(captor.capture());
        assertEquals("서울특별시", captor.getValue().get("conditionValue"));
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
        assertEquals("경기도 수원시", captor.getValue().get("region"));
    }

    @Test
    @DisplayName("한 건의 DB 저장이 실패하면 예외를 전파해 전체 트랜잭션을 롤백한다")
    void should_propagateFailure_when_oneServiceFails() {
        when(gov24Client.isConfigured()).thenReturn(true);
        when(gov24Client.findServicesByName(anyString())).thenReturn(List.of());
        when(gov24Client.findServicesByName("반려동물")).thenReturn(List.of(
                listRow("BAD", "실패 사업", "서울특별시"),
                listRow("OK", "정상 사업", "부산광역시")));
        when(gov24SyncMapper.upsertService(argThat(m -> "BAD".equals(m.get("serviceId")))))
                .thenThrow(new RuntimeException("DB 오류"));
        when(gov24SyncMapper.findProgramIdBySourceServiceId("OK")).thenReturn(12L);

        assertThrows(RuntimeException.class, () -> service().syncPetSupportPrograms());

        verify(gov24SyncMapper, never())
                .upsertCuratedProgram(argThat(m -> "OK".equals(m.get("sourceServiceId"))));
    }

    @Test
    @DisplayName("정부24 목록 수집 실패 시 기존 사업을 비활성화하지 않는다")
    void should_notDeactivatePrograms_when_collectionFails() {
        when(gov24Client.isConfigured()).thenReturn(true);
        when(gov24Client.findServicesByName(anyString()))
                .thenThrow(new RestClientException("정부24 장애"));

        assertThrows(RestClientException.class, () -> service().syncPetSupportPrograms());

        verify(transactionOperations, never()).execute(any());
        verify(gov24SyncMapper, never()).deactivateAllGov24Programs();
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

    @Test
    @DisplayName("전화문의가 기관명 상한(200자)을 넘어도 잘리지 않고 온전히 저장한다")
    void should_keepContactPhone_when_longerThanAgencyLimit() {
        // 정부24는 여러 지자체 연락처를 '시명/번호||시명/번호'로 이어붙여 내려준다.
        // 경기도처럼 시군이 많으면 200자를 쉽게 넘긴다.
        String phones = joinPhones(30);
        assertTrue(phones.length() > 200, "테스트 전제: 200자를 넘겨야 한다");

        Map<String, Object> captured = syncWithField("E5", "전화문의", phones);

        assertEquals(phones, captured.get("contactPhone"));
    }

    @Test
    @DisplayName("전화문의가 상한을 넘으면 구분자 경계에서 잘라 반토막 전화번호를 남기지 않는다")
    void should_cutAtDelimiter_when_contactPhoneExceedsLimit() {
        // 상한(20000자)을 확실히 넘기는 길이
        String phones = joinPhones(2000);
        assertTrue(phones.length() > 20000, "테스트 전제: 상한을 넘겨야 한다");

        Map<String, Object> captured = syncWithField("E6", "전화문의", phones);

        String stored = (String) captured.get("contactPhone");
        assertTrue(stored.length() <= 20000, "상한을 넘지 않아야 한다");
        assertTrue(phones.startsWith(stored), "앞에서부터 잘린 값이어야 한다");
        // 저장된 값의 모든 항목이 온전한 '시명/번호' 형태여야 한다
        for (String item : stored.split(Pattern.quote("||"))) {
            assertTrue(item.matches("[가-힣]+\\d*/\\d{3}-\\d{4}-\\d{4}"),
                    "반토막 난 항목이 남았다: " + item);
        }
    }

    @Test
    @DisplayName("상한 경계에 서로게이트 페어가 걸려도 쪼개지 않는다")
    void should_notSplitSurrogatePair_when_cuttingAtLimit() {
        // 서비스명 상한은 300자다. 300번째 자리에 이모지의 하이 서로게이트가 오게 만든다.
        String name = "가".repeat(299) + "🐶" + "뒤";
        assertTrue(Character.isHighSurrogate(name.charAt(299)), "테스트 전제: 경계가 서로게이트여야 한다");

        Map<String, Object> captured = syncWithField("E7", "서비스명", name);

        String stored = (String) captured.get("serviceName");
        assertEquals(299, stored.length());
        assertFalse(Character.isHighSurrogate(stored.charAt(stored.length() - 1)),
                "고아 서로게이트가 남으면 UTF-8로 인코딩할 수 없다");
        // 고아 서로게이트가 있으면 왕복 변환에서 값이 깨진다
        assertEquals(stored, new String(stored.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
    }

    /** 지정한 필드만 바꿔 한 건을 동기화하고, 원본 적재에 넘어간 값을 돌려준다. */
    private Map<String, Object> syncWithField(String serviceId, String field, String value) {
        when(gov24Client.isConfigured()).thenReturn(true);
        when(gov24Client.findServicesByName(anyString())).thenReturn(List.of());
        Map<String, Object> row = listRow(serviceId, "반려동물 지원", "경기도");
        row.put(field, value);
        when(gov24Client.findServicesByName("반려동물")).thenReturn(List.of(row));
        when(gov24SyncMapper.findProgramIdBySourceServiceId(serviceId)).thenReturn(21L);

        service().syncPetSupportPrograms();

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(gov24SyncMapper).upsertService(captor.capture());
        return captor.getValue();
    }

    private String joinPhones(int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) sb.append("||");
            sb.append("수원시").append(i).append("/031-5191-").append(String.format("%04d", i % 10000));
        }
        return sb.toString();
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
