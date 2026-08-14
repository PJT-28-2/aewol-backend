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
     * <p>두 경계는 통계적 주장이 아니라 <b>무의미한 입력을 걸러내는 장치</b>다. 다만
     * 임의의 배수가 아니라 인용 가능한 수치에 앵커했다(이슈 #178에서 재산정).</p>
     *
     * <p><b>하한 10,000원</b> — 농림축산식품부 2024 동물병원 진료비 현황조사(4,159개소)의
     * 초진 진찰료 평균이 10,291원이다. 즉 연 1회 진찰 수준이며, 그보다 낮은 값은
     * "의료비를 거의 쓰지 않았다"는 뜻이라 보험 비교 자체가 무의미하다. 특히 0원은
     * 예상 보장금을 0으로 만들어 전 상품을 기계적으로 UNFAVORABLE로 뒤집는다.</p>
     *
     * <p><b>상한 10,000,000원</b> — 시드 상품의 의료비 담보 연간한도가 1,000만~4,000만원
     * 이므로, 최저 담보한도와 같은 1,000만원을 넘는 연 의료비는 어떤 상품으로도 전액
     * 보장이 불가능하다. 기본 가정치(444,000원)의 약 22배이기도 하다. 직전 값
     * 50,000,000원은 통계 확정 전의 잠정치였다.</p>
     *
     * <p>프론트 슬라이더는 이보다 좁은 100,000~3,000,000원을 UX 범위로 쓴다.
     * 검증 경계보다 좁은 것은 의도된 설계다 — 슬라이더는 흔한 구간만 다루고,
     * 그 밖의 값은 API를 직접 쓰는 경우에만 들어온다.</p>
     */
    @Min(value = 10_000, message = "annualMedicalCostKrw는 10,000원 이상이어야 합니다.")
    @Max(value = 10_000_000, message = "annualMedicalCostKrw는 10,000,000원 이하여야 합니다.")
    private Long annualMedicalCostKrw;
}
