package com.aewol.external.codef;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * randomDepositorName()은 2026-08-07엔 brute-force 방어를 위해 완성형 한글 음절 전체
 * 범위(가~힣, 11172^4가지) 무작위 방식이었지만, "궭뛟밝꿁" 같은 임의 조합은 사용자가 읽고
 * 옮겨 적기 어렵다는 피드백(2026-08-11, 민주)으로 형용사+명사 자연어 조합(ADJECTIVES x
 * NOUNS)으로 되돌렸다. private static 멤버라 리플렉션으로 직접 접근한다(외부 HTTP 호출이
 * 없는 순수 함수라 CodefClient 빈 생성 없이도 테스트 가능).
 */
class CodefClientTest {

    private static final int SAMPLE_SIZE = 500;

    @Test
    @DisplayName("입금자명은 항상 4글자이고, 각 글자는 완성형 한글(가~힣) 범위 안에 있다")
    void should_generateFourValidHangulSyllables() throws Exception {
        Method method = randomDepositorNameMethod();

        for (int i = 0; i < SAMPLE_SIZE; i++) {
            String name = (String) method.invoke(null);
            assertEquals(4, name.length());
            for (char c : name.toCharArray()) {
                assertTrue(c >= '가' && c <= '힣', "완성형 한글 범위를 벗어난 글자: " + c);
            }
        }
    }

    @Test
    @DisplayName("입금자명은 항상 ADJECTIVES 중 하나 + NOUNS 중 하나로 분해된다")
    void should_decomposeIntoKnownAdjectiveAndNoun() throws Exception {
        Method method = randomDepositorNameMethod();
        Set<String> adjectives = new HashSet<>(Arrays.asList(wordPool("ADJECTIVES")));
        Set<String> nouns = new HashSet<>(Arrays.asList(wordPool("NOUNS")));

        for (int i = 0; i < SAMPLE_SIZE; i++) {
            String name = (String) method.invoke(null);
            assertTrue(adjectives.contains(name.substring(0, 2)), "형용사 목록에 없는 접두부: " + name);
            assertTrue(nouns.contains(name.substring(2, 4)), "명사 목록에 없는 접미부: " + name);
        }
    }

    @Test
    @DisplayName("단어풀 조합 수가 25회 추측(30분 5회 요청 x confirm 5회) 대비 충분히 넓다")
    void should_haveWordPoolLargeEnoughForBruteForceSafety() throws Exception {
        int adjectiveCount = wordPool("ADJECTIVES").length;
        int nounCount = wordPool("NOUNS").length;
        long combinations = (long) adjectiveCount * nounCount;

        // 계좌당 30분 5회 요청 x confirm 5회 오답 허용 = 최대 25회 추측(AccountServiceImpl 정책).
        // 예전 378가지 조합(25/378 ≈ 6.6%)이 CodeRabbit 지적을 받았던 걸 감안해, 성공 확률을
        // 1% 밑으로 유지할 수 있는 최소 조합 수(2,500가지) 이상은 항상 보장되도록 한다.
        assertTrue(combinations >= 2_500,
                "단어풀 조합이 너무 좁아 brute-force에 취약합니다: " + combinations + "가지");
    }

    @Test
    @DisplayName("단어풀 모든 항목은 정확히 2글자다 (입금자명 4자 고정 UI가 깨지지 않도록)")
    void should_haveOnlyTwoCharacterWords() throws Exception {
        for (String word : wordPool("ADJECTIVES")) {
            assertEquals(2, word.length(), "ADJECTIVES 항목이 2글자가 아닙니다: " + word);
        }
        for (String word : wordPool("NOUNS")) {
            assertEquals(2, word.length(), "NOUNS 항목이 2글자가 아닙니다: " + word);
        }
    }

    @Test
    @DisplayName("표본 500개 중 대부분(90% 이상)이 서로 달라 특정 조합에 쏠리지 않는다")
    void should_produceHighlyVariedValues() throws Exception {
        Method method = randomDepositorNameMethod();

        Set<String> generated = new HashSet<>();
        for (int i = 0; i < SAMPLE_SIZE; i++) {
            generated.add((String) method.invoke(null));
        }

        // 조합 수가 8,000가지 안팎이라 500번 뽑으면 birthday paradox로 소량의 중복은
        // 통계적으로 자연스럽다(기대 고유값 약 485개) — 옛 테스트처럼 "중복 0개"를
        // 기대하면 새 구현에서 항상 실패하므로, 넉넉한 하한선(450개, 90%)으로 검증한다.
        assertTrue(generated.size() >= 450,
                "고유 조합 수가 예상보다 너무 적습니다: " + generated.size());
    }

    private static Method randomDepositorNameMethod() throws NoSuchMethodException {
        Method method = CodefClient.class.getDeclaredMethod("randomDepositorName");
        method.setAccessible(true);
        return method;
    }

    private static String[] wordPool(String fieldName) throws Exception {
        Field field = CodefClient.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (String[]) field.get(null);
    }
}
