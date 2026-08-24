package com.aewol.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aewol.common.cache.MemberAuthStateCache;
import com.aewol.common.util.JwtUtil;
import io.jsonwebtoken.Claims;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

class StompAuthChannelInterceptorTest {

    private final JwtUtil jwtUtil = mock(JwtUtil.class);
    private final MemberAuthStateCache authStateCache = mock(MemberAuthStateCache.class);
    private final StompAuthChannelInterceptor interceptor =
            new StompAuthChannelInterceptor(jwtUtil, authStateCache);

    @Test
    void connectWithoutTokenIsRejected() {
        Message<byte[]> message = connectMessage(null);
        assertThrows(MessageDeliveryException.class,
                () -> interceptor.preSend(message, mock(MessageChannel.class)));
    }

    @Test
    void connectWithAccessTokenSetsUser() {
        stubValidAccessToken("member-1", new Date(2_000_000L));
        when(authStateCache.find("member-1")).thenReturn(activeMember());

        Message<?> result = interceptor.preSend(connectMessage("Bearer access-token"), mock(MessageChannel.class));
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(result);
        assertEquals("member-1", accessor.getUser().getName());
    }

    @Test
    void connectWithInactiveMemberIsRejected() {
        stubValidAccessToken("member-1", new Date(2_000_000L));
        Map<String, Object> inactive = activeMember();
        inactive.put("is_active", 0);
        when(authStateCache.find("member-1")).thenReturn(inactive);

        assertThrows(MessageDeliveryException.class,
                () -> interceptor.preSend(connectMessage("Bearer access-token"), mock(MessageChannel.class)));
    }

    @Test
    void connectWithWithdrawnMemberIsRejected() {
        stubValidAccessToken("member-1", new Date(1_000_000L));
        Map<String, Object> withdrawn = activeMember();
        withdrawn.put("withdrawn_at_epoch", 2_000L);
        when(authStateCache.find("member-1")).thenReturn(withdrawn);

        assertThrows(MessageDeliveryException.class,
                () -> interceptor.preSend(connectMessage("Bearer access-token"), mock(MessageChannel.class)));
    }

    @Test
    void connectWhenAuthStateLookupFailsIsRejected() {
        stubValidAccessToken("member-1", new Date(2_000_000L));
        when(authStateCache.find("member-1"))
                .thenThrow(new DataAccessResourceFailureException("redis/db down"));

        assertThrows(MessageDeliveryException.class,
                () -> interceptor.preSend(connectMessage("Bearer access-token"), mock(MessageChannel.class)));
    }

    private void stubValidAccessToken(String memberId, Date issuedAt) {
        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn(memberId);
        when(claims.getIssuedAt()).thenReturn(issuedAt);
        when(jwtUtil.isTokenValid("access-token")).thenReturn(true);
        when(jwtUtil.parseClaims("access-token")).thenReturn(claims);
        when(jwtUtil.isAccessToken(claims)).thenReturn(true);
    }

    private static Map<String, Object> activeMember() {
        Map<String, Object> authState = new HashMap<>();
        authState.put("is_active", 1);
        return authState;
    }

    private Message<byte[]> connectMessage(String authorization) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setLeaveMutable(true);
        if (authorization != null) {
            accessor.setNativeHeader("Authorization", authorization);
        }
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
