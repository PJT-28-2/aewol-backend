package com.aewol.domain.transaction.service;

import com.aewol.domain.transaction.dto.PaymentRecordCommand;
import com.aewol.domain.transaction.dto.PaymentRequest;
import com.aewol.domain.transaction.dto.TransactionResponse;
import com.aewol.domain.transaction.dto.TransactionTagUpdateRequest;
import com.aewol.domain.transaction.dto.TransactionPageResponse;
import java.util.List;

public interface TransactionService {
    TransactionResponse processPayment(String memberId, PaymentRequest request);
    // 프록시 경계를 넘겨 독립된 짧은 트랜잭션으로 실행되어야 하므로 인터페이스에 선언한다.
    // private 자기호출로는 Spring AOP 프록시를 거치지 않아 @Transactional이 적용되지 않는다.
    TransactionResponse recordExternalPayment(PaymentRecordCommand command);
    TransactionPageResponse getTransactions(String memberId, String type, String period,
                                            String cursor, int size);
    List<TransactionResponse> getRecentTransactions(String memberId, String type, int limit);
    TransactionResponse getTransaction(String memberId, String txnId);
    TransactionResponse updateTag(String memberId, String txnId, TransactionTagUpdateRequest request);
}
