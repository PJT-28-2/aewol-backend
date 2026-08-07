package com.aewol.domain.emergency.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aewol.domain.emergency.mapper.HospitalSeedMapper;
import com.aewol.external.animalhospital.AnimalHospitalClient;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class HospitalSeedServiceImplTest {

    @Mock AnimalHospitalClient animalHospitalClient;
    @Mock HospitalSeedMapper hospitalSeedMapper;
    @Mock HospitalSeedLock hospitalSeedLock;
    @Mock TransactionOperations transactionOperations;

    @BeforeEach
    void setUp() {
        // 기본값: 락 소유권을 계속 유지한다고 가정. 소유권 상실 시나리오는 각 테스트에서
        // hospitalSeedLock.execute(...) 스텁을 오버라이드해 BooleanSupplier를 직접 제어한다.
        lenient().doAnswer(invocation -> {
            Function<BooleanSupplier, ?> action = invocation.getArgument(0);
            return action.apply(() -> true);
        }).when(hospitalSeedLock).execute(any());
        lenient().doAnswer(invocation ->
                        ((TransactionCallback<?>) invocation.getArgument(0)).doInTransaction(null))
                .when(transactionOperations).execute(any());
    }

    private HospitalSeedServiceImpl service() {
        return new HospitalSeedServiceImpl(
                animalHospitalClient, hospitalSeedMapper, hospitalSeedLock, transactionOperations);
    }

    private Map<String, Object> row() {
        return new HashMap<>(Map.of("mgtNo", "3620000-HS-2024-000001"));
    }

    @Test
    @DisplayName("service-key가 없으면 외부 호출 없이 시딩을 건너뛴다")
    void should_skipSync_when_serviceKeyIsMissing() {
        when(animalHospitalClient.isConfigured()).thenReturn(false);

        int result = service().syncHospitals();

        assertEquals(0, result);
        verify(animalHospitalClient, never()).findAllHospitals();
        verify(hospitalSeedMapper, never()).upsertHospital(anyMap());
    }

    @Test
    @DisplayName("유효한 행은 WGS84 좌표로 변환되어 upsert된다")
    void should_upsertHospital_when_rowIsValid() {
        Map<String, Object> row = row();
        when(animalHospitalClient.isConfigured()).thenReturn(true);
        when(animalHospitalClient.findAllHospitals()).thenReturn(List.of(row));
        when(animalHospitalClient.mgtNo(row)).thenReturn("3620000-HS-2024-000001");
        when(animalHospitalClient.name(row)).thenReturn("애월동물병원");
        when(animalHospitalClient.address(row)).thenReturn("제주시 애월읍 애월로 1");
        when(animalHospitalClient.phone(row)).thenReturn("064-000-0000");
        when(animalHospitalClient.statusName(row)).thenReturn("영업/정상");
        when(animalHospitalClient.toWgs84(row)).thenReturn(new double[]{126.3110, 33.4620});

        int upserted = service().syncHospitals();

        assertEquals(1, upserted);
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(hospitalSeedMapper).upsertHospital(captor.capture());
        Map<String, Object> saved = captor.getValue();
        assertEquals("3620000-HS-2024-000001", saved.get("externalMngNo"));
        assertEquals("애월동물병원", saved.get("name"));
        assertEquals(BigDecimal.valueOf(33.4620), saved.get("latitude"));
        assertEquals(BigDecimal.valueOf(126.3110), saved.get("longitude"));
        assertEquals(0, saved.get("is24h"));
        assertEquals(null, saved.get("isHolidayOpen"));
        assertEquals(null, saved.get("avgWaitMinutes"));
    }

    @Test
    @DisplayName("관리번호(mgtNo)가 없는 행은 스킵한다")
    void should_skipRow_when_mgtNoMissing() {
        Map<String, Object> row = row();
        when(animalHospitalClient.isConfigured()).thenReturn(true);
        when(animalHospitalClient.findAllHospitals()).thenReturn(List.of(row));
        when(animalHospitalClient.mgtNo(row)).thenReturn(null);

        int upserted = service().syncHospitals();

        assertEquals(0, upserted);
        verify(hospitalSeedMapper, never()).upsertHospital(anyMap());
    }

    @Test
    @DisplayName("영업상태명에 '폐업'이 포함되면 upsert하지 않고 external_mng_no로 삭제한다")
    void should_deleteExistingRecord_when_hospitalIsClosed() {
        Map<String, Object> row = row();
        when(animalHospitalClient.isConfigured()).thenReturn(true);
        when(animalHospitalClient.findAllHospitals()).thenReturn(List.of(row));
        when(animalHospitalClient.mgtNo(row)).thenReturn("3620000-HS-2024-000001");
        when(animalHospitalClient.statusName(row)).thenReturn("폐업");

        int upserted = service().syncHospitals();

        assertEquals(0, upserted);
        verify(hospitalSeedMapper).deleteHospitalByExternalMngNo("3620000-HS-2024-000001");
        verify(hospitalSeedMapper, never()).upsertHospital(anyMap());
    }

    @Test
    @DisplayName("[회귀] 이전에 저장된 병원이 이후 폐업으로 전환되면 재실행 시 삭제되어 더 이상 조회되지 않는다")
    void should_removeStaleRecord_when_previouslySeededHospitalIsLaterClosed() {
        // 이전 실행에서 영업 중으로 upsert됐던 병원이, 이번 실행에서는 '폐업/자진'처럼
        // 마커 문구를 포함한 다양한 표기로 내려온다고 가정한다.
        Map<String, Object> row = row();
        when(animalHospitalClient.isConfigured()).thenReturn(true);
        when(animalHospitalClient.findAllHospitals()).thenReturn(List.of(row));
        when(animalHospitalClient.mgtNo(row)).thenReturn("3620000-HS-2024-000001");
        when(animalHospitalClient.statusName(row)).thenReturn("폐업/자진");

        int upserted = service().syncHospitals();

        assertEquals(0, upserted);
        verify(hospitalSeedMapper).deleteHospitalByExternalMngNo("3620000-HS-2024-000001");
        verify(hospitalSeedMapper, never()).upsertHospital(anyMap());
    }

    @Test
    @DisplayName("병원명 또는 주소가 없는 행은 스킵한다")
    void should_skipRow_when_nameOrAddressMissing() {
        Map<String, Object> row = row();
        when(animalHospitalClient.isConfigured()).thenReturn(true);
        when(animalHospitalClient.findAllHospitals()).thenReturn(List.of(row));
        when(animalHospitalClient.mgtNo(row)).thenReturn("3620000-HS-2024-000001");
        when(animalHospitalClient.statusName(row)).thenReturn("영업/정상");
        when(animalHospitalClient.name(row)).thenReturn(null);

        int upserted = service().syncHospitals();

        assertEquals(0, upserted);
        verify(hospitalSeedMapper, never()).upsertHospital(anyMap());
    }

    @Test
    @DisplayName("[회귀] 수집 후 DB 쓰기 전에 락 소유권을 상실하면 어떤 행도 upsert/삭제하지 않고 중단한다")
    void should_abortBeforeAnyWrite_when_lockOwnershipLostBeforePersisting() {
        Map<String, Object> row = row();
        when(animalHospitalClient.isConfigured()).thenReturn(true);
        when(animalHospitalClient.findAllHospitals()).thenReturn(List.of(row));
        // 소유권을 이미 상실한 상태로 시작 — 새 인스턴스가 이미 락을 가져갔다고 가정.
        doExecuteWithOwnership(() -> false);

        assertThrows(IllegalStateException.class, () -> service().syncHospitals());

        verify(hospitalSeedMapper, never()).upsertHospital(anyMap());
        verify(hospitalSeedMapper, never()).deleteHospitalByExternalMngNo(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("[회귀] 트랜잭션 도중 락 소유권을 상실하면 이미 처리한 행 이후로는 즉시 중단한다"
            + " (새 인스턴스가 삭제한 폐업 병원을 이전 실행이 다시 upsert하는 것을 방지)")
    void should_stopMidTransaction_when_lockOwnershipLostDuringPersisting() {
        Map<String, Object> row1 = new HashMap<>(Map.of("mgtNo", "row-1"));
        Map<String, Object> row2 = new HashMap<>(Map.of("mgtNo", "row-2"));
        when(animalHospitalClient.isConfigured()).thenReturn(true);
        when(animalHospitalClient.findAllHospitals()).thenReturn(List.of(row1, row2));
        when(animalHospitalClient.mgtNo(row1)).thenReturn("row-1");
        when(animalHospitalClient.name(row1)).thenReturn("병원1");
        when(animalHospitalClient.address(row1)).thenReturn("주소1");
        when(animalHospitalClient.statusName(row1)).thenReturn("영업/정상");
        // row2는 값을 세팅하지 않는다 — 소유권 상실로 row1 처리 직후 중단되어 row2까지는
        // 도달하지 않아야 하므로, 도달한다면 스텁 부재로 NPE가 나서 테스트가 실패해 드러난다.

        // 사전 확인(1회) + row1 확인(2회째까지는 true) + row2 확인 시점(3회째)부터 false.
        AtomicInteger callIndex = new AtomicInteger();
        doExecuteWithOwnership(() -> callIndex.getAndIncrement() < 2);

        assertThrows(IllegalStateException.class, () -> service().syncHospitals());

        verify(hospitalSeedMapper, times(1)).upsertHospital(anyMap());
    }

    /**
     * {@code when(mock.execute(any()))} 형태로 재스텁하면, 이미 걸려있는 이전 doAnswer 스텁이
     * 그 호출 자체를 가로채 실행되면서 {@code any()} 매처의 null 플레이스홀더를 action으로
     * 잘못 넘겨 NPE가 난다. doAnswer(...).when(...) 형태로 덮어써야 안전하다.
     */
    private void doExecuteWithOwnership(BooleanSupplier lockOwned) {
        doAnswer(invocation -> {
            Function<BooleanSupplier, ?> action = invocation.getArgument(0);
            return action.apply(lockOwned);
        }).when(hospitalSeedLock).execute(any());
    }

    @Test
    @DisplayName("좌표 변환에 실패하면 위경도는 NULL로 저장한다")
    void should_saveNullCoordinates_when_conversionFails() {
        Map<String, Object> row = row();
        when(animalHospitalClient.isConfigured()).thenReturn(true);
        when(animalHospitalClient.findAllHospitals()).thenReturn(List.of(row));
        when(animalHospitalClient.mgtNo(row)).thenReturn("3620000-HS-2024-000001");
        when(animalHospitalClient.statusName(row)).thenReturn("영업/정상");
        when(animalHospitalClient.name(row)).thenReturn("애월동물병원");
        when(animalHospitalClient.address(row)).thenReturn("제주시 애월읍 애월로 1");
        when(animalHospitalClient.toWgs84(row)).thenReturn(null);

        int upserted = service().syncHospitals();

        assertEquals(1, upserted);
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(hospitalSeedMapper).upsertHospital(captor.capture());
        assertEquals(null, captor.getValue().get("latitude"));
        assertEquals(null, captor.getValue().get("longitude"));
    }
}
