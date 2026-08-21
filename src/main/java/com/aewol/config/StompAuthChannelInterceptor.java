package com.aewol.config;

import com.aewol.common.util.JwtUtil;
import io.jsonwebtoken.Claims;
import java.util.List;
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
 */
@Component
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    private final JwtUtil jwtUtil;

    public StompAuthChannelInterceptor(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
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
