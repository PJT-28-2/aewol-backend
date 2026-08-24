package com.aewol.domain.insurance.service;

import com.aewol.domain.insurance.dto.ClaimConfirmRequest;
import com.aewol.domain.insurance.dto.ClaimResponse;
import org.springframework.web.multipart.MultipartFile;
import java.util.List;

public interface ClaimService {
    ClaimResponse createClaim(String memberId, String petId, MultipartFile receipt);
    ClaimResponse confirmClaim(String memberId, String claimId, ClaimConfirmRequest correctedData);
    List<ClaimResponse> getClaims(String memberId);
    ClaimResponse getClaim(String memberId, String claimId);
}
