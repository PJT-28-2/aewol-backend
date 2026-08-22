package com.aewol.domain.notification.controller;

import com.aewol.common.exception.GlobalExceptionHandler;
import com.aewol.common.filter.JwtAuthenticationFilter;
import com.aewol.common.filter.MemberAuthStateCache;
import com.aewol.common.util.JwtUtil;
import com.aewol.config.SecurityConfig;
import com.aewol.domain.member.mapper.MemberMapper;
import com.aewol.domain.notification.dto.NotificationListResponse;
import com.aewol.domain.notification.dto.NotificationResponse;
import com.aewol.domain.notification.service.NotificationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

class NotificationControllerTest {

    private AnnotationConfigWebApplicationContext context;
    private MockMvc mockMvc;
    private NotificationService service;
    private MemberMapper memberMapper;
    private JwtUtil jwtUtil;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context,
                "jwt.secret=test-secret-key-with-at-least-32-bytes",
                "jwt.access-token-expiry=1800000", "jwt.refresh-token-expiry=604800000");
        context.register(TestConfig.class);
        context.refresh();
        service = context.getBean(NotificationService.class);
        memberMapper = context.getBean(MemberMapper.class);
        jwtUtil = context.getBean(JwtUtil.class);
        reset(service, memberMapper, jwtUtil);
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilter(new CharacterEncodingFilter("UTF-8", true))
                .apply(springSecurity()).build();
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    @Test
    void allInboxEndpointsRequireAuthentication() throws Exception {
        mockMvc.perform(get("/api/users/me/notifications")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/users/me/notifications/unread-count")).andExpect(status().isUnauthorized());
        mockMvc.perform(patch("/api/users/me/notifications/1/read")).andExpect(status().isUnauthorized());
        mockMvc.perform(patch("/api/users/me/notifications/read-all")).andExpect(status().isUnauthorized());
    }

    @Test
    void listUsesAuthenticatedMemberAndPagination() throws Exception {
        stubAccessToken();
        NotificationResponse item = NotificationResponse.builder()
                .notificationId("7").type("PAYMENT").title("결제 완료").message("결제가 완료됐어요.")
                .read(false).createdAt("2026-08-20T12:00:00").build();
        when(service.getNotifications("member-1", 1, 5)).thenReturn(NotificationListResponse.builder()
                .notifications(List.of(item)).unreadCount(3).hasNext(true).page(1).size(5).build());

        MvcResult result = mockMvc.perform(get("/api/users/me/notifications?page=1&size=5")
                        .header("Authorization", "Bearer access-token"))
                .andExpect(status().isOk()).andReturn();

        JsonNode json = json(result).get("result");
        assertEquals(3, json.get("unreadCount").asInt());
        assertEquals("7", json.get("notifications").get(0).get("notificationId").asText());
        verify(service).getNotifications("member-1", 1, 5);
    }

    @Test
    void readEndpointsAndUnreadCountUseAuthenticatedMember() throws Exception {
        stubAccessToken();
        when(service.getUnreadCount("member-1")).thenReturn(4);

        MvcResult countResult = mockMvc.perform(get("/api/users/me/notifications/unread-count")
                        .header("Authorization", "Bearer access-token"))
                .andExpect(status().isOk()).andReturn();
        mockMvc.perform(patch("/api/users/me/notifications/9/read")
                        .header("Authorization", "Bearer access-token"))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/api/users/me/notifications/read-all")
                        .header("Authorization", "Bearer access-token"))
                .andExpect(status().isOk());

        assertEquals(4, json(countResult).get("result").get("unreadCount").asInt());
        verify(service).markAsRead("member-1", "9");
        verify(service).markAllAsRead("member-1");
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
        @Bean NotificationService notificationService() { return mock(NotificationService.class); }
        @Bean MemberMapper memberMapper() { return mock(MemberMapper.class); }
        @Bean JwtUtil jwtUtil() { return mock(JwtUtil.class); }
        @Bean JwtAuthenticationFilter jwtAuthenticationFilter(JwtUtil jwtUtil, MemberMapper memberMapper) {
            return new JwtAuthenticationFilter(jwtUtil, MemberAuthStateCache.withoutCache(memberMapper));
        }
        @Bean NotificationController notificationController(NotificationService service) {
            return new NotificationController(service);
        }
        @Bean GlobalExceptionHandler globalExceptionHandler() { return new GlobalExceptionHandler(); }
    }
}
