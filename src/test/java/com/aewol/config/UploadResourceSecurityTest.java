package com.aewol.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@ActiveProfiles("test")
@SpringJUnitWebConfig(classes = AppConfig.class)
class UploadResourceSecurityTest {

    private static final Path UPLOAD_ROOT = Path.of(
            System.getProperty("java.io.tmpdir"), "aewol-upload-security-" + UUID.randomUUID());

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @DynamicPropertySource
    static void uploadProperties(DynamicPropertyRegistry registry) {
        registry.add("file.upload-dir", () -> UPLOAD_ROOT.toString());
    }

    @BeforeAll
    static void createFiles() throws IOException {
        Files.createDirectories(UPLOAD_ROOT.resolve("diary"));
        Files.createDirectories(UPLOAD_ROOT.resolve("group-purchase"));
        Files.writeString(UPLOAD_ROOT.resolve("diary/private.png"), "private", StandardCharsets.UTF_8);
        Files.writeString(UPLOAD_ROOT.resolve("group-purchase/public.png"), "public", StandardCharsets.UTF_8);
    }

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
    }

    @AfterAll
    static void deleteFiles() throws IOException {
        if (!Files.exists(UPLOAD_ROOT)) {
            return;
        }
        try (var paths = Files.walk(UPLOAD_ROOT)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    throw new IllegalStateException("테스트 업로드 파일을 정리하지 못했습니다.", e);
                }
            });
        }
    }

    @Test
    @DisplayName("인증 사용자도 비공개 업로드 파일을 /uploads 경로로 직접 조회할 수 없다")
    void should_notServePrivateUpload_whenAuthenticatedUserKnowsKey() throws Exception {
        mockMvc.perform(get("/uploads/diary/private.png")
                        .with(user("member-1").roles("USER")))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("공동구매 상품 이미지도 더 이상 /uploads 경로로 직접 조회할 수 없다")
    void should_notServeGroupPurchaseImageDirectly() throws Exception {
        // 저장소를 S3로 옮기면서(#203) 로컬 디스크를 정적 서빙하던 유일한 예외를 없앴다.
        // 공동구매 이미지도 다른 업로드 파일과 같이 서명 URL로만 조회한다.
        mockMvc.perform(get("/uploads/group-purchase/public.png")
                        .with(user("member-1").roles("USER")))
                .andExpect(status().isNotFound());
    }
}
