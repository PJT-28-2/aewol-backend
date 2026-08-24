package com.aewol.domain.transaction.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aewol.external.kakao.KakaoLocalClient;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
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

    @Test
    @DisplayName("TTL이 지난 캐시는 다시 카카오를 호출한다")
    void should_refreshCache_when_ttlExpires() {
        AtomicLong millis = new AtomicLong(1_000L);
        Clock clock = clockFrom(millis);
        AutoTaggingServiceImpl service = new AutoTaggingServiceImpl(kakaoLocalClient, clock);
        when(kakaoLocalClient.searchByKeyword("초록가게"))
                .thenReturn(Map.of("documents", List.of(Map.of("category_name", "반려동물 > 펫샵"))));

        assertEquals("FOOD", service.categorize("초록가게"));
        millis.addAndGet(AutoTaggingServiceImpl.CACHE_TTL.toMillis() + 1);
        assertEquals("FOOD", service.categorize("초록가게"));

        verify(kakaoLocalClient, times(2)).searchByKeyword("초록가게");
    }

    @Test
    @DisplayName("서로 다른 가맹점명이 한도를 넘으면 캐시 크기를 되돌린다")
    void should_boundCacheSize_when_distinctMerchantsKeepComing() {
        AutoTaggingServiceImpl service = new AutoTaggingServiceImpl(kakaoLocalClient);

        for (int i = 0; i < AutoTaggingServiceImpl.MAX_CACHE_SIZE; i++) {
            assertEquals("HOSPITAL", service.categorize("병원" + i));
        }
        assertEquals(AutoTaggingServiceImpl.MAX_CACHE_SIZE, service.cacheSize());
        assertEquals("HOSPITAL", service.categorize("병원-overflow"));
        assertEquals(1, service.cacheSize());
    }

    private static Clock clockFrom(AtomicLong millis) {
        return new Clock() {
            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return Instant.ofEpochMilli(millis.get());
            }
        };
    }
}
