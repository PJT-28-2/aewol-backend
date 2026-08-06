package com.aewol.domain.bank.service;

import com.aewol.domain.bank.dto.BankResponse;
import java.util.List;

public interface BankService {
    List<BankResponse> getBanks();
}
