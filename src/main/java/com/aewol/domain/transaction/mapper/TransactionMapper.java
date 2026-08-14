package com.aewol.domain.transaction.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;
import java.util.Map;

@Mapper
public interface TransactionMapper {
    Map<String, Object> findById(@Param("txnId") String txnId);
    List<Map<String, Object>> findByWalletId(@Param("walletId") String walletId,
                                              @Param("txnFilter") String txnFilter,
                                              @Param("startDate") java.time.LocalDateTime startDate,
                                              @Param("endDate") java.time.LocalDateTime endDate,
                                              @Param("cursorDate") java.time.LocalDateTime cursorDate,
                                              @Param("cursorTxnId") Long cursorTxnId,
                                              @Param("limit") int limit);
    List<Map<String, Object>> findRecentByWalletId(@Param("walletId") String walletId,
                                                    @Param("txnFilter") String txnFilter,
                                                    @Param("limit") int limit);
    Map<String, Object> findTossPaymentByOrderId(@Param("orderId") String orderId);
    void insert(Map<String, Object> transaction);
    void insertTossPayment(Map<String, Object> transaction);
    int updateTag(@Param("txnId") String txnId,
                  @Param("category") String category,
                  @Param("petId") String petId);
}
