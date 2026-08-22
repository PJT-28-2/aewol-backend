package com.aewol.common.filter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.aewol.domain.member.mapper.MemberMapper;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class MemberAuthStateCacheTest {

    @Mock RedisTemplate<String, String> redisTemplate;
    @Mock ValueOperations<String, String> valueOperations;
    @Mock MemberMapper memberMapper;

    private MemberAuthStateCache cache;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        cache = new MemberAuthStateCache(redisTemplate, memberMapper);
    }

    private static Map<String, Object> authState(String active, String role) {
        Map<String, Object> row = new HashMap<>();
        row.put("is_active", active);
        row.put("role", role);
        row.put("withdrawn_at_epoch", null);
        return row;
    }

    @Test
    @DisplayName("캐시에 없으면 DB를 보고 채운다")
    void should_loadFromDatabase_when_cacheMisses() {
        when(valueOperations.get("auth:state:1")).thenReturn(null);
        when(memberMapper.findAuthStateById("1")).thenReturn(authState("1", "USER"));

        Map<String, Object> result = cache.find("1");

        assertEquals("USER", result.get("role"));
        verify(valueOperations).set(eq("auth:state:1"), anyString(), eq(60L), eq(TimeUnit.SECONDS));
    }

    // 이 캐시의 존재 이유다. 두 번째 요청부터 DB를 때리지 않아야 한다.
    @Test
    @DisplayName("캐시에 있으면 DB를 보지 않는다")
    void should_notTouchDatabase_when_cacheHits() {
        when(valueOperations.get("auth:state:1")).thenReturn("1|USER|");

        Map<String, Object> result = cache.find("1");

        assertEquals("USER", result.get("role"));
        assertEquals("1", result.get("is_active"));
        verify(memberMapper, never()).findAuthStateById(anyString());
    }

    // 없는 회원을 캐시하지 않으면 존재하지 않는 id로 매번 DB를 때린다.
    @Test
    @DisplayName("없는 회원도 캐시해 반복 조회를 막는다")
    void should_cacheAbsentMember() {
        when(valueOperations.get("auth:state:404")).thenReturn(null);
        when(memberMapper.findAuthStateById("404")).thenReturn(null);

        assertNull(cache.find("404"));
        verify(valueOperations).set(eq("auth:state:404"), eq("-"), anyLong(), any());
    }

    @Test
    @DisplayName("없는 회원으로 캐시된 값은 DB를 다시 보지 않는다")
    void should_returnNull_when_absentCached() {
        when(valueOperations.get("auth:state:404")).thenReturn("-");

        assertNull(cache.find("404"));
        verify(memberMapper, never()).findAuthStateById(anyString());
    }

    // 캐시는 부하를 줄이는 장치이지 정답의 출처가 아니다. Redis가 죽어도 인증은 돌아야 한다.
    @Test
    @DisplayName("Redis 조회가 실패해도 DB로 넘어간다")
    void should_fallBackToDatabase_when_redisFails() {
        when(valueOperations.get("auth:state:1")).thenThrow(new RuntimeException("redis down"));
        when(memberMapper.findAuthStateById("1")).thenReturn(authState("1", "USER"));

        assertEquals("USER", cache.find("1").get("role"));
    }

    // 지우지 않으면 TTL이 지날 때까지 탈퇴한 회원이 계속 인증된다.
    @Test
    @DisplayName("무효화하면 캐시 키를 지운다")
    void should_deleteKey_when_evicted() {
        cache.evict("1");

        verify(redisTemplate).delete("auth:state:1");
    }

    @Test
    @DisplayName("Redis 없이 만들면 매번 DB를 본다")
    void should_alwaysHitDatabase_when_createdWithoutCache() {
        MemberAuthStateCache noCache = MemberAuthStateCache.withoutCache(memberMapper);
        when(memberMapper.findAuthStateById("1")).thenReturn(authState("1", "USER"));

        noCache.find("1");
        noCache.find("1");

        verify(memberMapper, times(2)).findAuthStateById("1");
        assertDoesNotThrow(() -> noCache.evict("1"));
    }
}
