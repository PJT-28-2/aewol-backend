package com.aewol.common.filter;

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
