package com.aewol.domain.donation.service;

import com.aewol.common.exception.BusinessException;
import com.aewol.domain.donation.mapper.DonationMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

@Service
@RequiredArgsConstructor
public class DonationServiceImpl implements DonationService {

    private final DonationMapper donationMapper;

    @Override
    public Map<String, Object> getPot(String memberId) {
        Map<String, Object> pot = donationMapper.findPotByMemberId(memberId);
        if (pot == null) throw BusinessException.notFound("저금통을 찾을 수 없습니다.");
        return pot;
    }

    @Override
    @Transactional
    public void donate(String memberId, BigDecimal amount, String recipientName) {
        Map<String, Object> pot = donationMapper.findPotByMemberId(memberId);
        if (pot == null) throw BusinessException.notFound("저금통을 찾을 수 없습니다.");

        BigDecimal balance = (BigDecimal) pot.get("balance");
        if (balance.compareTo(amount) < 0) throw new BusinessException("저금통 잔액이 부족합니다.");

        String potId = (String) pot.get("pot_id");
        donationMapper.updatePotBalance(potId, balance.subtract(amount));

        Map<String, Object> history = new HashMap<>();
        history.put("donationId", UUID.randomUUID().toString());
        history.put("potId", potId);
        history.put("amount", amount);
        history.put("recipientName", recipientName);
        donationMapper.insertHistory(history);
    }

    @Override
    public List<Map<String, Object>> getHistory(String memberId) {
        Map<String, Object> pot = donationMapper.findPotByMemberId(memberId);
        if (pot == null) return Collections.emptyList();
        return donationMapper.findHistoryByPotId((String) pot.get("pot_id"));
    }
}
