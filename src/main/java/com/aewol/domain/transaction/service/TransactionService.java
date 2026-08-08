package com.aewol.domain.transaction.service;

import com.aewol.domain.transaction.dto.PaymentRequest;
import com.aewol.domain.transaction.dto.TransactionResponse;
import java.util.List;

public interface TransactionService {
    TransactionResponse processPayment(String memberId, PaymentRequest request);
    List<TransactionResponse> getTransactions(String memberId, String category, String petId);
    List<TransactionResponse> getRecentTransactions(String memberId);
    TransactionResponse getTransaction(String memberId, String txnId);
}
