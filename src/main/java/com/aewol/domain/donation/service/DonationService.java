package com.aewol.domain.donation.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public interface DonationService {
    Map<String, Object> getPot(String memberId);
    void donate(String memberId, BigDecimal amount, String recipientName);
    List<Map<String, Object>> getHistory(String memberId);
}
