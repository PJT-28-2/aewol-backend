package com.aewol.domain.transaction.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aewol.external.kakao.KakaoLocalClient;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AutoTaggingServiceImplTest {

    @Mock KakaoLocalClient kakaoLocalClient;

    @Test
    @DisplayName("키워드로 분류되면 카카오 로컬을 호출하지 않는다")
    void should_skipKakao_when_keywordMatches() {
        AutoTaggingServiceImpl service = new AutoTaggingServiceImpl(kakaoLocalClient);

        assertEquals("HOSPITAL", service.categorize("애월동물병원"));
        verify(kakaoLocalClient, never()).searchByKeyword("애월동물병원");
    }

    @Test
    @DisplayName("같은 가맹점명은 카카오 결과를 재사용한다")
    void should_reuseCachedKakaoCategory() {
        AutoTaggingServiceImpl service = new AutoTaggingServiceImpl(kakaoLocalClient);
        when(kakaoLocalClient.searchByKeyword("초록가게"))
                .thenReturn(Map.of("documents", List.of(Map.of("category_name", "반려동물 > 펫샵"))));

        assertEquals("FOOD", service.categorize("초록가게"));
        assertEquals("FOOD", service.categorize("초록가게"));
        verify(kakaoLocalClient).searchByKeyword("초록가게");
    }
}
