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

    List<Map<String, Object>> findProductsBySpecies(@Param("species") String species);
    List<Map<String, Object>> findCoveragesByProductIds(@Param("productIds") List<Long> productIds);
    List<Map<String, Object>> findPlanTiersByProductIds(@Param("productIds") List<Long> productIds);

    /**
     * 상품별 "견적 기준 티어"(is_reference_tier=1)만 조회한다. 환급률/자기부담금/
     * 연간한도/출처/신뢰도의 진실 원천이 insurance_product로부터 insurance_product_
     * plan_tiers로 이전됨에 따라(V28), 손익분기 계산은 이 결과를 사용해야 한다.
     * 상품당 최대 1행만 반환된다(무결성 쿼리 11로 보장).
     */
    List<Map<String, Object>> findReferenceTiersByProductIds(@Param("productIds") List<Long> productIds);
}
