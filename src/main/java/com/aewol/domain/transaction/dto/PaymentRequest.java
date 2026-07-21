package com.aewol.domain.transaction.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;

@Getter
@NoArgsConstructor
public class PaymentRequest {
    @NotBlank
    private String merchantName;
    @NotNull
    private BigDecimal amount;
    private String petId;
    private String memo;
}
