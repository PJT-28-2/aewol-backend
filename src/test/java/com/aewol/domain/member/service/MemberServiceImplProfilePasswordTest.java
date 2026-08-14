package com.aewol.domain.member.service;

import com.aewol.common.exception.BusinessException;
import com.aewol.domain.auth.service.AuthCredentialStore;
import com.aewol.domain.member.dto.MemberPasswordChangeRequest;
import com.aewol.domain.member.dto.MemberPasswordVerifyRequest;
import com.aewol.domain.member.dto.MemberResponse;
import com.aewol.domain.member.dto.MemberUpdateRequest;
import com.aewol.domain.member.mapper.MemberMapper;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MemberServiceImplProfilePasswordTest {

    @Mock MemberMapper memberMapper;
    @Mock PasswordEncoder passwordEncoder;
    @Mock AuthCredentialStore authCredentialStore;

    private MemberServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new MemberServiceImpl(memberMapper, passwordEncoder, authCredentialStore);
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void getMemberKeepsAllProfileFieldsAndProviders() {
        Map<String, Object> local = member("LOCAL", "encoded");
        when(memberMapper.findById("1")).thenReturn(local);

        MemberResponse response = service.getMember("1");

        assertEquals("1", response.getMemberId());
        assertEquals("user@aewol.com", response.getEmail());
        assertEquals("홍길동", response.getName());
        assertEquals("01012345678", response.getPhone());
        assertEquals("profile.jpg", response.getProfileImg());
        assertEquals("LOCAL", response.getProvider());
        assertEquals("12345", response.getZipCode());
        assertEquals("제주시 애월읍", response.getAddress());
        assertEquals("101호", response.getAddressDetail());
        // simple_password 컬럼이 없는(=PIN 미설정) 회원은 hasSimplePassword가 false여야 한다.
        assertFalse(response.getHasSimplePassword());

        local.put("provider", "KAKAO");
        when(memberMapper.findById("2")).thenReturn(local);
        assertEquals("KAKAO", service.getMember("2").getProvider());
    }

    @Test
    void getMemberReflectsSimplePasswordStatus() {
        // hasSimplePassword는 simple_password 컬럼에 값이 있는지(true/false)만 알려주고,
        // 실제 해시값은 응답에 절대 포함되지 않는다 — 프론트가 이 필드로 로컬 캐시
        // (localStorage hasSimplePassword)를 서버 기준으로 동기화한다(2026-08-13).
        Map<String, Object> withPin = member("LOCAL", "encoded");
        withPin.put("simple_password", "encoded-pin");
        when(memberMapper.findById("1")).thenReturn(withPin);

        MemberResponse response = service.getMember("1");

        assertTrue(response.getHasSimplePassword());
    }

    @Test
    void partialUpdateNormalizesPhonePreservesNullsAndNeverUpdatesName() {
        when(memberMapper.findById("1")).thenReturn(member("LOCAL", "encoded"));
        when(memberMapper.existsActiveByPhoneExcludingMember("01012345678", "1")).thenReturn(false);
        MemberUpdateRequest request = updateRequest("010-1234-5678", null, null, null, "   ");

        service.updateMember("1", request);

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(memberMapper).updateProfile(captor.capture());
        Map<String, Object> update = captor.getValue();
        assertFalse(update.containsKey("name"));
        assertEquals("01012345678", update.get("phone"));
        assertEquals("profile.jpg", update.get("profileImg"));
        assertEquals("12345", update.get("zipCode"));
        assertEquals("제주시 애월읍", update.get("address"));
        assertEquals("", update.get("addressDetail"));
    }

    @Test
    void phoneWithSpacesAndOwnPhoneAreAllowedAndInactiveDuplicatesDoNotBlock() {
        when(memberMapper.findById("1")).thenReturn(member("LOCAL", "encoded"));
        when(memberMapper.existsActiveByPhoneExcludingMember("01012345678", "1"))
                .thenReturn(false, false);

        service.updateMember("1", updateRequest("010 1234 5678", null, null, null, null));
        service.updateMember("1", updateRequest("01012345678", null, null, null, null));

        verify(memberMapper, org.mockito.Mockito.times(2))
                .existsActiveByPhoneExcludingMember("01012345678", "1");
        verify(memberMapper, org.mockito.Mockito.times(2)).updateProfile(any());
    }

    @Test
    void activeDuplicatePhoneReturnsConflict() {
        when(memberMapper.findById("1")).thenReturn(member("LOCAL", "encoded"));
        when(memberMapper.existsActiveByPhoneExcludingMember("01099998888", "1")).thenReturn(true);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.updateMember("1", updateRequest("010-9999-8888", null, null, null, null)));

        assertEquals(409, exception.getStatus().value());
        assertEquals("이미 사용 중인 전화번호입니다.", exception.getMessage());
        verify(memberMapper, never()).updateProfile(any());
    }

    @Test
    void blankRequiredProfileFieldsReturnBadRequest() {
        when(memberMapper.findById("1")).thenReturn(member("LOCAL", "encoded"));

        assertBadRequest(() -> service.updateMember("1", updateRequest(" ", null, null, null, null)));
        assertBadRequest(() -> service.updateMember("1", updateRequest(null, null, " ", null, null)));
        assertBadRequest(() -> service.updateMember("1", updateRequest(null, null, null, " ", null)));

        verify(memberMapper, never()).updateProfile(any());
    }

    @Test
    void optionalProfileFieldsSupportPreserveAndDeletion() {
        when(memberMapper.findById("1")).thenReturn(member("LOCAL", "encoded"));

        service.updateMember("1", updateRequest(null, null, null, null, null));
        service.updateMember("1", updateRequest(null, "", null, null, ""));
        service.updateMember("1", updateRequest(null, "   ", null, null, "   "));

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(memberMapper, org.mockito.Mockito.times(3)).updateProfile(captor.capture());
        assertEquals("profile.jpg", captor.getAllValues().get(0).get("profileImg"));
        assertEquals("101호", captor.getAllValues().get(0).get("addressDetail"));
        assertEquals("", captor.getAllValues().get(1).get("profileImg"));
        assertEquals("", captor.getAllValues().get(1).get("addressDetail"));
        assertEquals("", captor.getAllValues().get(2).get("profileImg"));
        assertEquals("", captor.getAllValues().get(2).get("addressDetail"));
    }

    @Test
    void localPasswordVerificationUsesPasswordEncoder() {
        when(memberMapper.findById("1")).thenReturn(member("LOCAL", "encoded"));
        when(passwordEncoder.matches("current-password", "encoded")).thenReturn(true);

        service.verifyPassword("1", verifyRequest("current-password"));

        verify(passwordEncoder).matches("current-password", "encoded");
    }

    @Test
    void wrongPasswordAndKakaoVerificationReturnBadRequest() {
        when(memberMapper.findById("1")).thenReturn(member("LOCAL", "encoded"));
        when(passwordEncoder.matches("wrong", "encoded")).thenReturn(false);
        BusinessException wrong = assertThrows(BusinessException.class,
                () -> service.verifyPassword("1", verifyRequest("wrong")));
        assertEquals(400, wrong.getStatus().value());
        assertEquals("현재 비밀번호가 일치하지 않습니다.", wrong.getMessage());

        when(memberMapper.findById("2")).thenReturn(member("KAKAO", null));
        BusinessException kakao = assertThrows(BusinessException.class,
                () -> service.verifyPassword("2", verifyRequest("ignored")));
        assertEquals(400, kakao.getStatus().value());
        assertEquals("카카오 회원은 비밀번호 확인 대상이 아닙니다.", kakao.getMessage());
    }

    @Test
    void localPasswordChangeEncodesAndStoresOnlyEncodedPassword() {
        when(memberMapper.findById("1")).thenReturn(member("LOCAL", "encoded-old"));
        when(passwordEncoder.matches("current-password", "encoded-old")).thenReturn(true);
        when(passwordEncoder.encode("new-password")).thenReturn("encoded-new");
        when(memberMapper.updatePassword("1", "encoded-new")).thenReturn(1);
        TransactionSynchronizationManager.initSynchronization();

        service.changePassword("1", changeRequest("current-password", "new-password"));

        verify(passwordEncoder).matches("current-password", "encoded-old");
        verify(passwordEncoder).encode("new-password");
        verify(memberMapper).updatePassword("1", "encoded-new");
        verify(memberMapper, never()).updatePassword("1", "new-password");
        verify(authCredentialStore, never()).deleteRefresh("1");

        TransactionSynchronizationUtils.triggerAfterCommit();

        verify(authCredentialStore, times(1)).deleteRefresh("1");
    }

    @Test
    void passwordChangeRollbackDoesNotDeleteRefreshToken() {
        when(memberMapper.findById("1")).thenReturn(member("LOCAL", "encoded-old"));
        when(passwordEncoder.matches("current-password", "encoded-old")).thenReturn(true);
        when(passwordEncoder.encode("new-password")).thenReturn("encoded-new");
        when(memberMapper.updatePassword("1", "encoded-new")).thenReturn(1);
        TransactionSynchronizationManager.initSynchronization();

        service.changePassword("1", changeRequest("current-password", "new-password"));

        assertEquals(1, TransactionSynchronizationManager.getSynchronizations().size());
        TransactionSynchronizationUtils.triggerAfterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

        verify(authCredentialStore, never()).deleteRefresh("1");
    }

    @Test
    void passwordChangeRedisCleanupFailureAfterCommitDoesNotEscape() {
        when(memberMapper.findById("1")).thenReturn(member("LOCAL", "encoded-old"));
        when(passwordEncoder.matches("current-password", "encoded-old")).thenReturn(true);
        when(passwordEncoder.encode("new-password")).thenReturn("encoded-new");
        when(memberMapper.updatePassword("1", "encoded-new")).thenReturn(1);
        doThrow(new RuntimeException("redis unavailable"))
                .when(authCredentialStore).deleteRefresh("1");
        TransactionSynchronizationManager.initSynchronization();

        service.changePassword("1", changeRequest("current-password", "new-password"));

        assertDoesNotThrow(TransactionSynchronizationUtils::triggerAfterCommit);
        verify(authCredentialStore, times(1)).deleteRefresh("1");
    }

    @Test
    void passwordChangeWithoutTransactionSynchronizationFailsFast() {
        when(memberMapper.findById("1")).thenReturn(member("LOCAL", "encoded-old"));
        when(passwordEncoder.matches("current-password", "encoded-old")).thenReturn(true);
        when(passwordEncoder.encode("new-password")).thenReturn("encoded-new");

        assertThrows(IllegalStateException.class,
                () -> service.changePassword("1", changeRequest("current-password", "new-password")));

        verify(memberMapper, never()).updatePassword(any(), any());
        verify(authCredentialStore, never()).deleteRefresh("1");
    }

    @Test
    void passwordChangeRejectsWrongSameAndKakaoPasswords() {
        when(memberMapper.findById("1")).thenReturn(member("LOCAL", "encoded"));
        when(passwordEncoder.matches("wrong", "encoded")).thenReturn(false);
        BusinessException wrong = assertThrows(BusinessException.class,
                () -> service.changePassword("1", changeRequest("wrong", "new-password")));
        assertEquals("현재 비밀번호가 일치하지 않습니다.", wrong.getMessage());

        when(passwordEncoder.matches("same-password", "encoded")).thenReturn(true);
        BusinessException same = assertThrows(BusinessException.class,
                () -> service.changePassword("1", changeRequest("same-password", "same-password")));
        assertEquals("새 비밀번호는 현재 비밀번호와 달라야 합니다.", same.getMessage());

        when(memberMapper.findById("2")).thenReturn(member("KAKAO", null));
        BusinessException kakao = assertThrows(BusinessException.class,
                () -> service.changePassword("2", changeRequest("ignored", "new-password")));
        assertEquals("카카오 회원은 비밀번호를 변경할 수 없습니다.", kakao.getMessage());
        verify(memberMapper, never()).updatePassword(any(), any());
    }

    @Test
    void passwordChangeRejectsAnyNonSingleRowUpdate() {
        when(memberMapper.findById("1")).thenReturn(member("LOCAL", "encoded"));
        when(passwordEncoder.matches("current-password", "encoded")).thenReturn(true);
        when(passwordEncoder.encode("new-password")).thenReturn("encoded-new");
        when(memberMapper.updatePassword("1", "encoded-new")).thenReturn(0, 2);
        TransactionSynchronizationManager.initSynchronization();

        BusinessException zero = assertThrows(BusinessException.class,
                () -> service.changePassword("1", changeRequest("current-password", "new-password")));
        TransactionSynchronizationUtils.triggerAfterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.initSynchronization();

        BusinessException multiple = assertThrows(BusinessException.class,
                () -> service.changePassword("1", changeRequest("current-password", "new-password")));
        TransactionSynchronizationUtils.triggerAfterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

        assertEquals(409, zero.getStatus().value());
        assertEquals(409, multiple.getStatus().value());
        verify(authCredentialStore, never()).deleteRefresh("1");
    }

    private void assertBadRequest(org.junit.jupiter.api.function.Executable executable) {
        assertEquals(400, assertThrows(BusinessException.class, executable).getStatus().value());
    }

    private Map<String, Object> member(String provider, String password) {
        Map<String, Object> member = new HashMap<>();
        member.put("member_id", 1L);
        member.put("email", "user@aewol.com");
        member.put("name", "홍길동");
        member.put("phone", "01012345678");
        member.put("profile_img", "profile.jpg");
        member.put("provider", provider);
        member.put("password", password);
        member.put("zip_code", "12345");
        member.put("address", "제주시 애월읍");
        member.put("address_detail", "101호");
        return member;
    }

    private MemberUpdateRequest updateRequest(
            String phone, String profileImg, String zipCode, String address, String addressDetail) {
        MemberUpdateRequest request = new MemberUpdateRequest();
        ReflectionTestUtils.setField(request, "phone", phone);
        ReflectionTestUtils.setField(request, "profileImg", profileImg);
        ReflectionTestUtils.setField(request, "zipCode", zipCode);
        ReflectionTestUtils.setField(request, "address", address);
        ReflectionTestUtils.setField(request, "addressDetail", addressDetail);
        return request;
    }

    private MemberPasswordVerifyRequest verifyRequest(String currentPassword) {
        MemberPasswordVerifyRequest request = new MemberPasswordVerifyRequest();
        ReflectionTestUtils.setField(request, "currentPassword", currentPassword);
        return request;
    }

    private MemberPasswordChangeRequest changeRequest(String currentPassword, String newPassword) {
        MemberPasswordChangeRequest request = new MemberPasswordChangeRequest();
        ReflectionTestUtils.setField(request, "currentPassword", currentPassword);
        ReflectionTestUtils.setField(request, "newPassword", newPassword);
        return request;
    }
}
