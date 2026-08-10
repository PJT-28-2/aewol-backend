package com.aewol.domain.transaction.dto;

import java.util.List;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class TransactionPageResponse {
    private List<TransactionResponse> transactions;
    private String nextCursor;
}
