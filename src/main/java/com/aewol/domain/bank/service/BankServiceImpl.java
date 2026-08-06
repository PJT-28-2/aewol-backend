package com.aewol.domain.bank.service;

import com.aewol.domain.bank.dto.BankResponse;
import com.aewol.domain.bank.mapper.BankMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class BankServiceImpl implements BankService {

    private final BankMapper bankMapper;

    @Override
    public List<BankResponse> getBanks() {
        return bankMapper.findAll().stream()
                .map(this::toBankResponse)
                .collect(Collectors.toList());
    }

    private BankResponse toBankResponse(Map<String, Object> bank) {
        return BankResponse.builder()
                .bankCode((String) bank.get("bank_code"))
                .bankName((String) bank.get("bank_name"))
                .build();
    }
}
