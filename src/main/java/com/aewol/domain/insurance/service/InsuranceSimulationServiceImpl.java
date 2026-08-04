package com.aewol.domain.insurance.service;

import com.aewol.domain.insurance.dto.SimulationRequest;
import com.aewol.domain.insurance.dto.SimulationResponse;
import com.aewol.domain.insurance.mapper.InsuranceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class InsuranceSimulationServiceImpl implements InsuranceSimulationService {

    private final InsuranceMapper insuranceMapper;

    @Override
    public SimulationResponse simulate(SimulationRequest request) {
        // 품종/나이 기반 예상 의료비 계산 (시드 데이터 or 통계)
        BigDecimal estimatedCost = calculateEstimatedCost(request.getBreed(), request.getAge());
        BigDecimal premium = estimatedCost.multiply(BigDecimal.valueOf(0.06));
        BigDecimal deductible = estimatedCost.multiply(BigDecimal.valueOf(0.2));
        int breakEvenYear = premium.compareTo(BigDecimal.ZERO) > 0
                ? estimatedCost.divide(premium, 0, java.math.RoundingMode.CEILING).intValue()
                : 0;
        String recommendation = breakEvenYear <= 3 ? "RECOMMENDED" :
                breakEvenYear <= 5 ? "NEUTRAL" : "NOT_RECOMMENDED";

        // V2 스키마: 단일값 대신 판정(verdict)/메시지만 저장, breed/age는 pet에서 유도
        String verdict = "RECOMMENDED".equals(recommendation) ? "FAVORABLE"
                : "NOT_RECOMMENDED".equals(recommendation) ? "UNFAVORABLE" : "NEUTRAL";
        Map<String, Object> sim = new HashMap<>();
        sim.put("petId", request.getPetId());
        sim.put("medicalHistoryCodes", request.getMedicalHistory());
        sim.put("verdict", verdict);
        sim.put("message", "예상 손익분기 " + breakEvenYear + "년 기준 " + recommendation);
        insuranceMapper.insertSimulation(sim); // sim_id AUTO_INCREMENT

        return SimulationResponse.builder()
                .simId(String.valueOf(sim.get("simId"))).breed(request.getBreed()).age(request.getAge())
                .estimatedAnnualCost(estimatedCost).premium(premium)
                .deductible(deductible).breakEvenYear(breakEvenYear)
                .recommendation(recommendation).build();
    }

    private BigDecimal calculateEstimatedCost(String breed, int age) {
        BigDecimal base = BigDecimal.valueOf(500000);
        if (age >= 7) base = base.multiply(BigDecimal.valueOf(1.8));
        else if (age >= 3) base = base.multiply(BigDecimal.valueOf(1.2));
        return base;
    }
}
