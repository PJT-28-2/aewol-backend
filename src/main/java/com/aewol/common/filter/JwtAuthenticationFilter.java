package com.aewol.common.filter;

import com.aewol.common.cache.MemberAuthStateCache;
import com.aewol.common.util.JwtUtil;
import io.jsonwebtoken.Claims;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final MemberAuthStateCache authStateCache;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String refreshPath = request.getContextPath() + "/api/auth/refresh";
        return "POST".equalsIgnoreCase(request.getMethod())
                && refreshPath.equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = resolveToken(request);

        if (token != null && jwtUtil.isTokenValid(token)) {
            Claims claims = jwtUtil.parseClaims(token);
            if (!jwtUtil.isAccessToken(claims)) {
                filterChain.doFilter(request, response);
                return;
            }
            String memberId = claims.getSubject();
            Map<String, Object> authState;
            try {
                authState = authStateCache.find(memberId);
            } catch (DataAccessException e) {
                log.error("인증 과정에서 회원 활성 상태를 확인하지 못했습니다.", e);
                throw e;
            }

            if (!MemberAuthStateCache.canAuthenticate(authState, claims.getIssuedAt())) {
                filterChain.doFilter(request, response);
                return;
            }

            String role = resolveRole(authState, claims);
            if (role == null) {
                filterChain.doFilter(request, response);
                return;
            }

            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(
                            memberId,
                            null,
                            List.of(new SimpleGrantedAuthority("ROLE_" + role))
                    );
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }

        filterChain.doFilter(request, response);
    }

    /**
     * 권한은 DB의 현재 role을 쓴다. 토큰 claim만 보면 강등 후에도 access TTL(30분) 동안
     * ADMIN API가 살아 있다. DB에 role이 아직 없는 테스트/레거시 행만 claim으로 보조한다.
     */
    private String resolveRole(Map<String, Object> authState, Claims claims) {
        Object dbRole = authState.get("role");
        if (dbRole != null) {
            String role = String.valueOf(dbRole).trim();
            if (!role.isEmpty()) {
                return role;
            }
        }
        String claimRole = claims.get("role", String.class);
        if (claimRole == null || claimRole.isBlank()) {
            return null;
        }
        return claimRole.trim();
    }

    private String resolveToken(HttpServletRequest request) {
        String bearer = request.getHeader("Authorization");
        if (StringUtils.hasText(bearer)
                && bearer.length() > 7
                && bearer.regionMatches(true, 0, "Bearer", 0, 6)
                && bearer.charAt(6) == ' ') {
            String token = bearer.substring(7);
            if (StringUtils.hasText(token)
                    && token.equals(token.trim())
                    && token.chars().noneMatch(Character::isWhitespace)) {
                return token;
            }
        }
        return null;
    }
}
