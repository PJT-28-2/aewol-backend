package com.aewol.config;

import com.aewol.common.cache.MemberAuthStateCache;
import com.aewol.common.util.JwtUtil;
import io.jsonwebtoken.Claims;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * SockJS 핸드셰이크는 JWT 없이 열리지만, STOMP CONNECT에서 access token을 확인한다.
 * HTTP 필터와 같이 회원의 현재 활성 상태도 본다. 탈퇴한 토큰으로 소켓만 열려 있으면
 * 이후 메시지 핸들러가 추가될 때 HTTP로는 막힌 계정이 우회 경로가 된다.
 */
@Slf4j
@Component
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    private final JwtUtil jwtUtil;
    private final MemberAuthStateCache authStateCache;

    public StompAuthChannelInterceptor(JwtUtil jwtUtil, MemberAuthStateCache authStateCache) {
        this.jwtUtil = jwtUtil;
        this.authStateCache = authStateCache;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || !StompCommand.CONNECT.equals(accessor.getCommand())) {
            return message;
        }
        String token = resolveToken(accessor);
        if (!StringUtils.hasText(token) || !jwtUtil.isTokenValid(token)) {
            throw new MessageDeliveryException("로그인이 필요합니다.");
        }
        Claims claims = jwtUtil.parseClaims(token);
        if (!jwtUtil.isAccessToken(claims)) {
            throw new MessageDeliveryException("로그인이 필요합니다.");
        }
        Map<String, Object> authState;
        try {
            authState = authStateCache.find(claims.getSubject());
        } catch (DataAccessException e) {
            log.error("WebSocket 인증 과정에서 회원 활성 상태를 확인하지 못했습니다.", e);
            throw new MessageDeliveryException("로그인이 필요합니다.");
        }
        if (!MemberAuthStateCache.canAuthenticate(authState, claims.getIssuedAt())) {
            throw new MessageDeliveryException("로그인이 필요합니다.");
        }
        accessor.setUser(new UsernamePasswordAuthenticationToken(
                claims.getSubject(), null, List.of()));
        return message;
    }

    private String resolveToken(StompHeaderAccessor accessor) {
        String bearer = accessor.getFirstNativeHeader("Authorization");
        if (!StringUtils.hasText(bearer)
                || bearer.length() <= 7
                || !bearer.regionMatches(true, 0, "Bearer", 0, 6)
                || bearer.charAt(6) != ' ') {
            return null;
        }
        return bearer.substring(7).trim();
    }
}
