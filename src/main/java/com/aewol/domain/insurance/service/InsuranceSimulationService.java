package com.aewol.domain.insurance.service;

import com.aewol.domain.insurance.dto.SimulationRequest;
import com.aewol.domain.insurance.dto.SimulationResponse;

public interface InsuranceSimulationService {
    SimulationResponse simulate(SimulationRequest request);
}
