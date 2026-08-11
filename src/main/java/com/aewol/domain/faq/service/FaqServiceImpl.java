package com.aewol.domain.faq.service;

import com.aewol.common.exception.BusinessException;
import com.aewol.domain.faq.dto.FaqDetailResponse;
import com.aewol.domain.faq.dto.FaqResponse;
import com.aewol.domain.faq.mapper.FaqMapper;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class FaqServiceImpl implements FaqService {

    private final FaqMapper faqMapper;

    @Override
    public List<FaqResponse> getFaqs(String category, String keyword) {
        return faqMapper.findAll(category, keyword).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Override
    public FaqDetailResponse getFaq(Long faqId) {
        Map<String, Object> faq = faqMapper.findById(faqId);
        if (faq == null) {
            throw BusinessException.notFound("FAQ를 찾을 수 없어요");
        }
        return FaqDetailResponse.builder()
                .faqId(String.valueOf(faq.get("faq_id")))
                .category((String) faq.get("category"))
                .question((String) faq.get("question"))
                .answer((String) faq.get("answer"))
                .build();
    }

    private FaqResponse toResponse(Map<String, Object> faq) {
        return FaqResponse.builder()
                .faqId(String.valueOf(faq.get("faq_id")))
                .category((String) faq.get("category"))
                .question((String) faq.get("question"))
                .build();
    }
}
