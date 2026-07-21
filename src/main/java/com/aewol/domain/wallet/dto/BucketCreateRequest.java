package com.aewol.domain.wallet.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;

@Getter
@NoArgsConstructor
public class BucketCreateRequest {
    @NotBlank
    private String bucketType;
    @NotBlank
    private String bucketName;
    private String petId;
    private BigDecimal targetAmount;
    private Boolean isSos;
}
