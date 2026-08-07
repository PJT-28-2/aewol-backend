package com.aewol.domain.certificate.service;

import com.aewol.domain.certificate.dto.ApmsSyncRequest;
import com.aewol.domain.certificate.dto.PetRegistrationCandidateResponse;
import java.util.List;

public interface CertificateService {
    List<PetRegistrationCandidateResponse> syncRegistration(String memberId, ApmsSyncRequest request);
}
