package com.aewol.domain.insight.service.collector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.aewol.domain.insight.service.InsightCard;
import com.aewol.domain.support.dto.SupportProgramResponse;
import com.aewol.domain.support.dto.SupportProgramsResponse;
import com.aewol.domain.support.service.SupportService;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SupportInsightCollectorTest {

    @Mock SupportService supportService;
    @InjectMocks SupportInsightCollector collector;

    private void givenPetName(String petName) {
        SupportProgramResponse program = SupportProgramResponse.builder()
                .id("support-1")
                .title("반려동물 의료비 지원")
                .eligible(true)
                .applied(false)
                .build();
        when(supportService.getMatchedPrograms("m1", "p1")).thenReturn(
                SupportProgramsResponse.builder()
                        .petId("p1")
                        .petName(petName)
                        .programs(List.of(program))
                        .build());
    }

    @Test
    @DisplayName("받침이 있는 이름 뒤에는 '이'를 붙인다")
    void should_useI_when_petNameHasFinalConsonant() {
        givenPetName("황칠복");

        InsightCard card = collector.collect("m1", "p1");

        assertEquals("황칠복이 받을 수 있는 지원 1건", card.getHeadline());
        assertTrue(card.getFallbackBody().startsWith("황칠복이 지금 신청할 수 있는"));
    }

    @Test
    @DisplayName("받침이 없는 이름 뒤에는 '가'를 붙인다")
    void should_useGa_when_petNameHasNoFinalConsonant() {
        givenPetName("보리");

        InsightCard card = collector.collect("m1", "p1");

        assertEquals("보리가 받을 수 있는 지원 1건", card.getHeadline());
        assertTrue(card.getFallbackBody().startsWith("보리가 지금 신청할 수 있는"));
    }
}
