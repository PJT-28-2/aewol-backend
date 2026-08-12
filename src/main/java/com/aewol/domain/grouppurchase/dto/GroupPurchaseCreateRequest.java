package com.aewol.domain.grouppurchase.dto;

import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.Future;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class GroupPurchaseCreateRequest {

    @NotBlank
    private String productName;

    @NotBlank
    @Pattern(regexp = "사료|간식|용품|기타", message = "카테고리는 사료, 간식, 용품, 기타 중 하나여야 합니다.")
    private String category;

    private String image;

    @NotNull
    @DecimalMin(value = "0.01", message = "정가는 0보다 커야 합니다.")
    private BigDecimal unitPrice;

    @NotNull
    @DecimalMin(value = "0.01", message = "공동구매 가격은 0보다 커야 합니다.")
    private BigDecimal groupPrice;

    private String deliveryMethod;

    @DecimalMin(value = "0.00", message = "배송비는 0 이상이어야 합니다.")
    private BigDecimal deliveryFee;

    @Min(value = 1, message = "배송 예상 소요일은 1일 이상이어야 합니다.")
    private Integer deliveryEstimateDays;

    private String description;

    @NotNull
    @Min(value = 1, message = "목표 수량은 1개 이상이어야 합니다.")
    private Integer targetQuantity;

    @NotNull
    @Future(message = "마감일은 현재 시각 이후여야 합니다.")
    private LocalDateTime deadline;
}
