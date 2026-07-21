package com.aewol.domain.insurance.service;

import com.aewol.domain.insurance.dto.SimulationRequest;
import com.aewol.domain.insurance.dto.SimulationResponse;
import com.aewol.domain.insurance.mapper.InsuranceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

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

        String simId = UUID.randomUUID().toString();
        Map<String, Object> sim = new HashMap<>();
        sim.put("simId", simId);
        sim.put("petId", request.getPetId());
        sim.put("breed", request.getBreed());
        sim.put("age", request.getAge());
        sim.put("medicalHistory", request.getMedicalHistory());
        sim.put("estimatedAnnualCost", estimatedCost);
        sim.put("premium", premium);
        sim.put("deductible", deductible);
        sim.put("breakEvenYear", breakEvenYear);
        sim.put("recommendation", recommendation);
        insuranceMapper.insertSimulation(sim);

        return SimulationResponse.builder()
                .simId(simId).breed(request.getBreed()).age(request.getAge())
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
