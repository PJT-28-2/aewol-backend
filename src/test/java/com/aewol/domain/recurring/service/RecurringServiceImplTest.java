package com.aewol.domain.recurring.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

import com.aewol.common.exception.BusinessException;
import com.aewol.domain.recurring.mapper.RecurringMapper;
import com.aewol.domain.wallet.mapper.WalletMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class RecurringServiceImplTest {

    @Mock RecurringMapper recurringMapper;
    @Mock WalletMapper walletMapper;

    @Test
    void should_deactivateRecurring_when_memberOwnsWallet() {
        RecurringServiceImpl service = new RecurringServiceImpl(recurringMapper, walletMapper);
        when(recurringMapper.findById("recurring-1")).thenReturn(Map.of("wallet_id", "wallet-1"));
        when(walletMapper.findById("wallet-1")).thenReturn(Map.of("member_id", "member-1"));

        service.cancelRecurring("member-1", "recurring-1");

        verify(recurringMapper).deactivate("recurring-1");
    }

    @Test
    void should_throwNotFound_when_recurringDoesNotExist() {
        RecurringServiceImpl service = new RecurringServiceImpl(recurringMapper, walletMapper);
        when(recurringMapper.findById("recurring-404")).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.cancelRecurring("member-1", "recurring-404"));

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatus());
    }

    @Test
    void should_throwForbidden_when_memberDoesNotOwnRecurringWallet() {
        RecurringServiceImpl service = new RecurringServiceImpl(recurringMapper, walletMapper);
        when(recurringMapper.findById("recurring-1")).thenReturn(Map.of("wallet_id", "wallet-1"));
        when(walletMapper.findById("wallet-1")).thenReturn(Map.of("member_id", "owner-1"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.cancelRecurring("member-2", "recurring-1"));

        assertEquals(HttpStatus.FORBIDDEN, exception.getStatus());
        verify(recurringMapper, never()).deactivate(anyString());
    }
}
