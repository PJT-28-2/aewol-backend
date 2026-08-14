package com.aewol.domain.insight.service;

import com.aewol.domain.insight.dto.HomeInsightResponse;
import java.util.List;

public interface HomeInsightService {

    /** 홈 화면에 띄울 카드들. 보여줄 데이터가 없는 카드는 빠진다. */
    List<HomeInsightResponse> getCards(String memberId, String petId);

    /** 배치 예열용. 생성한 카드 수를 돌려준다. */
    int warmUp(String memberId, String petId);
}
