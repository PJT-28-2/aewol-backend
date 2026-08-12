package com.aewol.domain.member.controller;

import com.aewol.common.exception.GlobalExceptionHandler;
import com.aewol.common.filter.JwtAuthenticationFilter;
import com.aewol.common.util.JwtUtil;
import com.aewol.config.SecurityConfig;
import com.aewol.domain.member.dto.MemberResponse;
import com.aewol.domain.member.mapper.MemberMapper;
import com.aewol.domain.member.service.MemberService;
import com.aewol.domain.member.service.SimplePasswordVerificationService;
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
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MemberProfileSecurityTest {

    private AnnotationConfigWebApplicationContext context;
    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private MemberService memberService;
    private SimplePasswordVerificationService simplePasswordVerificationService;
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

        memberService = context.getBean(MemberService.class);
        simplePasswordVerificationService = context.getBean(SimplePasswordVerificationService.class);
        memberMapper = context.getBean(MemberMapper.class);
        jwtUtil = context.getBean(JwtUtil.class);
        reset(memberService, simplePasswordVerificationService, memberMapper, jwtUtil);
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    @Test
    void unauthenticatedProfileEndpointsAreBlocked() throws Exception {
        mockMvc.perform(get("/api/users/me")).andExpect(status().isUnauthorized());
        mockMvc.perform(patch("/api/users/me").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/users/me/password/verify")
                        .contentType("application/json").content("{\"currentPassword\":\"password\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(patch("/api/users/me/password")
                        .contentType("application/json")
                        .content("{\"currentPassword\":\"password\",\"newPassword\":\"new-password\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/users/simple-password/verify")
                        .contentType("application/json").content("{\"password\":\"123456\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void accessTokenCanVerifySimplePasswordWithoutChangingIt() throws Exception {
        stubAccessToken(true);
        when(simplePasswordVerificationService.verify("member-1", "123456")).thenReturn(true);

        MvcResult result = mockMvc.perform(post("/api/users/simple-password/verify")
                        .header("Authorization", "Bearer access-token")
                        .contentType("application/json")
                        .content("{\"password\":\"123456\"}"))
                .andExpect(status().isOk()).andReturn();

        assertEquals(true, json(result).get("result").get("verified").asBoolean());
        verify(simplePasswordVerificationService).verify("member-1", "123456");
        verify(memberService, never()).setSimplePassword(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void simplePasswordVerificationRejectsInvalidFormat() throws Exception {
        stubAccessToken(true);

        mockMvc.perform(post("/api/users/simple-password/verify")
                        .header("Authorization", "Bearer access-token")
                        .contentType("application/json")
                        .content("{\"password\":\"12345a\"}"))
                .andExpect(status().isBadRequest());

        verify(simplePasswordVerificationService, never()).verify(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void accessTokenUsesPrincipalAndReturnsCompleteProfileContract() throws Exception {
        stubAccessToken(true);
        when(memberService.getMember("member-1")).thenReturn(profile("LOCAL"));

        MvcResult result = mockMvc.perform(get("/api/users/me")
                        .header("Authorization", "Bearer access-token"))
                .andExpect(status().isOk()).andReturn();

        JsonNode response = json(result).get("result");
        assertEquals("member-1", response.get("memberId").asText());
        assertEquals("user@aewol.com", response.get("email").asText());
        assertEquals("홍길동", response.get("name").asText());
        assertEquals("01012345678", response.get("phone").asText());
        assertEquals("profile.jpg", response.get("profileImg").asText());
        assertEquals("LOCAL", response.get("provider").asText());
        assertEquals("12345", response.get("zipCode").asText());
        assertEquals("제주시 애월읍", response.get("address").asText());
        assertEquals("101호", response.get("addressDetail").asText());
        verify(memberService).getMember("member-1");

        reset(memberService);
        when(memberService.getMember("member-1")).thenReturn(profile("KAKAO"));
        MvcResult kakao = mockMvc.perform(get("/api/users/me")
                        .header("Authorization", "Bearer access-token"))
                .andExpect(status().isOk()).andReturn();
        assertEquals("KAKAO", json(kakao).get("result").get("provider").asText());
    }

    @Test
    void accessTokenCanPatchAndPasswordSuccessResponsesContainNullResult() throws Exception {
        stubAccessToken(true);

        MvcResult profile = mockMvc.perform(patch("/api/users/me")
                        .header("Authorization", "Bearer access-token")
                        .contentType("application/json")
                        .content("{\"phone\":\"010-1234-5678\"}"))
                .andExpect(status().isOk()).andReturn();
        assertNullResult(profile);

        MvcResult verifyPassword = mockMvc.perform(post("/api/users/me/password/verify")
                        .header("Authorization", "Bearer access-token")
                        .contentType("application/json")
                        .content("{\"currentPassword\":\"current-password\"}"))
                .andExpect(status().isOk()).andReturn();
        assertNullResult(verifyPassword);

        MvcResult changePassword = mockMvc.perform(patch("/api/users/me/password")
                        .header("Authorization", "Bearer access-token")
                        .contentType("application/json")
                        .content("{\"currentPassword\":\"current-password\",\"newPassword\":\"new-password\"}"))
                .andExpect(status().isOk()).andReturn();
        assertNullResult(changePassword);

        ArgumentCaptor<com.aewol.domain.member.dto.MemberUpdateRequest> profileCaptor =
                ArgumentCaptor.forClass(com.aewol.domain.member.dto.MemberUpdateRequest.class);
        verify(memberService).updateMember(org.mockito.ArgumentMatchers.eq("member-1"), profileCaptor.capture());
        assertEquals("010-1234-5678", profileCaptor.getValue().getPhone());
        verify(memberService).verifyPassword(
                org.mockito.ArgumentMatchers.eq("member-1"), org.mockito.ArgumentMatchers.any());
        verify(memberService).changePassword(
                org.mockito.ArgumentMatchers.eq("member-1"), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void passwordDtoValidationRejectsBlankAndInvalidLengths() throws Exception {
        stubAccessToken(true);

        mockMvc.perform(post("/api/users/me/password/verify")
                        .header("Authorization", "Bearer access-token")
                        .contentType("application/json").content("{\"currentPassword\":\"   \"}"))
                .andExpect(status().isBadRequest());
        assertInvalidPasswordChange("", "new-password");
        assertInvalidPasswordChange("current-password", "");
        assertInvalidPasswordChange("current-password", "1234567");
        assertInvalidPasswordChange("current-password", "123456789012345678901");

        verify(memberService, never()).verifyPassword(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
        verify(memberService, never()).changePassword(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void refreshTokenAndInactiveAccessTokenCannotReachProfileService() throws Exception {
        stubRefreshToken();
        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer refresh-token"))
                .andExpect(status().isUnauthorized());
        verify(memberService, never()).getMember(org.mockito.ArgumentMatchers.anyString());

        reset(jwtUtil, memberMapper, memberService);
        stubAccessToken(false);
        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer access-token"))
                .andExpect(status().isUnauthorized());
        verify(memberService, never()).getMember(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void oldMembersEndpointNoLongerExists() throws Exception {
        stubAccessToken(true);
        mockMvc.perform(get("/api/members/me").header("Authorization", "Bearer access-token"))
                .andExpect(status().isNotFound());
    }

    private void assertInvalidPasswordChange(String currentPassword, String newPassword) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "currentPassword", currentPassword, "newPassword", newPassword));
        mockMvc.perform(patch("/api/users/me/password")
                        .header("Authorization", "Bearer access-token")
                        .contentType("application/json").content(body))
                .andExpect(status().isBadRequest());
    }

    private void assertNullResult(MvcResult result) throws Exception {
        JsonNode json = json(result);
        assertEquals(200, json.get("status").asInt());
        assertEquals("success", json.get("message").asText());
        assertEquals(true, json.get("result").isNull());
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private MemberResponse profile(String provider) {
        return MemberResponse.builder()
                .memberId("member-1").email("user@aewol.com").name("홍길동")
                .phone("01012345678").profileImg("profile.jpg").provider(provider)
                .zipCode("12345").address("제주시 애월읍").addressDetail("101호").build();
    }

    private void stubAccessToken(boolean active) {
        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn("member-1");
        when(claims.get("role", String.class)).thenReturn("USER");
        when(claims.getIssuedAt()).thenReturn(new Date(2_000_000L));
        when(jwtUtil.isTokenValid("access-token")).thenReturn(true);
        when(jwtUtil.parseClaims("access-token")).thenReturn(claims);
        when(jwtUtil.isAccessToken(claims)).thenReturn(true);
        when(memberMapper.findAuthStateById("member-1")).thenReturn(authState(active));
    }

    private void stubRefreshToken() {
        Claims claims = mock(Claims.class);
        when(jwtUtil.isTokenValid("refresh-token")).thenReturn(true);
        when(jwtUtil.parseClaims("refresh-token")).thenReturn(claims);
        when(jwtUtil.isAccessToken(claims)).thenReturn(false);
    }

    private Map<String, Object> authState(boolean active) {
        Map<String, Object> state = new HashMap<>();
        state.put("is_active", active ? 1 : 0);
        return state;
    }

    @Configuration
    @EnableWebMvc
    @Import(SecurityConfig.class)
    static class TestConfig {

        @Bean
        MemberService memberService() {
            return mock(MemberService.class);
        }

        @Bean
        SimplePasswordVerificationService simplePasswordVerificationService() {
            return mock(SimplePasswordVerificationService.class);
        }

        @Bean
        MemberMapper memberMapper() {
            return mock(MemberMapper.class);
        }

        @Bean
        JwtUtil jwtUtil() {
            return mock(JwtUtil.class);
        }

        @Bean
        JwtAuthenticationFilter jwtAuthenticationFilter(JwtUtil jwtUtil, MemberMapper memberMapper) {
            return new JwtAuthenticationFilter(jwtUtil, memberMapper);
        }

        @Bean
        MemberController memberController(MemberService memberService,
                                          SimplePasswordVerificationService simplePasswordVerificationService) {
            return new MemberController(memberService, simplePasswordVerificationService);
        }

        @Bean
        GlobalExceptionHandler globalExceptionHandler() {
            return new GlobalExceptionHandler();
        }
    }
}
