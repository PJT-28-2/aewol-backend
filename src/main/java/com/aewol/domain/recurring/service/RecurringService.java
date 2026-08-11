package com.aewol.domain.recurring.service;

import com.aewol.domain.recurring.dto.RecurringCreateRequest;
import com.aewol.domain.recurring.dto.RecurringResponse;
import java.util.List;

public interface RecurringService {
    List<RecurringResponse> getRecurringPayments(String memberId);
    RecurringResponse createRecurring(String memberId, RecurringCreateRequest request);
    RecurringResponse updateRecurring(String memberId, String recurringId, RecurringCreateRequest request);
    void cancelRecurring(String memberId, String recurringId);
}
