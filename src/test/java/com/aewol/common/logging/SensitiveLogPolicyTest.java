package com.aewol.common.logging;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SensitiveLogPolicyTest {

    private static final List<String> FORBIDDEN_LOG_FRAGMENTS = List.of(
            "raw: {}",
            "원본 응답",
            "paymentKey: {}",
            "paymentKey={}",
            "입금자명 = {}",
            "account: {}",
            "merchant: {}",
            "merchantName: {}",
            "fileUrl: {}",
            "HttpMessageNotReadableException: {}"
    );

    @Test
    @DisplayName("운영 소스의 로그 문구에 외부 원문과 사용자·금융 식별자를 직접 출력하지 않는다")
    void productionLogsMustNotContainSensitivePayloadPatterns() throws IOException {
        try (var sources = Files.walk(Path.of("src/main/java"))) {
            for (Path source : sources.filter(path -> path.toString().endsWith(".java")).toList()) {
                String content = Files.readString(source);
                for (String forbidden : FORBIDDEN_LOG_FRAGMENTS) {
                    assertFalse(content.contains(forbidden),
                            () -> source + " contains forbidden log fragment: " + forbidden);
                }
            }
        }
    }
}
