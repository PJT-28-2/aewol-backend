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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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

    /**
     * DB가 실제로 돌려주는 모양.
     *
     * <p>{@code is_active}는 tinyint(1)이라 MyBatis가 Boolean으로 준다. 여기에 문자열을
     * 넣어두면 캐시가 문자열을 되돌려도 테스트가 통과해버린다 — 실제로 그렇게 놓쳤다.
     */
    private static Map<String, Object> authState(Object active, String role) {
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
        when(memberMapper.findAuthStateById("1")).thenReturn(authState(Boolean.TRUE, "USER"));

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
        // 문자열로 돌려주면 소비하는 쪽이 조용히 비활성으로 읽는다. 타입까지 확인한다.
        assertEquals(Boolean.TRUE, result.get("is_active"));
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
        when(memberMapper.findAuthStateById("1")).thenReturn(authState(Boolean.TRUE, "USER"));

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
        when(memberMapper.findAuthStateById("1")).thenReturn(authState(Boolean.TRUE, "USER"));

        noCache.find("1");
        noCache.find("1");

        verify(memberMapper, times(2)).findAuthStateById("1");
        assertDoesNotThrow(() -> noCache.evict("1"));
    }

    // 캐시 미스와 캐시 히트가 서로 다른 타입을 돌려주면, 소비하는 쪽에서만 조용히 어긋난다.
    // 이 캐시가 지켜야 할 계약은 "DB가 주던 것과 구별되지 않는 값"이다.
    @Test
    @DisplayName("캐시를 거쳐도 DB로 읽을 때와 같은 타입을 돌려준다")
    void should_returnSameTypesAsDatabase_when_roundTrippedThroughCache() {
        Map<String, Object> fromDb = authState(Boolean.TRUE, "USER");
        fromDb.put("withdrawn_at_epoch", 1_700_000_000L);

        when(valueOperations.get("auth:state:1")).thenReturn(null, (String) null);
        when(memberMapper.findAuthStateById("1")).thenReturn(fromDb);
        Map<String, Object> direct = cache.find("1");

        // 방금 저장한 값을 그대로 다시 읽은 상황을 만든다.
        org.mockito.ArgumentCaptor<String> stored = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(eq("auth:state:1"), stored.capture(), anyLong(), any());
        when(valueOperations.get("auth:state:1")).thenReturn(stored.getValue());
        Map<String, Object> viaCache = cache.find("1");

        assertEquals(direct.get("is_active").getClass(), viaCache.get("is_active").getClass());
        assertEquals(direct.get("is_active"), viaCache.get("is_active"));
        assertEquals(direct.get("role"), viaCache.get("role"));
        assertEquals(
                ((Number) direct.get("withdrawn_at_epoch")).longValue(),
                ((Number) viaCache.get("withdrawn_at_epoch")).longValue());
    }

    // 형식이 깨진 값을 "회원 없음"으로 읽으면 멀쩡한 회원이 인증에 실패한다.
    // 배포 중 인코딩이 바뀌거나 이전 버전 값이 남으면 실제로 일어난다.
    @Test
    @DisplayName("캐시 값 형식이 깨졌으면 DB로 넘어간다")
    void should_fallBackToDatabase_when_cachedValueMalformed() {
        when(valueOperations.get("auth:state:1")).thenReturn("망가진값");
        when(memberMapper.findAuthStateById("1")).thenReturn(authState(Boolean.TRUE, "USER"));

        Map<String, Object> result = cache.find("1");

        assertNotNull(result);
        assertEquals("USER", result.get("role"));
    }

    // 숫자가 아닌 epoch 하나 때문에 요청이 500으로 떨어지면 안 된다.
    @Test
    @DisplayName("epoch가 숫자가 아니어도 예외 없이 DB로 넘어간다")
    void should_fallBackToDatabase_when_epochNotNumeric() {
        when(valueOperations.get("auth:state:1")).thenReturn("1|USER|어제");
        when(memberMapper.findAuthStateById("1")).thenReturn(authState(Boolean.TRUE, "USER"));

        assertEquals("USER", assertDoesNotThrow(() -> cache.find("1")).get("role"));
    }
}
