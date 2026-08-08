package com.aewol.domain.faq.service;

import com.aewol.domain.faq.dto.FaqDetailResponse;
import com.aewol.domain.faq.dto.FaqResponse;
import java.util.List;

public interface FaqService {
    List<FaqResponse> getFaqs(String category, String keyword);
    FaqDetailResponse getFaq(String faqId);
}
