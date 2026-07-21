package com.aewol.domain.support.service;

import java.util.List;
import java.util.Map;

public interface SupportService {
    List<Map<String, Object>> getPrograms(String region);
    List<Map<String, Object>> getMatchedPrograms(String memberId);
}
