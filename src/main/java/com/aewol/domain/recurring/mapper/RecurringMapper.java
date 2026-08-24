package com.aewol.domain.recurring.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;
import java.util.Map;

@Mapper
public interface RecurringMapper {
    Map<String, Object> findByIdForUpdate(@Param("recurringId") String recurringId);
    List<Map<String, Object>> findByWalletId(@Param("walletId") String walletId);
    void insert(Map<String, Object> recurring);
    int update(Map<String, Object> recurring);
    int updateNextPaymentDate(@Param("recurringId") String recurringId,
                              @Param("nextPaymentDate") java.time.LocalDate nextPaymentDate);
    int deactivate(@Param("recurringId") String recurringId);

    /**
     * 펫 등록해제(#291) 전용. transaction.recurring_id가 recurring_payment를 ON DELETE
     * NO ACTION으로 참조하고 있어서, 이미 결제가 한 번이라도 실행된 정기결제는 하드 삭제하면
     * FK 위반으로 실패한다. 그래서 삭제 대신 비활성화해 향후 결제만 막고 이력은 보존한다.
     * 호출 전 소유권 검증(assertOwner 등)이 선행돼야 한다 — 이 메서드는 petId만으로 매칭한다.
     */
    int deactivateByPetId(@Param("petId") String petId);

    List<Map<String, Object>> findDuePayments(@Param("date") String date);

    /** 결제일 3일 전 미리 알림용. next_payment_date가 그날인 활성 정기결제만 본다. */
    List<Map<String, Object>> findUpcomingPayments(@Param("date") String date);
}
