package com.aewol.domain.insurance.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;
import java.util.Map;

@Mapper
public interface InsuranceMapper {
    void insertSimulation(Map<String, Object> simulation);
    void insertClaim(Map<String, Object> claim);
    void updateClaim(Map<String, Object> claim);
    Map<String, Object> findClaimById(@Param("claimId") String claimId);
    List<Map<String, Object>> findClaimsByMemberId(@Param("memberId") String memberId);
}
