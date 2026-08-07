package com.aewol.external.codef;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 후보 풀이 좁으면(수식어x명사 378가지) brute-force에 취약하다는 CodeRabbit 지적(2026-08-07)에
 * 따라 완성형 한글 음절 전체(가~힣) 범위에서 4글자를 뽑도록 바꾼 randomDepositorName()을 검증한다.
 * private static 메서드라 리플렉션으로 직접 호출한다(외부 HTTP 호출이 없는 순수 함수라
 * CodefClient 빈 생성 없이도 테스트 가능).
 */
class CodefClientTest {

    private static final int SAMPLE_SIZE = 500;

    @Test
    @DisplayName("입금자명은 항상 4글자이고, 각 글자는 완성형 한글(가~힣) 범위 안에 있다")
    void should_generateFourValidHangulSyllables() throws Exception {
        Method method = CodefClient.class.getDeclaredMethod("randomDepositorName");
        method.setAccessible(true);

        for (int i = 0; i < SAMPLE_SIZE; i++) {
            String name = (String) method.invoke(null);
            assertEquals(4, name.length());
            for (char c : name.toCharArray()) {
                assertTrue(c >= '가' && c <= '힣', "완성형 한글 범위를 벗어난 글자: " + c);
            }
        }
    }

    @Test
    @DisplayName("후보 공간이 넓어서 표본 500개 안에서 중복이 사실상 발생하지 않는다")
    void should_produceHighlyVariedValues() throws Exception {
        Method method = CodefClient.class.getDeclaredMethod("randomDepositorName");
        method.setAccessible(true);

        Set<String> generated = new HashSet<>();
        for (int i = 0; i < SAMPLE_SIZE; i++) {
            generated.add((String) method.invoke(null));
        }

        // 옛 378개 후보 풀이었다면 500번 뽑을 때 중복이 거의 확실했다.
        // 11172^4 공간에서는 500개를 뽑아도 중복이 없는 게 사실상 확실하다.
        assertEquals(SAMPLE_SIZE, generated.size());
    }
}
