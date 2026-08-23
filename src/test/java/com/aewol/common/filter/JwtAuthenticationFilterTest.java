package com.aewol.common.filter;

import com.aewol.common.cache.MemberAuthStateCache;
import com.aewol.common.util.JwtUtil;
import com.aewol.domain.member.mapper.MemberMapper;
import io.jsonwebtoken.Claims;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import javax.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class JwtAuthenticationFilterTest {

    private static final long WITHDRAWN_AT = 1_000L;
    private final JwtUtil jwtUtil = mock(JwtUtil.class);
    private final MemberMapper memberMapper = mock(MemberMapper.class);
    private final JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwtUtil, MemberAuthStateCache.withoutCache(memberMapper));

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void activeMemberWithoutWithdrawalHistoryIsAuthenticated() throws Exception {
        stubToken(new Date(900_000L));
        when(memberMapper.findAuthStateById("member-1")).thenReturn(member(true, null));

        filter.doFilter(request(), new MockHttpServletResponse(), mock(FilterChain.class));

        assertEquals("member-1", SecurityContextHolder.getContext().getAuthentication().getPrincipal());
    }

    @Test
    void dbRoleOverridesStaleJwtClaim() throws Exception {
        stubToken(new Date(900_000L), "ADMIN");
        Map<String, Object> member = member(true, null);
        member.put("role", "USER");
        when(memberMapper.findAuthStateById("member-1")).thenReturn(member);

        filter.doFilter(request(), new MockHttpServletResponse(), mock(FilterChain.class));

        assertEquals("ROLE_USER",
                SecurityContextHolder.getContext().getAuthentication().getAuthorities().iterator().next().getAuthority());
    }

    @Test
    void inactiveMemberIsNotAuthenticated() throws Exception {
        stubToken(new Date(1_100_000L));
        when(memberMapper.findAuthStateById("member-1")).thenReturn(member(false, WITHDRAWN_AT));

        filter.doFilter(request(), new MockHttpServletResponse(), mock(FilterChain.class));

        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void recoveredMemberRejectsTokenIssuedBeforeWithdrawal() throws Exception {
        assertAuthenticationForRecoveredMember(999L, false);
    }

    @Test
    void recoveredMemberRejectsTokenIssuedAtWithdrawal() throws Exception {
        assertAuthenticationForRecoveredMember(WITHDRAWN_AT, false);
    }

    @Test
    void recoveredMemberAcceptsTokenIssuedAfterWithdrawal() throws Exception {
        assertAuthenticationForRecoveredMember(1_001L, true);
    }

    @Test
    void missingIssuedAtFailsClosed() throws Exception {
        stubToken(null);
        when(memberMapper.findAuthStateById("member-1")).thenReturn(member(true, null));

        filter.doFilter(request(), new MockHttpServletResponse(), mock(FilterChain.class));

        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void missingMemberFailsClosed() throws Exception {
        stubToken(new Date(1_100_000L));
        when(memberMapper.findAuthStateById("member-1")).thenReturn(null);

        filter.doFilter(request(), new MockHttpServletResponse(), mock(FilterChain.class));

        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void refreshTokenDoesNotCreateAuthentication() throws Exception {
        stubRejectedToken("refresh-token");

        filter.doFilter(request("refresh-token"), new MockHttpServletResponse(), mock(FilterChain.class));

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verifyNoInteractions(memberMapper);
    }

    @Test
    void tokenWithoutTypeDoesNotCreateAuthentication() throws Exception {
        stubRejectedToken("legacy-token");

        filter.doFilter(request("legacy-token"), new MockHttpServletResponse(), mock(FilterChain.class));

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verifyNoInteractions(memberMapper);
    }

    @Test
    void unknownTokenTypeDoesNotCreateAuthentication() throws Exception {
        stubRejectedToken("unknown-token");

        filter.doFilter(request("unknown-token"), new MockHttpServletResponse(), mock(FilterChain.class));

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verifyNoInteractions(memberMapper);
    }

    @Test
    void dbFailureStopsChainAndPropagates() throws Exception {
        stubToken(new Date(1_100_000L));
        FilterChain chain = mock(FilterChain.class);
        when(memberMapper.findAuthStateById("member-1"))
                .thenThrow(new DataAccessResourceFailureException("db unavailable"));

        assertThrows(DataAccessResourceFailureException.class,
                () -> filter.doFilter(request(), new MockHttpServletResponse(), chain));
        verify(chain, never()).doFilter(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void refreshEndpointSkipsAccessAuthentication() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/refresh");
        request.addHeader("Authorization", "Bearer refresh-token");
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        verify(chain).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verifyNoInteractions(jwtUtil, memberMapper);
    }

    /**
     * 캐시를 실제로 태워서 인증이 통과하는지 본다.
     *
     * <p>다른 테스트는 모두 {@code withoutCache}라 Redis 경로를 한 번도 밟지 않았다.
     * 그 틈에서 캐시가 {@code is_active}를 문자열로 되돌리는 버그가 그대로 통과했다 —
     * 예외도 없이 비활성으로 읽혀, 캐시 미스인 첫 요청만 되고 TTL 동안 전부 401이 된다.
     * 캐시를 거친 경로도 DB 경로와 똑같이 인증돼야 한다.
     */
    @Test
    void cachedAuthStateAuthenticatesJustLikeDatabase() throws Exception {
        @SuppressWarnings("unchecked")
        RedisTemplate<String, String> redisTemplate = mock(RedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        MemberAuthStateCache cache = new MemberAuthStateCache(redisTemplate, memberMapper);
        JwtAuthenticationFilter cachedFilter = new JwtAuthenticationFilter(jwtUtil, cache);

        // 첫 요청: 캐시가 비어 DB를 보고 채운다.
        stubToken(new Date(900_000L));
        when(valueOperations.get("auth:state:member-1")).thenReturn(null);
        when(memberMapper.findAuthStateById("member-1")).thenReturn(member(true, null));

        cachedFilter.doFilter(request(), new MockHttpServletResponse(), mock(FilterChain.class));
        assertEquals("member-1", SecurityContextHolder.getContext().getAuthentication().getPrincipal());

        org.mockito.ArgumentCaptor<String> stored = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(
                org.mockito.ArgumentMatchers.eq("auth:state:member-1"),
                stored.capture(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any());

        // 두 번째 요청: 방금 저장한 값을 그대로 읽는다. 여기서도 인증돼야 한다.
        SecurityContextHolder.clearContext();
        when(valueOperations.get("auth:state:member-1")).thenReturn(stored.getValue());

        cachedFilter.doFilter(request(), new MockHttpServletResponse(), mock(FilterChain.class));

        assertEquals("member-1", SecurityContextHolder.getContext().getAuthentication().getPrincipal());
        verify(memberMapper, org.mockito.Mockito.times(1)).findAuthStateById("member-1");
    }

    private void assertAuthenticationForRecoveredMember(long issuedAtEpoch, boolean expected)
            throws Exception {
        stubToken(new Date(issuedAtEpoch * 1_000L));
        when(memberMapper.findAuthStateById("member-1")).thenReturn(member(true, WITHDRAWN_AT));

        filter.doFilter(request(), new MockHttpServletResponse(), mock(FilterChain.class));

        if (expected) {
            assertEquals("member-1", SecurityContextHolder.getContext().getAuthentication().getPrincipal());
        } else {
            assertNull(SecurityContextHolder.getContext().getAuthentication());
        }
    }

    private void stubToken(Date issuedAt) {
        stubToken(issuedAt, "USER");
    }

    private void stubToken(Date issuedAt, String role) {
        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn("member-1");
        when(claims.get("role", String.class)).thenReturn(role);
        when(claims.getIssuedAt()).thenReturn(issuedAt);
        when(jwtUtil.isTokenValid("access-token")).thenReturn(true);
        when(jwtUtil.parseClaims("access-token")).thenReturn(claims);
        when(jwtUtil.isAccessToken(claims)).thenReturn(true);
    }

    private void stubRejectedToken(String token) {
        Claims claims = mock(Claims.class);
        when(jwtUtil.isTokenValid(token)).thenReturn(true);
        when(jwtUtil.parseClaims(token)).thenReturn(claims);
        when(jwtUtil.isAccessToken(claims)).thenReturn(false);
    }

    private Map<String, Object> member(boolean active, Long withdrawnAtEpoch) {
        Map<String, Object> member = new HashMap<>();
        member.put("is_active", active ? 1 : 0);
        member.put("withdrawn_at_epoch", withdrawnAtEpoch);
        return member;
    }

    private MockHttpServletRequest request() {
        return request("access-token");
    }

    private MockHttpServletRequest request(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        return request;
    }
}
