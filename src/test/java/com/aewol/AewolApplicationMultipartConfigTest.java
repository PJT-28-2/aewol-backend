package com.aewol;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * AewolApplication.readMultipartLimitBytes()가 application.yml의
 * spring.servlet.multipart.* 값을 그대로 따라가는지 확인한다.
 *
 * Spring Boot가 아니라서 DispatcherServlet 초기화 전에는 Environment로 이 값을 읽을
 * 수 없어 별도 로더로 직접 읽는데, 예전엔 이 값이 10MB로 하드코딩돼 있어서
 * application.yml을 35MB로 올려도 실제로는 반영되지 않는 불일치가 있었다
 * (2026-08-19 수정). application.yml 값이 바뀌었는데 이 테스트가 안 깨지면, 그건
 * 다시 하드코딩으로 되돌아갔다는 신호다.
 */
class AewolApplicationMultipartConfigTest {

    @Test
    @DisplayName("application.yml의 max-file-size(10MB)를 바이트로 읽는다")
    void should_readMaxFileSizeInBytes_when_yamlHas10MB() {
        long bytes = AewolApplication.readMultipartLimitBytes(
                "spring.servlet.multipart.max-file-size", "1MB");
        assertEquals(10L * 1024 * 1024, bytes);
    }

    @Test
    @DisplayName("application.yml의 max-request-size(35MB)를 바이트로 읽는다 — 1:1 문의 첨부 3개(최대 30MB) 요청이 통과할 수 있어야 한다")
    void should_readMaxRequestSizeInBytes_when_yamlHas35MB() {
        long bytes = AewolApplication.readMultipartLimitBytes(
                "spring.servlet.multipart.max-request-size", "1MB");
        assertEquals(35L * 1024 * 1024, bytes);

        long inquiryMaxAttachmentBytes = 30L * 1024 * 1024; // InquiryServiceImpl: 3개 x 10MB
        org.junit.jupiter.api.Assertions.assertTrue(bytes >= inquiryMaxAttachmentBytes);
    }

    @Test
    @DisplayName("yml에 없는 키는 기본값을 그대로 바이트로 변환한다")
    void should_useDefaultValue_when_keyMissing() {
        long bytes = AewolApplication.readMultipartLimitBytes(
                "spring.servlet.multipart.does-not-exist", "7MB");
        assertEquals(7L * 1024 * 1024, bytes);
    }
}
