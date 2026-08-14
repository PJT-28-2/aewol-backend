package com.aewol.domain.notification.service;

import com.aewol.common.exception.BusinessException;
import com.aewol.domain.notification.dto.NotificationSettingResponse;
import com.aewol.domain.notification.dto.NotificationSettingUpdateRequest;
import com.aewol.domain.notification.mapper.NotificationSettingMapper;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationSettingServiceImplTest {

    private NotificationSettingMapper mapper;
    private NotificationSettingServiceImpl service;

    @BeforeEach
    void setUp() {
        mapper = mock(NotificationSettingMapper.class);
        service = new NotificationSettingServiceImpl(mapper);
    }

    @Test
    void getsSettingsForAuthenticatedMember() {
        when(mapper.findByMemberId("member-1")).thenReturn(settings(true, false, true, false, true));

        NotificationSettingResponse result = service.getSettings("member-1");

        assertTrue(result.isPaymentEnabled());
        assertFalse(result.isRecurringPaymentEnabled());
        assertTrue(result.isFamilyShareEnabled());
        assertFalse(result.isCommunityEnabled());
        assertTrue(result.isMarketingEnabled());
    }

    @Test
    void partialUpdatePassesFalseAndTrueWhileLeavingMissingFieldsNull() {
        NotificationSettingUpdateRequest request = request(null, true, null, false, null);
        when(mapper.findByMemberId("member-1"))
                .thenReturn(settings(true, false, true, true, false))
                .thenReturn(settings(true, true, true, false, false));

        NotificationSettingResponse result = service.updateSettings("member-1", request);

        verify(mapper).updatePartial("member-1", null, true, null, false, null);
        assertTrue(result.isPaymentEnabled());
        assertTrue(result.isRecurringPaymentEnabled());
        assertTrue(result.isFamilyShareEnabled());
        assertFalse(result.isCommunityEnabled());
        assertFalse(result.isMarketingEnabled());
    }

    @Test
    void emptyPatchIsNoOpAndReturnsCurrentSettings() {
        NotificationSettingUpdateRequest request = new NotificationSettingUpdateRequest();
        when(mapper.findByMemberId("member-1")).thenReturn(settings(false, true, false, true, false));

        NotificationSettingResponse result = service.updateSettings("member-1", request);

        verify(mapper, never()).updatePartial(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
        assertFalse(result.isPaymentEnabled());
        assertTrue(result.isRecurringPaymentEnabled());
    }

    @Test
    void missingSettingRowIsTreatedAsDataIntegrityFailure() {
        when(mapper.findByMemberId("member-1")).thenReturn(null);

        BusinessException getException = assertThrows(
                BusinessException.class, () -> service.getSettings("member-1"));
        BusinessException updateException = assertThrows(BusinessException.class,
                () -> service.updateSettings("member-1", request(true, null, null, null, null)));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, getException.getStatus());
        assertEquals("알림 설정을 처리하는 중 오류가 발생했습니다.", getException.getMessage());
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, updateException.getStatus());
        assertEquals("알림 설정을 처리하는 중 오류가 발생했습니다.", updateException.getMessage());
        verify(mapper, never()).updatePartial(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    private NotificationSettingUpdateRequest request(
            Boolean payment, Boolean recurring, Boolean family, Boolean community, Boolean marketing) {
        NotificationSettingUpdateRequest request = new NotificationSettingUpdateRequest();
        ReflectionTestUtils.setField(request, "paymentEnabled", payment);
        ReflectionTestUtils.setField(request, "recurringPaymentEnabled", recurring);
        ReflectionTestUtils.setField(request, "familyShareEnabled", family);
        ReflectionTestUtils.setField(request, "communityEnabled", community);
        ReflectionTestUtils.setField(request, "marketingEnabled", marketing);
        return request;
    }

    private Map<String, Object> settings(
            boolean payment, boolean recurring, boolean family, boolean community, boolean marketing) {
        Map<String, Object> settings = new HashMap<>();
        settings.put("payment_enabled", payment ? 1 : 0);
        settings.put("recurring_payment_enabled", recurring ? 1 : 0);
        settings.put("family_share_enabled", family ? 1 : 0);
        settings.put("community_enabled", community ? 1 : 0);
        settings.put("marketing_enabled", marketing ? 1 : 0);
        return settings;
    }
}
