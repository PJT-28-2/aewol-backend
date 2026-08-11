package com.aewol.domain.recurring.dto;

import java.math.BigDecimal;
import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.Digits;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class RecurringCreateRequest {

    @NotBlank(message = "상품명을 입력해 주세요.")
    @Size(max = 100, message = "상품명은 100자 이하만 입력할 수 있습니다.")
    private String itemName;

    @NotNull(message = "결제 금액을 입력해 주세요.")
    @DecimalMin(value = "1", message = "결제 금액은 1원 이상이어야 합니다.")
    @Digits(integer = 13, fraction = 0, message = "결제 금액은 정수만 입력할 수 있습니다.")
    private BigDecimal price;

    @NotNull(message = "결제일을 선택해 주세요.")
    @Min(value = 1, message = "결제일은 1~31 사이여야 합니다.")
    @Max(value = 31, message = "결제일은 1~31 사이여야 합니다.")
    private Integer cycleDay;

    @NotBlank(message = "카테고리를 선택해 주세요.")
    @Pattern(regexp = "MEDICAL|GROOMING|FOOD|SUPPLIES|ETC",
            message = "지원하지 않는 카테고리입니다.")
    private String category;

    private String petId;
}
