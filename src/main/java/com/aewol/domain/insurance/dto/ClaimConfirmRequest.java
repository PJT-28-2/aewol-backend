package com.aewol.domain.insurance.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.Digits;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.PastOrPresent;
import javax.validation.constraints.Size;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.jackson.Jacksonized;

/** 보험 청구 확인 단계에서 사용자가 최종 확인한 값만 받는다. */
@Getter
@Builder
@Jacksonized
public class ClaimConfirmRequest {

    @NotBlank(message = "병원명은 필수입니다.")
    @Size(max = 100, message = "병원명은 100자 이하여야 합니다.")
    private String hospitalName;

    @NotNull(message = "진료일은 필수입니다.")
    @PastOrPresent(message = "진료일은 오늘 이후일 수 없습니다.")
    private LocalDate treatmentDate;

    @NotNull(message = "청구 금액은 필수입니다.")
    @DecimalMin(value = "0.01", message = "청구 금액은 0보다 커야 합니다.")
    @Digits(integer = 13, fraction = 2, message = "청구 금액 형식이 올바르지 않습니다.")
    private BigDecimal totalAmount;
}
