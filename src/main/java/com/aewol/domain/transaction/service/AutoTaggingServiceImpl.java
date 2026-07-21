package com.aewol.domain.transaction.service;

import com.aewol.external.gemini.GeminiVisionClient;
import com.aewol.external.kakao.KakaoLocalClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class AutoTaggingServiceImpl implements AutoTaggingService {

    private final KakaoLocalClient kakaoLocalClient;
    private final GeminiVisionClient geminiVisionClient;

    @Override
    public String categorize(String merchantName) {
        // 1차: 카카오 로컬 API 키워드 검색
        try {
            Map<String, Object> result = kakaoLocalClient.searchByKeyword(merchantName);
            List<Map<String, Object>> documents = (List<Map<String, Object>>) result.get("documents");

            if (documents != null && !documents.isEmpty()) {
                Map<String, Object> first = documents.get(0);
                String categoryName = (String) first.get("category_name");

                if (categoryName != null) {
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
                }
            }
        } catch (Exception e) {
            log.warn("카카오 로컬 API 검색 실패: {}", e.getMessage());
        }

        // 2차: 키워드 기반 분류
        String lower = merchantName.toLowerCase();
        if (lower.contains("병원") || lower.contains("의료") || lower.contains("클리닉")) return "HOSPITAL";
        if (lower.contains("사료") || lower.contains("간식") || lower.contains("푸드")) return "FOOD";
        if (lower.contains("미용") || lower.contains("그루밍") || lower.contains("목욕")) return "GROOMING";
        if (lower.contains("용품") || lower.contains("장난감") || lower.contains("펫샵")) return "TOY";

        // 3차: Gemini API 보조 분류
        try {
            String geminiResult = geminiVisionClient.classifyMerchant(merchantName);
            if (!"ETC".equals(geminiResult)) {
                return geminiResult;
            }
        } catch (Exception e) {
            log.warn("Gemini 분류 실패: {}", e.getMessage());
        }

        return "ETC";
    }
}
