package com.aewol.domain.insight.service.collector;

import com.aewol.domain.insight.service.InsightCard;
import com.aewol.domain.insight.service.InsightCardCollector;
import com.aewol.domain.insight.service.InsightCardType;
import com.aewol.domain.support.dto.SupportProgramResponse;
import com.aewol.domain.support.dto.SupportProgramsResponse;
import com.aewol.domain.support.service.SupportService;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 정부24에서 동기화한 지원사업 중 조건이 맞는 건을 안내한다.
 *
 * <p>원본이 "benefit: 서비스(의료)||현금", "period: 접수기관 별 상이" 같은 행정 문구라
 * 그대로 보여주면 읽히지 않는다. 이 카드가 LLM 을 쓰는 이유가 여기에 있다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SupportInsightCollector implements InsightCardCollector {

    /** 프롬프트에 넣을 사업 수. 전부 넣으면 토큰만 늘고 문장은 뭉개진다. */
    private static final int PROMPT_LIMIT = 6;

    private final SupportService supportService;

    @Override
    public InsightCardType type() {
        return InsightCardType.SUPPORT;
    }

    @Override
    public InsightCard collect(String memberId, String petId) {
        SupportProgramsResponse matched;
        try {
            matched = supportService.getMatchedPrograms(memberId, petId);
        } catch (RuntimeException e) {
            log.debug("[Insight] 지원정책 조회 실패 - memberId: {}, reason: {}", memberId, e.getMessage());
            return null;
        }
        if (matched == null || matched.getPrograms() == null) {
            return null;
        }

        List<SupportProgramResponse> eligible = matched.getPrograms().stream()
                .filter(SupportProgramResponse::isEligible)
                .filter(program -> !program.isApplied())
                .toList();
        if (eligible.isEmpty()) {
            return null;
        }

        String petName = matched.getPetName() == null ? "반려동물" : matched.getPetName();
        String facts = eligible.stream()
                .limit(PROMPT_LIMIT)
                .map(program -> "- %s (%s) / 기한: %s / 지원: %s".formatted(
                        program.getTitle(),
                        nullToDash(program.getAgency()),
                        nullToDash(program.getPeriod()),
                        nullToDash(program.getBenefit())))
                .collect(Collectors.joining("\n"));

        SupportProgramResponse first = eligible.get(0);
        return InsightCard.builder()
                .type(type())
                .headline("%s가 받을 수 있는 지원 %d건".formatted(petName, eligible.size()))
                .facts("""
                        반려동물: %s
                        지금 신청 조건을 충족하는 지원사업 %d건(전체 %d건 중):
                        %s"""
                        .formatted(petName, eligible.size(), matched.getPrograms().size(), facts))
                .fallbackBody("%s가 지금 신청할 수 있는 지원사업이 %d건 있어요. '%s'부터 확인해 보세요."
                        .formatted(petName, eligible.size(), first.getTitle()))
                .ctaLabel("전체 보기")
                .ctaPath("/support-programs")
                .digest(eligible.size() + ":" + first.getId())
                .build();
    }

    private static String nullToDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
