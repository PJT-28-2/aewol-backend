package com.aewol.domain.emergency.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aewol.domain.emergency.mapper.HospitalSeedMapper;
import com.aewol.external.animalhospital.AnimalHospitalClient;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
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
        lenient().doAnswer(invocation -> ((Supplier<?>) invocation.getArgument(0)).get())
                .when(hospitalSeedLock).execute(any());
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
    @DisplayName("영업상태명에 '폐업'이 포함되면 스킵한다")
    void should_skipRow_when_hospitalIsClosed() {
        Map<String, Object> row = row();
        when(animalHospitalClient.isConfigured()).thenReturn(true);
        when(animalHospitalClient.findAllHospitals()).thenReturn(List.of(row));
        when(animalHospitalClient.mgtNo(row)).thenReturn("3620000-HS-2024-000001");
        when(animalHospitalClient.statusName(row)).thenReturn("폐업");

        int upserted = service().syncHospitals();

        assertEquals(0, upserted);
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
