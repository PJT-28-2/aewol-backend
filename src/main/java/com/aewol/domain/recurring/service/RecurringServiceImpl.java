package com.aewol.domain.recurring.service;

import com.aewol.common.exception.BusinessException;
import com.aewol.domain.recurring.mapper.RecurringMapper;
import com.aewol.domain.wallet.mapper.WalletMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
@RequiredArgsConstructor
public class RecurringServiceImpl implements RecurringService {

    private final RecurringMapper recurringMapper;
    private final WalletMapper walletMapper;

    @Override
    public List<Map<String, Object>> getRecurringPayments(String memberId) {
        Map<String, Object> wallet = walletMapper.findByMemberId(memberId);
        if (wallet == null) throw BusinessException.notFound("지갑을 찾을 수 없습니다.");
        return recurringMapper.findByWalletId((String) wallet.get("wallet_id"));
    }

    @Override
    @Transactional
    public Map<String, Object> createRecurring(String memberId, Map<String, Object> request) {
        Map<String, Object> wallet = walletMapper.findByMemberId(memberId);
        if (wallet == null) throw BusinessException.notFound("지갑을 찾을 수 없습니다.");

        String recurringId = UUID.randomUUID().toString();
        Map<String, Object> recurring = new HashMap<>(request);
        recurring.put("recurringId", recurringId);
        recurring.put("walletId", wallet.get("wallet_id"));
        recurringMapper.insert(recurring);
        return recurring;
    }

    @Override
    @Transactional
    public void cancelRecurring(String recurringId) {
        recurringMapper.deactivate(recurringId);
    }
}
