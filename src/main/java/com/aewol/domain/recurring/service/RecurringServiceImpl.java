package com.aewol.domain.recurring.service;

import com.aewol.common.exception.BusinessException;
import com.aewol.domain.recurring.mapper.RecurringMapper;
import com.aewol.domain.wallet.mapper.WalletMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
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
        return recurringMapper.findByWalletId(String.valueOf(wallet.get("wallet_id")));
    }

    @Override
    @Transactional
    public Map<String, Object> createRecurring(String memberId, Map<String, Object> request) {
        Map<String, Object> wallet = walletMapper.findByMemberId(memberId);
        if (wallet == null) throw BusinessException.notFound("지갑을 찾을 수 없습니다.");

        // 프론트 계약: { itemName, price, cycleDay(1~28), category, petId }
        int paymentDay = parsePaymentDay(request.get("cycleDay") != null ? request.get("cycleDay") : request.get("paymentDay"));

        Map<String, Object> recurring = new HashMap<>();
        recurring.put("walletId", wallet.get("wallet_id"));
        recurring.put("petId", request.get("petId"));
        recurring.put("productName", request.get("itemName") != null ? request.get("itemName") : request.get("productName"));
        recurring.put("category", request.get("category"));
        recurring.put("price", request.get("price") != null ? request.get("price") : request.get("amount"));
        recurring.put("paymentDay", paymentDay);
        recurring.put("nextPaymentDate", nextPaymentDate(paymentDay));
        recurringMapper.insert(recurring); // recurring_id AUTO_INCREMENT
        return recurring;
    }

    @Override
    @Transactional
    public void cancelRecurring(String recurringId) {
        recurringMapper.deactivate(recurringId);
    }

    private int parsePaymentDay(Object value) {
        if (value == null) throw new BusinessException("결제일(1~28)을 선택해 주세요.");
        int day = value instanceof Number ? ((Number) value).intValue() : Integer.parseInt(String.valueOf(value));
        if (day < 1 || day > 28) throw new BusinessException("결제일은 1~28 사이여야 합니다.");
        return day;
    }

    /** 매월 N일 의미의 payment_day 기준, 다음 도래일 계산 */
    private LocalDate nextPaymentDate(int paymentDay) {
        LocalDate today = LocalDate.now();
        LocalDate candidate = today.withDayOfMonth(paymentDay);
        return candidate.isAfter(today) ? candidate : candidate.plusMonths(1);
    }
}
