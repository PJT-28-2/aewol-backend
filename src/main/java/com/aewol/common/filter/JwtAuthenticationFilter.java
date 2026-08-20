package com.aewol.common.filter;

import com.aewol.common.util.JwtUtil;
import com.aewol.domain.member.mapper.MemberMapper;
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
import java.util.Date;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final MemberMapper memberMapper;

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
                authState = memberMapper.findAuthStateById(memberId);
            } catch (DataAccessException e) {
                log.error("인증 과정에서 회원 활성 상태를 확인하지 못했습니다.", e);
                throw e;
            }

            if (!canAuthenticate(authState, claims.getIssuedAt())) {
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

    private boolean canAuthenticate(Map<String, Object> authState, Date issuedAt) {
        if (authState == null || !isActive(authState.get("is_active")) || issuedAt == null) {
            return false;
        }
        Object withdrawnAtEpoch = authState.get("withdrawn_at_epoch");
        if (withdrawnAtEpoch == null) {
            return true;
        }
        // 마지막 탈퇴 시각과 같거나 이전에 발급된 토큰은 복구 후에도 다시 사용할 수 없다.
        return issuedAt.getTime() / 1000L > ((Number) withdrawnAtEpoch).longValue();
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

    private boolean isActive(Object value) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return value instanceof Number && ((Number) value).intValue() == 1;
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
