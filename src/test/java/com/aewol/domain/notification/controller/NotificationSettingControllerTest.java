package com.aewol.domain.notification.controller;

import com.aewol.common.exception.GlobalExceptionHandler;
import com.aewol.common.filter.JwtAuthenticationFilter;
import com.aewol.common.util.JwtUtil;
import com.aewol.config.SecurityConfig;
import com.aewol.domain.member.mapper.MemberMapper;
import com.aewol.domain.notification.dto.NotificationSettingResponse;
import com.aewol.domain.notification.dto.NotificationSettingUpdateRequest;
import com.aewol.domain.notification.service.NotificationSettingService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.filter.CharacterEncodingFilter;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class NotificationSettingControllerTest {

    private AnnotationConfigWebApplicationContext context;
    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private NotificationSettingService service;
    private MemberMapper memberMapper;
    private JwtUtil jwtUtil;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        TestPropertySourceUtils.addInlinedPropertiesToEnvironment(
                context,
                "jwt.secret=test-secret-key-with-at-least-32-bytes",
                "jwt.access-token-expiry=1800000",
                "jwt.refresh-token-expiry=604800000");
        context.register(TestConfig.class);
        context.refresh();

        service = context.getBean(NotificationSettingService.class);
        memberMapper = context.getBean(MemberMapper.class);
        jwtUtil = context.getBean(JwtUtil.class);
        reset(service, memberMapper, jwtUtil);
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilter(new CharacterEncodingFilter("UTF-8", true))
                .apply(springSecurity())
                .build();
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    @Test
    void unauthenticatedRequestsAreBlocked() throws Exception {
        mockMvc.perform(get("/api/users/me/settings/notifications"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(patch("/api/users/me/settings/notifications")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getUsesAuthenticatedMemberAndReturnsResponseContract() throws Exception {
        stubAccessToken();
        when(service.getSettings("member-1"))
                .thenReturn(new NotificationSettingResponse(true, true, false, true, false));

        MvcResult result = mockMvc.perform(get("/api/users/me/settings/notifications")
                        .header("Authorization", "Bearer access-token"))
                .andExpect(status().isOk()).andReturn();

        JsonNode json = json(result);
        assertEquals(200, json.get("status").asInt());
        assertEquals("알림 설정을 조회했습니다.", json.get("message").asText());
        assertEquals(true, json.get("result").get("paymentEnabled").asBoolean());
        assertEquals(false, json.get("result").get("familyShareEnabled").asBoolean());
        assertEquals(false, json.get("result").get("marketingEnabled").asBoolean());
        verify(service).getSettings("member-1");
    }

    @Test
    void patchKeepsFalseDistinctFromMissingAndReturnsFinalSettings() throws Exception {
        stubAccessToken();
        when(service.updateSettings(
                org.mockito.ArgumentMatchers.eq("member-1"),
                org.mockito.ArgumentMatchers.any(NotificationSettingUpdateRequest.class)))
                .thenReturn(new NotificationSettingResponse(true, true, true, false, false));

        MvcResult result = mockMvc.perform(patch("/api/users/me/settings/notifications")
                        .header("Authorization", "Bearer access-token")
                        .contentType("application/json")
                        .content("{\"communityEnabled\":false,\"marketingEnabled\":false}"))
                .andExpect(status().isOk()).andReturn();

        JsonNode json = json(result);
        assertEquals(200, json.get("status").asInt());
        assertEquals("알림 설정이 변경되었습니다.", json.get("message").asText());
        assertEquals(false, json.get("result").get("communityEnabled").asBoolean());
        ArgumentCaptor<NotificationSettingUpdateRequest> captor =
                ArgumentCaptor.forClass(NotificationSettingUpdateRequest.class);
        verify(service).updateSettings(org.mockito.ArgumentMatchers.eq("member-1"), captor.capture());
        assertEquals(false, captor.getValue().getCommunityEnabled());
        assertEquals(false, captor.getValue().getMarketingEnabled());
        assertEquals(null, captor.getValue().getPaymentEnabled());
    }

    private void stubAccessToken() {
        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn("member-1");
        when(claims.get("role", String.class)).thenReturn("USER");
        when(claims.getIssuedAt()).thenReturn(new Date(2_000_000L));
        when(jwtUtil.isTokenValid("access-token")).thenReturn(true);
        when(jwtUtil.parseClaims("access-token")).thenReturn(claims);
        when(jwtUtil.isAccessToken(claims)).thenReturn(true);
        Map<String, Object> state = new HashMap<>();
        state.put("is_active", 1);
        when(memberMapper.findAuthStateById("member-1")).thenReturn(state);
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    @EnableWebMvc
    @Import(SecurityConfig.class)
    static class TestConfig {
        @Bean NotificationSettingService notificationSettingService() {
            return mock(NotificationSettingService.class);
        }
        @Bean MemberMapper memberMapper() {
            return mock(MemberMapper.class);
        }
        @Bean JwtUtil jwtUtil() {
            return mock(JwtUtil.class);
        }
        @Bean JwtAuthenticationFilter jwtAuthenticationFilter(JwtUtil jwtUtil, MemberMapper memberMapper) {
            return new JwtAuthenticationFilter(jwtUtil, memberMapper);
        }
        @Bean NotificationSettingController notificationSettingController(NotificationSettingService service) {
            return new NotificationSettingController(service);
        }
        @Bean GlobalExceptionHandler globalExceptionHandler() {
            return new GlobalExceptionHandler();
        }
    }
}
