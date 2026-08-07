package com.aewol.domain.account.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.aewol.common.exception.BusinessException;
import com.aewol.domain.account.dto.AccountPrimaryRequest;
import com.aewol.domain.account.mapper.AccountMapper;
import com.aewol.domain.account.mapper.AccountVerificationMapper;
import com.aewol.external.codef.CodefClient;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;

@ExtendWith(MockitoExtension.class)
class AccountServiceImplTest {

    @Mock AccountMapper accountMapper;
    @Mock AccountVerificationMapper accountVerificationMapper;
    @Mock CodefClient codefClient;
    @Mock Environment environment;
    @InjectMocks AccountServiceImpl service;

    private static final String MEMBER_ID = "9001";
    private static final String ACCOUNT_ID = "1";

    @Test
    @DisplayName("본인 소유의 활성 계좌를 대표 계좌로 설정한다")
    void should_setPrimary_when_ownerAndActiveAccount() {
        when(accountMapper.findByAccountId(ACCOUNT_ID))
                .thenReturn(accountRow(ACCOUNT_ID, MEMBER_ID, "ACTIVE", false))
                .thenReturn(accountRow(ACCOUNT_ID, MEMBER_ID, "ACTIVE", true));
        when(accountMapper.setPrimary(ACCOUNT_ID)).thenReturn(1);

        var result = service.setPrimaryAccount(MEMBER_ID, ACCOUNT_ID, request(true));

        assertTrue(result.getIsPrimary());
        verify(accountMapper).clearPrimaryByMemberId(MEMBER_ID);
        verify(accountMapper).setPrimary(ACCOUNT_ID);
    }

    @Test
    @DisplayName("findByAccountId 확인 이후 계좌가 동시에 연동 해제되면(setPrimary 영향 행 0) 예외를 던진다")
    void should_throwConflict_when_accountDisconnectedConcurrentlyBeforeSetPrimary() {
        // findByAccountId 시점엔 ACTIVE였지만, 그 사이 다른 트랜잭션이 INACTIVE로
        // 바꿔서 setPrimary의 WHERE status='ACTIVE' 조건에 안 걸리는 상황을 재현한다.
        when(accountMapper.findByAccountId(ACCOUNT_ID))
                .thenReturn(accountRow(ACCOUNT_ID, MEMBER_ID, "ACTIVE", false));
        when(accountMapper.setPrimary(ACCOUNT_ID)).thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.setPrimaryAccount(MEMBER_ID, ACCOUNT_ID, request(true)));

        assertEquals(org.springframework.http.HttpStatus.CONFLICT, ex.getStatus());
        verify(accountMapper).clearPrimaryByMemberId(MEMBER_ID);
    }

    @Test
    @DisplayName("isPrimary가 true가 아니면 예외를 던진다")
    void should_throwBadRequest_when_isPrimaryNotTrue() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.setPrimaryAccount(MEMBER_ID, ACCOUNT_ID, request(false)));

        assertEquals(org.springframework.http.HttpStatus.BAD_REQUEST, ex.getStatus());
        verify(accountMapper, never()).clearPrimaryByMemberId(any());
    }

    @Test
    @DisplayName("계좌를 찾을 수 없으면 404 예외를 던진다")
    void should_throwNotFound_when_accountDoesNotExist() {
        when(accountMapper.findByAccountId(ACCOUNT_ID)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.setPrimaryAccount(MEMBER_ID, ACCOUNT_ID, request(true)));

        assertEquals(org.springframework.http.HttpStatus.NOT_FOUND, ex.getStatus());
    }

    @Test
    @DisplayName("다른 회원 소유의 계좌면 404 예외를 던진다")
    void should_throwNotFound_when_accountBelongsToAnotherMember() {
        when(accountMapper.findByAccountId(ACCOUNT_ID))
                .thenReturn(accountRow(ACCOUNT_ID, "9002", "ACTIVE", false));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.setPrimaryAccount(MEMBER_ID, ACCOUNT_ID, request(true)));

        assertEquals(org.springframework.http.HttpStatus.NOT_FOUND, ex.getStatus());
    }

    @Test
    @DisplayName("연동 해제된(INACTIVE) 계좌는 대표 계좌로 설정할 수 없다")
    void should_throwConflict_when_accountInactive() {
        when(accountMapper.findByAccountId(ACCOUNT_ID))
                .thenReturn(accountRow(ACCOUNT_ID, MEMBER_ID, "INACTIVE", false));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.setPrimaryAccount(MEMBER_ID, ACCOUNT_ID, request(true)));

        assertEquals(org.springframework.http.HttpStatus.CONFLICT, ex.getStatus());
        verify(accountMapper, never()).clearPrimaryByMemberId(any());
    }

    private AccountPrimaryRequest request(boolean isPrimary) {
        return new AccountPrimaryRequest(isPrimary);
    }

    private Map<String, Object> accountRow(String accountId, String memberId, String status, boolean isPrimary) {
        Map<String, Object> row = new HashMap<>();
        row.put("account_id", accountId);
        row.put("member_id", memberId);
        row.put("bank_code", "004");
        row.put("bank_name", "KB국민은행");
        row.put("account_number", "1234567890");
        row.put("status", status);
        row.put("is_primary", isPrimary);
        return row;
    }
}
