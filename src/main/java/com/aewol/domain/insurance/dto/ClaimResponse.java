package com.aewol.domain.insurance.dto;

import lombok.Builder;
import lombok.Getter;
import java.math.BigDecimal;

@Getter
@Builder
public class ClaimResponse {
    private String claimId;
    private String petId;
    private String hospitalName;
    private String treatmentDate;
    private BigDecimal totalAmount;
    private String claimStatus;
    private String claimDocumentUrl;
    private String receiptImageUrl;
    private Object extractedData;
}
