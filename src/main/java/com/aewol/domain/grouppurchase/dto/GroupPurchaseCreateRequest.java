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
    @Pattern(regexp = GroupPurchaseCategory.PATTERN, message = GroupPurchaseCategory.INVALID_MESSAGE)
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

    // 타입은 LocalDateTime이지만 GroupPurchaseServiceImpl#create가 저장 시점에 항상 그날
    // 23:59:59로 덮어쓴다(is_urgent_active 자정 배치가 성립하려면 필요한 불변식). 지금은
    // 프론트가 이미 날짜만 골라 23:59:59를 붙여 보내므로 문제가 없지만, 이 타입 자체는
    // 임의 시각을 허용해서 그 사실을 드러내지 않는다 — 다른 클라이언트가 특정 시각(예: 오후
    // 6시 마감)을 의도해서 보내도 에러 없이 조용히 자정으로 밀려버린다. LocalDate로 좁히는
    // 게 더 안전한 설계지만, 프론트가 이미 전체 ISO 타임스탬프 문자열을 보내고 있어 지금
    // 바꾸면 프론트와 함께 계약을 맞춰야 한다 — 알려진 기술 부채로 남겨둔다.
    @NotNull
    @Future(message = "마감일은 현재 시각 이후여야 합니다.")
    private LocalDateTime deadline;
}
