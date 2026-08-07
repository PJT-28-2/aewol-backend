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
import java.util.List;

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
            String memberId = claims.getSubject();
            String role = claims.get("role", String.class);

            // 서명된 기존 Access Token이라도 현재 회원이 비활성이면 보호 API 인증을 허용하지 않는다.
            boolean active;
            try {
                active = memberMapper.existsActiveById(memberId);
            } catch (DataAccessException e) {
                log.error("인증 과정에서 회원 활성 상태를 확인하지 못했습니다.", e);
                throw e;
            }

            if (active) {
                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(
                                memberId,
                                null,
                                List.of(new SimpleGrantedAuthority("ROLE_" + role))
                        );
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        }

        filterChain.doFilter(request, response);
    }

    private String resolveToken(HttpServletRequest request) {
        String bearer = request.getHeader("Authorization");
        if (StringUtils.hasText(bearer) && bearer.startsWith("Bearer ")) {
            return bearer.substring(7);
        }
        return null;
    }
}
