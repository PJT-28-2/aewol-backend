package com.aewol.domain.transaction.service;

import com.aewol.domain.transaction.dto.PaymentRequest;
import com.aewol.domain.transaction.dto.TransactionResponse;
import com.aewol.domain.transaction.dto.TransactionTagUpdateRequest;
import com.aewol.domain.transaction.dto.TransactionPageResponse;
import java.util.List;

public interface TransactionService {
    TransactionResponse processPayment(String memberId, PaymentRequest request);
    TransactionPageResponse getTransactions(String memberId, String type, String period,
                                            String cursor, int size);
    List<TransactionResponse> getRecentTransactions(String memberId, String type, int limit);
    TransactionResponse getTransaction(String memberId, String txnId);
    TransactionResponse updateTag(String memberId, String txnId, TransactionTagUpdateRequest request);
}
