package com.aewol.common.filter;

import com.aewol.domain.member.mapper.MemberMapper;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * JWT 인증 시 확인하는 회원 활성 상태 캐시.
 *
 * <p>인증된 모든 요청이 {@code findAuthStateById}로 DB를 한 번씩 때렸다. 토큰만으로는
 * 탈퇴·비활성화를 알 수 없어 매번 확인해야 했기 때문인데, 값이 거의 바뀌지 않는 데다
 * 요청 수만큼 늘어나 DB에 그대로 부하가 걸렸다.
 *
 * <p>TTL을 짧게(60초) 둔다. 캐시가 길수록 DB는 편해지지만, 탈퇴한 회원이 그만큼 더 오래
 * 인증에 성공한다. 무효화를 걸어두더라도 놓치는 경로가 생길 수 있으므로 TTL 자체를
 * 안전망으로 삼는다.
 *
 * <p>Redis가 죽어도 인증은 계속돼야 한다. 조회·저장 실패는 삼키고 DB로 넘어간다 —
 * 캐시는 부하를 줄이는 장치이지 정답의 출처가 아니다.
 *
 * <p><b>돌려주는 맵은 DB가 주던 것과 타입까지 같아야 한다.</b> 소비하는 쪽
 * ({@code JwtAuthenticationFilter})은 {@code is_active}를 Boolean 또는 Number로만 읽는다.
 * 캐시가 문자열을 돌려주면 예외도 없이 조용히 "비활성"으로 판정돼 인증이 통째로 막힌다.
 * 캐시 미스인 첫 요청만 통과하고 TTL 동안 전부 401이 되는, 눈에 잘 안 띄는 사고다.
 */
@Slf4j
@Component
public class MemberAuthStateCache {

    private static final String KEY_PREFIX = "auth:state:";
    /** 탈퇴가 반영되기까지 최대 이만큼 늦는다. 그 대가로 매 요청 DB 조회를 없앤다. */
    private static final long TTL_SECONDS = 60;
    /** 없는 회원도 캐시해 존재하지 않는 id로 반복 조회되는 것을 막는다. */
    private static final String ABSENT = "-";

    private final RedisTemplate<String, String> redisTemplate;
    private final MemberMapper memberMapper;

    public MemberAuthStateCache(RedisTemplate<String, String> redisTemplate, MemberMapper memberMapper) {
        this.redisTemplate = redisTemplate;
        this.memberMapper = memberMapper;
    }

    /**
     * Redis 없이 DB만 보는 캐시.
     *
     * <p>단위테스트처럼 Redis를 띄우지 않는 자리에서 쓴다. 캐시는 부하를 줄이는 장치이지
     * 정답의 출처가 아니므로, 없으면 매번 DB를 보면 그만이다.
     */
    public static MemberAuthStateCache withoutCache(MemberMapper memberMapper) {
        return new MemberAuthStateCache(null, memberMapper);
    }

    /**
     * 활성 상태를 돌려준다. 캐시에 없거나 읽을 수 없으면 DB에서 읽어 채운다.
     *
     * @return 회원이 없으면 {@code null}
     * @throws DataAccessException DB 조회 자체가 실패한 경우 — 인증을 통과시키면 안 되므로
     *         그대로 올려보낸다
     */
    public Map<String, Object> find(String memberId) {
        String cached = readQuietly(memberId);
        if (ABSENT.equals(cached)) {
            return null;
        }
        if (cached != null) {
            Map<String, Object> decoded = decode(cached);
            if (decoded != null) {
                return decoded;
            }
            // 형식이 깨진 값을 "회원 없음"으로 읽으면 멀쩡한 회원이 인증에 실패한다.
            // 배포 중 인코딩이 바뀌거나 이전 버전 값이 남은 경우가 여기 걸리므로,
            // 캐시 미스와 똑같이 취급해 DB를 정답으로 삼는다.
            log.warn("[AUTH_CACHE_CORRUPT] 인증 캐시 형식이 올바르지 않습니다 — DB로 넘어갑니다. memberId={}", memberId);
        }

        Map<String, Object> authState = memberMapper.findAuthStateById(memberId);
        writeQuietly(memberId, authState);
        return authState;
    }

    /**
     * 트랜잭션이 커밋된 뒤에 캐시를 버린다.
     *
     * <p>Redis는 DB 트랜잭션에 참여하지 않는다. 커밋 전에 지우면 아직 예전 상태인 DB를
     * 다른 요청이 읽어 그대로 다시 캐싱하고, 정작 커밋된 새 상태는 TTL이 다할 때까지
     * 반영되지 않는다. 탈퇴 경로에서는 방금 탈퇴한 회원이 계속 인증에 성공한다는 뜻이라,
     * 이 캐시를 무효화하는 이유 자체가 무너진다.
     *
     * <p>롤백일 때도 지운다. 헛되이 지우면 DB를 한 번 더 읽을 뿐이지만, 지워야 할 때
     * 안 지우면 인증 상태가 어긋난다.
     *
     * <p>커밋 직후와 다른 요청의 캐시 저장이 겹치는 아주 좁은 구간은 남는다. 이때는
     * TTL(60초)이 상한이다. 완전히 없애려면 버전 태그나 지연 재삭제가 필요한데,
     * 이미 TTL을 안전망으로 두고 설계했으므로 여기서는 과하다고 봤다.
     */
    public void evictAfterCommit(String memberId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            // 트랜잭션 밖에서 불렸다면 이미 반영이 끝난 상태다.
            evict(memberId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                evict(memberId);
            }
        });
    }

    /**
     * 캐시를 즉시 버린다.
     *
     * <p>트랜잭션 안에서 상태를 바꾼 뒤라면 {@link #evictAfterCommit(String)}을 써야 한다.
     */
    public void evict(String memberId) {
        if (redisTemplate == null) {
            return;
        }
        try {
            redisTemplate.delete(KEY_PREFIX + memberId);
        } catch (RuntimeException e) {
            // 지우지 못하면 TTL 만료까지 예전 상태가 남는다. 최대 60초라 감수하되,
            // 조사할 수 있게 남긴다.
            log.warn("[AUTH_CACHE_EVICT_FAILED] 인증 캐시를 지우지 못했습니다. memberId={}", memberId, e);
        }
    }

    private String readQuietly(String memberId) {
        if (redisTemplate == null) {
            return null;
        }
        try {
            return redisTemplate.opsForValue().get(KEY_PREFIX + memberId);
        } catch (RuntimeException e) {
            log.warn("[AUTH_CACHE_READ_FAILED] 인증 캐시 조회 실패 — DB로 넘어갑니다.", e);
            return null;
        }
    }

    private void writeQuietly(String memberId, Map<String, Object> authState) {
        if (redisTemplate == null) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(
                    KEY_PREFIX + memberId, encode(authState), TTL_SECONDS, TimeUnit.SECONDS);
        } catch (RuntimeException e) {
            log.warn("[AUTH_CACHE_WRITE_FAILED] 인증 캐시 저장 실패 — 다음 요청도 DB를 봅니다.", e);
        }
    }

    /**
     * {@code isActive|role|withdrawnAtEpoch} 형태로 담는다.
     *
     * <p>JSON 직렬화를 쓰지 않는 이유는 필드가 셋뿐이고 매 요청 경로라 가볍게 두기
     * 위해서다. 필드가 늘면 그때 바꾼다.
     *
     * <p>드라이버나 매핑 설정에 따라 {@code is_active}는 Boolean으로도 Integer로도,
     * epoch는 Long으로도 BigDecimal로도 올 수 있다. 받은 타입을 그대로 문자열로 만들면
     * 그 차이가 캐시에 새겨지므로, 여기서 한 가지 표기로 눌러 담는다.
     */
    private static String encode(Map<String, Object> authState) {
        if (authState == null) {
            return ABSENT;
        }
        return encodeActive(authState.get("is_active")) + "|"
                + text(authState.get("role")) + "|"
                + encodeEpoch(authState.get("withdrawn_at_epoch"));
    }

    private static String encodeActive(Object value) {
        if (value instanceof Boolean) {
            return ((Boolean) value) ? "1" : "0";
        }
        if (value instanceof Number) {
            return ((Number) value).intValue() == 1 ? "1" : "0";
        }
        if (value == null) {
            return "";
        }
        // 알 수 없는 표기는 활성으로 넘기지 않는다. 잘못 통과시키는 쪽이 잘못 막는 쪽보다 나쁘다.
        return Boolean.parseBoolean(String.valueOf(value)) ? "1" : "0";
    }

    private static String encodeEpoch(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Number) {
            return String.valueOf(((Number) value).longValue());
        }
        return String.valueOf(value);
    }

    /**
     * @return 형식이 깨졌으면 {@code null} — 부르는 쪽이 캐시 미스로 취급한다
     */
    private static Map<String, Object> decode(String cached) {
        String[] parts = cached.split("\\|", -1);
        if (parts.length != 3) {
            return null;
        }
        try {
            Map<String, Object> authState = new java.util.HashMap<>();
            // DB가 주던 타입 그대로 되돌린다. 문자열로 돌려주면 소비하는 쪽이
            // 조용히 비활성으로 읽는다.
            authState.put("is_active", parts[0].isEmpty() ? null : Boolean.valueOf("1".equals(parts[0])));
            authState.put("role", parts[1].isEmpty() ? null : parts[1]);
            authState.put("withdrawn_at_epoch", parts[2].isEmpty() ? null : Long.valueOf(parts[2]));
            return authState;
        } catch (NumberFormatException e) {
            // 숫자가 아닌 epoch 하나가 요청을 500으로 떨어뜨리면 안 된다.
            return null;
        }
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
