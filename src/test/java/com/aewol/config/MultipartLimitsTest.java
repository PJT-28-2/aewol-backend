package com.aewol.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * AewolApplication이 Tomcat에 직접 설정하는 멀티파트 제한값이 application.yml과
 * 어긋나지 않는지 확인한다(PR #197 리뷰 반영).
 *
 * <p>Spring Boot가 아니라서 spring.servlet.multipart.* 값이 자동 반영되지 않고,
 * MultipartLimits가 application.yml을 직접 읽어와야만 두 곳이 실제로 동기화된다.
 * 실제로 이전에는 max-request-size가 10MB로 하드코딩돼 있어 문서화된 35MB(1:1 문의
 * 첨부 3개 지원용)와 어긋나 있었다.
 *
 * <p>Tomcat을 직접 띄워 실제 HTTP 요청이 413으로 거부되는지 확인하는 통합 테스트는
 * 이 프로젝트의 기존 테스트들(MockMvc + AppConfig 기반)로는 검증할 수 없다 — MockMvc는
 * 컨테이너 레벨의 멀티파트 크기 제한을 우회하기 때문이다. 대신 이 테스트는 실제로
 * 적용되는 값 자체가 문서화된 정책(파일당 10MB, 최대 3개)을 만족하는지 확인해서 같은
 * 종류의 회귀를 막는다.
 */
class MultipartLimitsTest {

    // api_명세서.md: 1:1 문의 첨부는 파일당 최대 10MB, 최대 3개까지 허용
    private static final long EXPECTED_MAX_FILE_SIZE = 10L * 1024 * 1024;
    private static final int MAX_ATTACHMENTS = 3;

    @Test
    @DisplayName("application.yml의 max-file-size를 그대로 읽어온다")
    void should_readMaxFileSize_fromApplicationYml() {
        assertEquals(EXPECTED_MAX_FILE_SIZE, MultipartLimits.maxFileSizeBytes());
    }

    @Test
    @DisplayName("max-request-size는 첨부 3개(각 최대 10MB)를 합친 크기 이상이어야 한다")
    void should_haveMaxRequestSize_thatCoversMaxAttachments() {
        long maxRequestSize = MultipartLimits.maxRequestSizeBytes();
        long minimumRequired = EXPECTED_MAX_FILE_SIZE * MAX_ATTACHMENTS;

        assertTrue(maxRequestSize >= minimumRequired,
                "max-request-size(" + maxRequestSize + "바이트)가 첨부 " + MAX_ATTACHMENTS
                        + "개 분(" + minimumRequired + "바이트)보다 작습니다. application.yml을 확인하세요.");
    }
}
