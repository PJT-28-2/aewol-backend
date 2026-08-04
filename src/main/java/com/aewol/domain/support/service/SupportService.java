package com.aewol.domain.support.service;

import com.aewol.domain.support.dto.SupportProgramsResponse;

public interface SupportService {
    SupportProgramsResponse getMatchedPrograms(String memberId, String petId);
    void markApplyPageOpened(String memberId, String programId, String petId);
}
