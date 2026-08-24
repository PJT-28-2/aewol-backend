package com.aewol.domain.transaction.service;

import com.aewol.external.kakao.KakaoLocalClient;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Slf4j
@Service
public class AutoTaggingServiceImpl implements AutoTaggingService {

    static final int MAX_CACHE_SIZE = 500;
    static final Duration CACHE_TTL = Duration.ofHours(24);

    private final KakaoLocalClient kakaoLocalClient;
    private final Clock clock;
    private final ConcurrentHashMap<String, CacheEntry> categoryByMerchant = new ConcurrentHashMap<>();

    public AutoTaggingServiceImpl(KakaoLocalClient kakaoLocalClient) {
        this(kakaoLocalClient, Clock.systemUTC());
    }

    AutoTaggingServiceImpl(KakaoLocalClient kakaoLocalClient, Clock clock) {
        this.kakaoLocalClient = kakaoLocalClient;
        this.clock = clock;
    }

    @Override
    public String categorize(String merchantName) {
        if (!StringUtils.hasText(merchantName)) {
            return "ETC";
        }
        String key = merchantName.trim();
        String cached = getCached(key);
        if (cached != null) {
            return cached;
        }

        String keywordCategory = categorizeByKeyword(key);
        if (!"ETC".equals(keywordCategory)) {
            putCached(key, keywordCategory);
            return keywordCategory;
        }

        String kakaoCategory = categorizeByKakao(key);
        putCached(key, kakaoCategory);
        return kakaoCategory;
    }

    private String getCached(String key) {
        CacheEntry entry = categoryByMerchant.get(key);
        if (entry == null) {
            return null;
        }
        if (entry.expiresAtMillis <= clock.millis()) {
            categoryByMerchant.remove(key, entry);
            return null;
        }
        return entry.category;
    }

    private void putCached(String key, String category) {
        evictIfOverCapacity();
        categoryByMerchant.put(key, new CacheEntry(category, clock.millis() + CACHE_TTL.toMillis()));
    }

    /**
     * 서로 다른 가맹점명을 계속 보내 맵이 커지는 것을 막는다.
     * 한도에 닿으면 만료분을 지우고, 그래도 가득이면 비운 뒤 이번 값만 다시 담는다.
     */
    private void evictIfOverCapacity() {
        if (categoryByMerchant.size() < MAX_CACHE_SIZE) {
            return;
        }
        long now = clock.millis();
        categoryByMerchant.entrySet().removeIf(entry -> entry.getValue().expiresAtMillis <= now);
        if (categoryByMerchant.size() >= MAX_CACHE_SIZE) {
            categoryByMerchant.clear();
        }
    }

    int cacheSize() {
        return categoryByMerchant.size();
    }

    private String categorizeByKeyword(String merchantName) {
        String lower = merchantName.toLowerCase();
        if (lower.contains("병원") || lower.contains("의료") || lower.contains("클리닉")) return "HOSPITAL";
        if (lower.contains("사료") || lower.contains("간식") || lower.contains("푸드")) return "FOOD";
        if (lower.contains("미용") || lower.contains("그루밍") || lower.contains("목욕")) return "GROOMING";
        if (lower.contains("용품") || lower.contains("장난감") || lower.contains("펫샵")) return "TOY";
        return "ETC";
    }

    private String categorizeByKakao(String merchantName) {
        try {
            Map<String, Object> result = kakaoLocalClient.searchByKeyword(merchantName);
            List<Map<String, Object>> documents = (List<Map<String, Object>>) result.get("documents");
            if (documents == null || documents.isEmpty()) {
                return "ETC";
            }
            String categoryName = (String) documents.get(0).get("category_name");
            if (categoryName == null) {
                return "ETC";
            }
            if (categoryName.contains("동물병원") || categoryName.contains("수의")) {
                return "HOSPITAL";
            }
            if (categoryName.contains("펫") || categoryName.contains("사료") || categoryName.contains("반려")) {
                return "FOOD";
            }
            if (categoryName.contains("미용") || categoryName.contains("그루밍")) {
                return "GROOMING";
            }
            if (categoryName.contains("용품") || categoryName.contains("장난감")) {
                return "TOY";
            }
        } catch (Exception e) {
            log.warn("카카오 로컬 API 검색 실패: {}", e.getMessage());
        }
        return "ETC";
    }

    private static final class CacheEntry {
        private final String category;
        private final long expiresAtMillis;

        private CacheEntry(String category, long expiresAtMillis) {
            this.category = category;
            this.expiresAtMillis = expiresAtMillis;
        }
    }
}
