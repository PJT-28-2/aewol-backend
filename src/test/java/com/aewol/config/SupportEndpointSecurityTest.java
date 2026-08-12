package com.aewol.config;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * {@code /api/support} 는 지원정책(SupportController)과 고객센터(FAQ·1:1 문의)가
 * prefix 를 나눠 쓴다. 공개 규칙을 {@code /api/support/**} 로 넓게 잡으면 개인 기록인
 * 1:1 문의까지 인증 없이 통과한다.
 *
 * <p>당시에는 조회가 anonymousUser 기준으로 필터되어 빈 결과가 나갔을 뿐이라 유출은
 * 없었지만, 인증 실패가 200 으로 위장되고 회원 필터 없는 GET 이 추가되는 순간 그대로
 * 공개된다. 규칙이 다시 넓어지지 않도록 고정한다(#138).
 */
@ActiveProfiles("test")
@SpringJUnitWebConfig(classes = AppConfig.class)
class SupportEndpointSecurityTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
    }

    @Test
    @DisplayName("1:1 문의 목록은 인증 없이 조회할 수 없다")
    void should_rejectInquiryList_when_notAuthenticated() throws Exception {
        mockMvc.perform(get("/api/support/inquiries"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("1:1 문의 상세는 인증 없이 조회할 수 없다")
    void should_rejectInquiryDetail_when_notAuthenticated() throws Exception {
        mockMvc.perform(get("/api/support/inquiries/1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("맞춤 지원정책은 회원 기준 매칭이라 인증 없이 조회할 수 없다")
    void should_rejectMatchedPrograms_when_notAuthenticated() throws Exception {
        mockMvc.perform(get("/api/support"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/support/matched"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("FAQ는 공개 콘텐츠라 인증 없이도 보안 필터를 통과한다")
    void should_allowFaq_when_notAuthenticated() throws Exception {
        // 이 테스트 컨텍스트에는 FAQ 데이터를 담을 DB가 없어 컨트롤러까지 도달한 뒤
        // 조회에서 실패한다. 여기서 확인할 것은 조회 결과가 아니라 '보안 필터가
        // 막지 않는다'는 것이므로 401/403이 아님만 단언한다.
        int status = mockMvc.perform(get("/api/support/faqs"))
                .andReturn()
                .getResponse()
                .getStatus();

        assertNotEquals(401, status, "FAQ가 인증을 요구하면 안 된다");
        assertNotEquals(403, status, "FAQ가 권한 부족으로 막히면 안 된다");
    }
}
