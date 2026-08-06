package com.aewol.domain.bank.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.aewol.domain.bank.dto.BankResponse;
import com.aewol.domain.bank.mapper.BankMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BankServiceImplTest {

    @Mock BankMapper bankMapper;
    @InjectMocks BankServiceImpl service;

    @Test
    @DisplayName("bank_master 조회 결과를 은행 코드/이름 응답으로 변환한다")
    void should_returnBankList_when_bankMasterHasRows() {
        when(bankMapper.findAll()).thenReturn(List.of(bankRow("004", "국민은행"), bankRow("088", "신한은행")));

        List<BankResponse> result = service.getBanks();

        assertEquals(2, result.size());
        assertEquals("004", result.get(0).getBankCode());
        assertEquals("국민은행", result.get(0).getBankName());
    }

    @Test
    @DisplayName("bank_master가 비어있으면 빈 목록을 반환한다")
    void should_returnEmptyList_when_bankMasterIsEmpty() {
        when(bankMapper.findAll()).thenReturn(List.of());

        List<BankResponse> result = service.getBanks();

        assertTrue(result.isEmpty());
    }

    private Map<String, Object> bankRow(String code, String name) {
        Map<String, Object> row = new HashMap<>();
        row.put("bank_code", code);
        row.put("bank_name", name);
        return row;
    }
}
