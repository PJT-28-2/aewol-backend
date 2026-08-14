package com.aewol.domain.insight.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface HomeInsightMapper {

    /** 아직 만료되지 않은 카드만 돌려준다. */
    List<Map<String, Object>> findFreshByMemberId(@Param("memberId") String memberId);

    void upsert(Map<String, Object> insight);

    /** 회원 탈퇴 등으로 캐시를 비울 때. */
    int deleteByMemberId(@Param("memberId") String memberId);

    /**
     * 배치 예열 대상. 반려동물이 있는 활성 회원만 고른다 — 카드 4종이 모두
     * 반려동물이나 지갑 데이터를 전제로 하기 때문이다.
     */
    List<String> findWarmUpTargetMemberIds();
}
