package com.aewol.external.kakao;

import com.aewol.common.exception.BusinessException;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KakaoAuthClientTest {

    @Mock RestTemplate restTemplate;
    private KakaoAuthClient client;

    @BeforeEach
    void setUp() {
        client = new KakaoAuthClient(restTemplate);
        ReflectionTestUtils.setField(client, "clientId", "client-id");
        ReflectionTestUtils.setField(client, "clientSecret", "");
        ReflectionTestUtils.setField(client, "redirectUri", "http://localhost:5173/callback/kakao");
    }

    @Test
    void readsAccountNameAndNeverUsesProfileNickname() {
        Map<String, Object> account = new HashMap<>();
        account.put("email", "  member@example.com  ");
        account.put("name", "  홍길동  ");
        account.put("profile", Map.of("nickname", "사용하면 안 되는 닉네임"));
        stubProfile(Map.of("id", 123456789L, "kakao_account", account));

        KakaoUserInfo userInfo = client.getUserInfo("kakao-access-token");

        assertEquals("123456789", userInfo.getProviderId());
        assertEquals("member@example.com", userInfo.getEmail());
        assertEquals("홍길동", userInfo.getName());
    }

    @Test
    void missingAndBlankEmailUseSyntheticInternalEmail() {
        Map<String, Object> missingEmailAccount = new HashMap<>();
        missingEmailAccount.put("name", "홍길동");
        Map<String, Object> blankEmailAccount = new HashMap<>();
        blankEmailAccount.put("name", "홍길동");
        blankEmailAccount.put("email", "   ");
        when(restTemplate.exchange(
                anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(
                                Map.of("id", 123L, "kakao_account", missingEmailAccount),
                                HttpStatus.OK),
                        new ResponseEntity<>(
                                Map.of("id", 456L, "kakao_account", blankEmailAccount),
                                HttpStatus.OK));

        assertEquals("123@kakao.user", client.getUserInfo("token-1").getEmail());
        assertEquals("456@kakao.user", client.getUserInfo("token-2").getEmail());
    }

    @Test
    void missingAccountNameFailsClosedEvenWhenNicknameExists() {
        Map<String, Object> account = new HashMap<>();
        account.put("email", "member@example.com");
        account.put("profile", Map.of("nickname", "nickname"));
        stubProfile(Map.of("id", 123L, "kakao_account", account));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> client.getUserInfo("kakao-access-token"));

        assertEquals(HttpStatus.BAD_GATEWAY, exception.getStatus());
    }

    @Test
    void missingProviderIdFailsClosed() {
        stubProfile(Map.of("kakao_account", Map.of(
                "email", "member@example.com", "name", "홍길동")));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> client.getUserInfo("kakao-access-token"));

        assertEquals(HttpStatus.BAD_GATEWAY, exception.getStatus());
    }

    @Test
    void nonStringAccountNameFailsClosed() {
        stubProfile(Map.of("id", 123L, "kakao_account", Map.of(
                "email", "member@example.com", "name", 123)));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> client.getUserInfo("kakao-access-token"));

        assertEquals(HttpStatus.BAD_GATEWAY, exception.getStatus());
    }

    @Test
    void accessTokenResponseMustContainNonBlankToken() {
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(Map.of("access_token", "   "), HttpStatus.OK));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> client.getAccessToken("authorization-code"));

        assertEquals(HttpStatus.BAD_GATEWAY, exception.getStatus());
    }

    @Test
    void tokenApiClientErrorIsConvertedToUnauthorized() {
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(Map.class)))
                .thenThrow(new HttpClientErrorException(HttpStatus.BAD_REQUEST, "raw kakao error"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> client.getAccessToken("authorization-code"));

        assertKakaoLoginFailure(exception);
    }

    @Test
    void tokenApiServerErrorIsConvertedToServiceUnavailable() {
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(Map.class)))
                .thenThrow(new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR, "raw kakao error"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> client.getAccessToken("authorization-code"));

        assertKakaoUnavailable(exception);
    }

    @Test
    void tokenApiConnectionFailureIsConvertedToServiceUnavailable() {
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(Map.class)))
                .thenThrow(new ResourceAccessException("authorization-code timeout"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> client.getAccessToken("authorization-code"));

        assertKakaoUnavailable(exception);
    }

    @Test
    void profileApiClientErrorIsConvertedToUnauthorized() {
        when(restTemplate.exchange(
                anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenThrow(new HttpClientErrorException(HttpStatus.UNAUTHORIZED, "raw profile error"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> client.getUserInfo("kakao-access-token"));

        assertKakaoLoginFailure(exception);
    }

    @Test
    void profileApiServerErrorIsConvertedToServiceUnavailable() {
        when(restTemplate.exchange(
                anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenThrow(new HttpServerErrorException(HttpStatus.BAD_GATEWAY, "raw profile error"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> client.getUserInfo("kakao-access-token"));

        assertKakaoUnavailable(exception);
    }

    @Test
    void profileApiConnectionFailureIsConvertedToServiceUnavailable() {
        when(restTemplate.exchange(
                anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenThrow(new ResourceAccessException("kakao-access-token timeout"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> client.getUserInfo("kakao-access-token"));

        assertKakaoUnavailable(exception);
    }

    private void assertKakaoLoginFailure(BusinessException exception) {
        assertEquals(HttpStatus.UNAUTHORIZED, exception.getStatus());
        assertEquals("카카오 로그인에 실패했습니다.", exception.getMessage());
    }

    private void assertKakaoUnavailable(BusinessException exception) {
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exception.getStatus());
        assertEquals("카카오 로그인 서비스를 이용할 수 없습니다. 잠시 후 다시 시도해주세요.",
                exception.getMessage());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void stubProfile(Map<String, Object> profile) {
        when(restTemplate.exchange(
                anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity(profile, HttpStatus.OK));
    }
}
