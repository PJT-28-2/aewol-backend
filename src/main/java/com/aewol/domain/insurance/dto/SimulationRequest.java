package com.aewol.domain.insurance.dto;

import com.aewol.domain.insurance.dto.validation.AllowedMedicalHistoryCode;
import java.util.List;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 검증 메시지는 반드시 한글로 명시한다. message를 생략하면 Hibernate Validator의
 * 기본 번역이 나가는데, 그 번역은 요청의 Accept-Language에 따라 달라진다
 * (헤더 없음/ko → 한글, en-US → 영어, ja-JP → 일본어). 커스텀 제약
 * {@link AllowedMedicalHistoryCode}는 한글로 고정되어 있어, 생략하면 한 응답 안에서
 * 언어가 섞인다. 같은 도메인의 InsuranceController도 한글 메시지를 명시하고 있다.
 */
@Getter
@NoArgsConstructor
public class SimulationRequest {
    @NotBlank(message = "petId는 필수입니다.")
    private String petId;

    @NotEmpty(message = "medicalHistoryCodes는 비어 있을 수 없습니다. 병력이 없으면 [\"NONE\"]을 보내세요.")
    private List<@NotBlank(message = "병력 코드는 비어 있을 수 없습니다.")
            @AllowedMedicalHistoryCode String> medicalHistoryCodes;

    /**
     * 사용자가 조정한 연간 예상 의료비(원). null이면 서버 기본 상수를 사용한다.
     *
     * <p>하한은 0이 아니라 10,000원이다 — 0원은 예상 보장금을 0으로 만들어 전 상품을
     * 기계적으로 UNFAVORABLE로 뒤집는, 의미 없는 입력이기 때문이다(프론트 슬라이더는
     * 더 좁은 100,000원을 UX 하한으로 쓴다). 상한은 S2(마리당 연 의료비 통계) 확정
     * 전까지의 잠정 안전장치다.</p>
     * TODO(S2): 통계 확정 후 하한·상한을 상식적 배수로 재산정할 것.
     */
    @Min(value = 10_000, message = "annualMedicalCostKrw는 10,000원 이상이어야 합니다.")
    @Max(value = 50_000_000, message = "annualMedicalCostKrw는 50,000,000원 이하여야 합니다.")
    private Long annualMedicalCostKrw;
}
