package com.aewol.domain.faq.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.aewol.common.exception.BusinessException;
import com.aewol.domain.faq.dto.FaqDetailResponse;
import com.aewol.domain.faq.dto.FaqResponse;
import com.aewol.domain.faq.mapper.FaqMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FaqServiceImplTest {

    @Mock FaqMapper faqMapper;
    @InjectMocks FaqServiceImpl service;

    private static final String FAQ_ID = "1";

    @Test
    @DisplayName("category/keyword 없이 조회하면 매퍼에 null로 그대로 전달한다")
    void should_passThroughNullFilters_when_noParamsGiven() {
        when(faqMapper.findAll(null, null)).thenReturn(List.of(faqRow(FAQ_ID, "계좌연동", "질문")));

        List<FaqResponse> result = service.getFaqs(null, null);

        assertEquals(1, result.size());
        assertEquals("질문", result.get(0).getQuestion());
        verify(faqMapper).findAll(null, null);
    }

    @Test
    @DisplayName("FAQ 상세 조회 시 question/answer를 모두 반환한다")
    void should_returnQuestionAndAnswer_when_faqExists() {
        Map<String, Object> row = faqRow(FAQ_ID, "저금·버킷", "버킷은 몇 개까지 만들 수 있나요?");
        row.put("answer", "반려동물·카테고리별로 제한 없이 자유롭게 생성할 수 있어요.");
        when(faqMapper.findById(FAQ_ID)).thenReturn(row);

        FaqDetailResponse result = service.getFaq(FAQ_ID);

        assertEquals("저금·버킷", result.getCategory());
        assertEquals("반려동물·카테고리별로 제한 없이 자유롭게 생성할 수 있어요.", result.getAnswer());
    }

    @Test
    @DisplayName("존재하지 않는 FAQ를 조회하면 404 예외를 던진다")
    void should_throwNotFound_when_faqDoesNotExist() {
        when(faqMapper.findById(FAQ_ID)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.getFaq(FAQ_ID));

        assertEquals(org.springframework.http.HttpStatus.NOT_FOUND, ex.getStatus());
    }

    private Map<String, Object> faqRow(String faqId, String category, String question) {
        Map<String, Object> row = new HashMap<>();
        row.put("faq_id", faqId);
        row.put("category", category);
        row.put("question", question);
        return row;
    }
}
