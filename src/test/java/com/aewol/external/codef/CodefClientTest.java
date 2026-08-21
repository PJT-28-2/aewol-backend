package com.aewol.external.codef;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

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

    @ParameterizedTest
    @ValueSource(strings = {
            "https://development.codef.io",
            "https://development.codef.io/",
            "http://development.codef.io",
    })
    @DisplayName("데모 서버 주소에 붙어 있으면 isDemoServer()가 true다")
    void should_returnTrue_when_apiBaseUrlIsDemoServer(String apiBaseUrl) {
        assertTrue(codefClientWithApiBaseUrl(apiBaseUrl).isDemoServer());
    }

    @Test
    @DisplayName("호스트 대소문자가 달라도 데모 서버로 판정한다 - 호스트는 대소문자를 구분하지 않는다")
    void should_returnTrue_when_demoHostHasDifferentCase() {
        assertTrue(codefClientWithApiBaseUrl("https://DEVELOPMENT.CODEF.IO").isDemoServer());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://api.codef.io",
            "https://api.codef.io/",
    })
    @DisplayName("정식 서버 주소에 붙어 있으면 isDemoServer()가 false다 - 실제 1원이 오가므로 시연용 노출이 차단되어야 한다(#290)")
    void should_returnFalse_when_apiBaseUrlIsProductionServer(String apiBaseUrl) {
        assertFalse(codefClientWithApiBaseUrl(apiBaseUrl).isDemoServer());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // 데모 호스트를 접두사로 갖는 제3자 도메인. 문자열 포함(contains)으로 판정하면
            // 데모 서버로 오인해서 입금자명이 노출된다(리뷰 지적).
            "https://development.codef.io.example.com",
            "https://development.codef.io.attacker.test/v1",
            // 데모 호스트가 경로/쿼리에만 들어있는 경우도 마찬가지다.
            "https://api.codef.io/development.codef.io",
            "https://api.codef.io?host=development.codef.io",
            // 서브도메인은 다른 호스트다.
            "https://evil.development.codef.io",
    })
    @DisplayName("데모 호스트를 문자열로만 포함하는 주소는 데모 서버로 판정하지 않는다 - 호스트 정확 일치로만 판단한다")
    void should_returnFalse_when_demoHostOnlyAppearsAsSubstring(String apiBaseUrl) {
        assertFalse(codefClientWithApiBaseUrl(apiBaseUrl).isDemoServer());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // 스킴이 없으면 호스트를 뽑아낼 수 없다(URI가 경로로 해석한다).
            "development.codef.io",
            // 형식이 깨진 값.
            "ht!tp://development.codef.io",
            "   ",
    })
    @DisplayName("호스트를 해석할 수 없는 주소는 데모 서버로 판정하지 않는다 - 안전장치이므로 애매하면 닫는다")
    void should_returnFalse_when_hostCannotBeResolved(String apiBaseUrl) {
        assertFalse(codefClientWithApiBaseUrl(apiBaseUrl).isDemoServer());
    }

    @Test
    @DisplayName("api-base-url이 설정되지 않았으면 isDemoServer()가 false다 - 판단할 수 없을 땐 노출하지 않는 쪽으로 닫는다")
    void should_returnFalse_when_apiBaseUrlIsNull() {
        assertFalse(codefClientWithApiBaseUrl(null).isDemoServer());
    }

    /**
     * isDemoServer()는 apiBaseUrl 문자열만 보는 순수 판별 로직이라 협력 객체가 필요 없다.
     * 생성자 인자는 모두 null로 두고 @Value 필드만 리플렉션으로 채운다.
     */
    private static CodefClient codefClientWithApiBaseUrl(String apiBaseUrl) {
        CodefClient client = new CodefClient(null, null, null, null);
        ReflectionTestUtils.setField(client, "apiBaseUrl", apiBaseUrl);
        return client;
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
