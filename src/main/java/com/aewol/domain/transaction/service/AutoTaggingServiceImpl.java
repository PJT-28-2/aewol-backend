package com.aewol.domain.transaction.service;

import com.aewol.external.kakao.KakaoLocalClient;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Slf4j
@Service
@RequiredArgsConstructor
public class AutoTaggingServiceImpl implements AutoTaggingService {

    private final KakaoLocalClient kakaoLocalClient;
    private final ConcurrentHashMap<String, String> categoryByMerchant = new ConcurrentHashMap<>();

    @Override
    public String categorize(String merchantName) {
        if (!StringUtils.hasText(merchantName)) {
            return "ETC";
        }
        String key = merchantName.trim();
        String cached = categoryByMerchant.get(key);
        if (cached != null) {
            return cached;
        }

        String keywordCategory = categorizeByKeyword(key);
        if (!"ETC".equals(keywordCategory)) {
            categoryByMerchant.put(key, keywordCategory);
            return keywordCategory;
        }

        String kakaoCategory = categorizeByKakao(key);
        categoryByMerchant.put(key, kakaoCategory);
        return kakaoCategory;
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
}
