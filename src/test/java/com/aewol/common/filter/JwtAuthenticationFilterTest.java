package com.aewol.common.filter;

import com.aewol.common.util.JwtUtil;
import com.aewol.domain.auth.service.AuthCredentialStore;
import com.aewol.domain.member.mapper.MemberMapper;
import io.jsonwebtoken.Claims;
import javax.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JwtAuthenticationFilterTest {

    private final JwtUtil jwtUtil = mock(JwtUtil.class);
    private final MemberMapper memberMapper = mock(MemberMapper.class);
    private final AuthCredentialStore authCredentialStore = mock(AuthCredentialStore.class);
    private final JwtAuthenticationFilter filter =
            new JwtAuthenticationFilter(jwtUtil, memberMapper, authCredentialStore);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void activeMemberIsAuthenticated() throws Exception {
        Claims claims = claims("member-1", "USER", "epoch-1");
        when(jwtUtil.isTokenValid("access-token")).thenReturn(true);
        when(jwtUtil.parseClaims("access-token")).thenReturn(claims);
        when(memberMapper.existsActiveById("member-1")).thenReturn(true);
        when(authCredentialStore.getEpoch("member-1")).thenReturn("epoch-1");

        filter.doFilter(request(), new MockHttpServletResponse(), mock(FilterChain.class));

        assertEquals("member-1", SecurityContextHolder.getContext().getAuthentication().getPrincipal());
        verify(memberMapper).existsActiveById("member-1");
    }

    @Test
    void inactiveMemberWithValidAccessTokenIsNotAuthenticated() throws Exception {
        Claims claims = claims("member-1", "USER");
        when(jwtUtil.isTokenValid("access-token")).thenReturn(true);
        when(jwtUtil.parseClaims("access-token")).thenReturn(claims);
        when(memberMapper.existsActiveById("member-1")).thenReturn(false);

        filter.doFilter(request(), new MockHttpServletResponse(), mock(FilterChain.class));

        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void dbFailureLeavesContextEmptySkipsChainAndPropagatesServerError() throws Exception {
        Claims claims = claims("member-1", "USER");
        FilterChain chain = mock(FilterChain.class);
        when(jwtUtil.isTokenValid("access-token")).thenReturn(true);
        when(jwtUtil.parseClaims("access-token")).thenReturn(claims);
        when(memberMapper.existsActiveById("member-1"))
                .thenThrow(new DataAccessResourceFailureException("db unavailable"));

        assertThrows(DataAccessResourceFailureException.class,
                () -> filter.doFilter(request(), new MockHttpServletResponse(), chain));

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(chain, never()).doFilter(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void refreshEndpointSkipsAccessTokenAuthentication() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/refresh");
        request.addHeader("Authorization", "Bearer refresh-token");
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(chain).doFilter(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verifyNoInteractions(jwtUtil, memberMapper, authCredentialStore);
    }

    @Test
    void currentEpochRequiresExactTokenClaim() throws Exception {
        Claims claims = claims("member-1", "USER", "epoch-1");
        when(jwtUtil.isTokenValid("access-token")).thenReturn(true);
        when(jwtUtil.parseClaims("access-token")).thenReturn(claims);
        when(memberMapper.existsActiveById("member-1")).thenReturn(true);
        when(authCredentialStore.getEpoch("member-1")).thenReturn("epoch-2");

        filter.doFilter(request(), new MockHttpServletResponse(), mock(FilterChain.class));

        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void matchingEpochAuthenticatesAndMissingClaimDoesNot() throws Exception {
        Claims missingEpoch = claims("member-1", "USER");
        Claims matchingEpoch = claims("member-1", "USER", "epoch-2");
        when(jwtUtil.isTokenValid("access-token")).thenReturn(true);
        when(memberMapper.existsActiveById("member-1")).thenReturn(true);
        when(authCredentialStore.getEpoch("member-1")).thenReturn("epoch-2");
        when(jwtUtil.parseClaims("access-token"))
                .thenReturn(missingEpoch, matchingEpoch);

        filter.doFilter(request(), new MockHttpServletResponse(), mock(FilterChain.class));
        assertNull(SecurityContextHolder.getContext().getAuthentication());

        filter.doFilter(request(), new MockHttpServletResponse(), mock(FilterChain.class));
        assertEquals("member-1", SecurityContextHolder.getContext().getAuthentication().getPrincipal());
    }

    @Test
    void missingRedisEpochRejectsClaimedAndLegacyTokens() throws Exception {
        Claims claimed = claims("member-1", "USER", "epoch-1");
        Claims legacy = claims("member-1", "USER");
        when(jwtUtil.isTokenValid("access-token")).thenReturn(true);
        when(memberMapper.existsActiveById("member-1")).thenReturn(true);
        when(authCredentialStore.getEpoch("member-1")).thenReturn(null);
        when(jwtUtil.parseClaims("access-token"))
                .thenReturn(claimed, legacy);

        filter.doFilter(request(), new MockHttpServletResponse(), mock(FilterChain.class));
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        filter.doFilter(request(), new MockHttpServletResponse(), mock(FilterChain.class));
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void redisFailureIsFailClosedAndStopsChain() throws Exception {
        Claims claims = claims("member-1", "USER", "epoch-1");
        FilterChain chain = mock(FilterChain.class);
        when(jwtUtil.isTokenValid("access-token")).thenReturn(true);
        when(jwtUtil.parseClaims("access-token")).thenReturn(claims);
        when(memberMapper.existsActiveById("member-1")).thenReturn(true);
        when(authCredentialStore.getEpoch("member-1"))
                .thenThrow(new RedisConnectionFailureException("unavailable"));

        assertThrows(RedisConnectionFailureException.class,
                () -> filter.doFilter(request(), new MockHttpServletResponse(), chain));

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(chain, never()).doFilter(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void accessBearerSchemeIsCaseInsensitiveButMalformedValuesAreIgnored() throws Exception {
        Claims claims = claims("member-1", "USER", "epoch-1");
        when(jwtUtil.isTokenValid("access-token")).thenReturn(true);
        when(jwtUtil.parseClaims("access-token")).thenReturn(claims);
        when(memberMapper.existsActiveById("member-1")).thenReturn(true);
        when(authCredentialStore.getEpoch("member-1")).thenReturn("epoch-1");
        for (String scheme : new String[]{"Bearer", "bearer", "BEARER", "BeArEr"}) {
            SecurityContextHolder.clearContext();
            filter.doFilter(request(scheme + " access-token"),
                    new MockHttpServletResponse(), mock(FilterChain.class));
            assertEquals("member-1", SecurityContextHolder.getContext().getAuthentication().getPrincipal());
        }

        for (String header : new String[]{"Bearer", "BearerToken value", "Basic value", "Bearer  value"}) {
            SecurityContextHolder.clearContext();
            filter.doFilter(request(header), new MockHttpServletResponse(), mock(FilterChain.class));
            assertNull(SecurityContextHolder.getContext().getAuthentication());
        }
    }

    private MockHttpServletRequest request() {
        return request("Bearer access-token");
    }

    private MockHttpServletRequest request(String authorization) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", authorization);
        return request;
    }

    private Claims claims(String memberId, String role) {
        return claims(memberId, role, null);
    }

    private Claims claims(String memberId, String role, String authEpoch) {
        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn(memberId);
        when(claims.get("role", String.class)).thenReturn(role);
        when(claims.get("authEpoch", String.class)).thenReturn(authEpoch);
        return claims;
    }
}
