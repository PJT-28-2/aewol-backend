package com.aewol.domain.grouppurchase.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

/**
 * findMyGroupPurchases의 status 필터 SQL이 GroupPurchaseServiceImpl#computeStatus와
 * 동일한 기준(4분리: OPEN/COMPLETED/FAILED/CANCELLED)으로 판정하는지, 그리고 게시글은
 * 관리자만 작성하므로 참여글(group_purchase_participant)만 반환하는지 실제 H2 DB에 대해 검증한다.
 * GroupPurchaseServiceImplTest는 매퍼를 mock으로 대체하므로 이 SQL 자체는 그쪽 테스트로 잡히지 않는다.
 */
class GroupPurchaseMapperTest {

    private JdbcTemplate jdbcTemplate;
    private SqlSessionFactory sqlSessionFactory;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:group_purchase_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("""
                CREATE TABLE group_purchase (
                    gp_id BIGINT NOT NULL AUTO_INCREMENT,
                    member_id BIGINT NOT NULL,
                    product_name VARCHAR(200) NOT NULL,
                    -- 필터·정렬 검증에 쓰지는 않지만 findList가 컬럼을 명시해 조회하므로
                    -- 픽스처에도 있어야 한다.
                    category VARCHAR(20) NULL,
                    image VARCHAR(500) NULL,
                    unit_price DECIMAL(12,2) NULL,
                    group_price DECIMAL(12,2) NULL,
                    status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
                    current_quantity INT NOT NULL DEFAULT 0,
                    target_quantity INT NOT NULL DEFAULT 1,
                    -- V49: 정렬 전용 파생 컬럼. 매 요청 계산하지 않고 쓰기 시점(insertGroupPurchase
                    -- 픽스처가 여기서는 그 역할)에 미리 값을 넣어둔다는 게 요점이라, 이 테이블도
                    -- DB가 자동 계산해주지 않고 애플리케이션이 채워야 한다.
                    is_urgent_active TINYINT NOT NULL DEFAULT 0,
                    deadline DATETIME NULL,
                    created_at DATETIME NOT NULL,
                    PRIMARY KEY (gp_id)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE group_purchase_participant (
                    participant_id BIGINT NOT NULL AUTO_INCREMENT,
                    gp_id BIGINT NOT NULL,
                    member_id BIGINT NOT NULL,
                    purchase_quantity INT NOT NULL DEFAULT 1,
                    payment_status VARCHAR(10) NOT NULL DEFAULT 'PENDING',
                    canceled_at DATETIME NULL,
                    PRIMARY KEY (participant_id)
                )
                """);

        sqlSessionFactory = createSqlSessionFactory(dataSource);
    }

    @Test
    @DisplayName("참여한 공동구매만 반환하고, 참여하지 않은(작성만 한) 공동구매는 반환하지 않는다")
    void should_returnOnlyParticipatedGroupPurchases_notAuthoredWithoutParticipation() {
        long authoredOnlyGpId = insertGroupPurchase(99L, "OPEN", 3, 10, LocalDateTime.now().plusDays(5));
        // 관리자(99L)가 작성만 하고 본인은 참여하지 않은 글 — 마이페이지(참여자 전용)에서 제외되어야 한다.
        long participatedGpId = insertGroupPurchase(99L, "OPEN", 1, 5, LocalDateTime.now().plusDays(5));
        insertParticipant(participatedGpId, 1L);

        List<Map<String, Object>> result = findMyGroupPurchases("1", null);

        assertEquals(1, result.size());
        assertEquals(participatedGpId, ((Number) result.get(0).get("gp_id")).longValue());
    }

    @Test
    @DisplayName("마감 전이어도 목표 수량을 채웠으면 COMPLETED 필터에 잡히고 OPEN 필터에서는 빠진다")
    void should_matchCompletedFilter_notOpenFilter_when_targetReachedBeforeDeadline() {
        long gpId = insertGroupPurchase(99L, "OPEN", 10, 10, LocalDateTime.now().plusDays(5));
        insertParticipant(gpId, 1L);

        assertEquals(1, findMyGroupPurchases("1", "COMPLETED").size());
        assertEquals(0, findMyGroupPurchases("1", "OPEN").size());
    }

    @Test
    @DisplayName("마감 전이고 목표 미달이면 OPEN 필터에만 잡힌다")
    void should_matchOpenFilterOnly_when_deadlineNotPassedAndTargetNotReached() {
        long gpId = insertGroupPurchase(99L, "OPEN", 3, 10, LocalDateTime.now().plusDays(5));
        insertParticipant(gpId, 1L);

        assertEquals(1, findMyGroupPurchases("1", "OPEN").size());
        assertEquals(0, findMyGroupPurchases("1", "COMPLETED").size());
        assertEquals(0, findMyGroupPurchases("1", "FAILED").size());
        assertEquals(0, findMyGroupPurchases("1", "CANCELLED").size());
    }

    @Test
    @DisplayName("목표 수량이 0 이하인 비정상 데이터는 마감 전이면 OPEN 필터에만 잡히고 COMPLETED 필터에는 잡히지 않는다")
    void should_matchOpenFilterOnly_when_targetQuantityIsZero() {
        long gpId = insertGroupPurchase(99L, "OPEN", 0, 0, LocalDateTime.now().plusDays(5));
        insertParticipant(gpId, 1L);

        assertEquals(1, findMyGroupPurchases("1", "OPEN").size());
        assertEquals(0, findMyGroupPurchases("1", "COMPLETED").size());
    }

    @Test
    @DisplayName("findList는 image 컬럼을 목록 카드 썸네일용으로 함께 반환한다")
    void should_includeImageColumn_inFindListResult() {
        long gpId = insertGroupPurchase(99L, "OPEN", 3, 10, LocalDateTime.now().plusDays(5));
        jdbcTemplate.update("UPDATE group_purchase SET image = ? WHERE gp_id = ?",
                "group-purchase/sample.png", gpId);

        List<Map<String, Object>> result = findList(null, null, null, 10);

        assertEquals("group-purchase/sample.png", result.get(0).get("image"));
    }

    @Test
    @DisplayName("target_quantity가 0 이하인 비정상 데이터는 status 필터 없이도 findList 결과에서 제외된다")
    void should_excludeNonPositiveTargetQuantity_fromFindList_regardlessOfStatusFilter() {
        long corruptedGpId = insertGroupPurchase(99L, "OPEN", 0, 0, LocalDateTime.now().plusDays(5));
        long normalGpId = insertGroupPurchase(99L, "OPEN", 3, 10, LocalDateTime.now().plusDays(5));

        List<Map<String, Object>> result = findList(null, null, null, 10);

        assertEquals(1, result.size());
        assertEquals(normalGpId, ((Number) result.get(0).get("gp_id")).longValue());
        assertTrue(result.stream().noneMatch(row -> ((Number) row.get("gp_id")).longValue() == corruptedGpId));
    }

    @Test
    @DisplayName("target_quantity가 0 이하인 비정상 데이터는 OPEN 필터에서도 제외된다")
    void should_excludeNonPositiveTargetQuantity_fromFindList_underOpenFilter() {
        insertGroupPurchase(99L, "OPEN", 0, 0, LocalDateTime.now().plusDays(5));

        assertEquals(0, findList("OPEN", null, null, 10).size());
    }

    @Test
    @DisplayName("deadline이 NULL인 비정상 데이터는 status 필터 없이도 findList 결과에서 제외된다")
    void should_excludeNullDeadline_fromFindList_regardlessOfStatusFilter() {
        long corruptedGpId = insertGroupPurchase(99L, "OPEN", 3, 10, null);
        long normalGpId = insertGroupPurchase(99L, "OPEN", 3, 10, LocalDateTime.now().plusDays(5));

        List<Map<String, Object>> result = findList(null, null, null, 10);

        assertEquals(1, result.size());
        assertEquals(normalGpId, ((Number) result.get(0).get("gp_id")).longValue());
        assertTrue(result.stream().noneMatch(row -> ((Number) row.get("gp_id")).longValue() == corruptedGpId));
    }

    @Test
    @DisplayName("deadline이 NULL인 비정상 데이터는 OPEN 필터에서도 제외된다")
    void should_excludeNullDeadline_fromFindList_underOpenFilter() {
        insertGroupPurchase(99L, "OPEN", 3, 10, null);

        assertEquals(0, findList("OPEN", null, null, 10).size());
    }

    @Test
    @DisplayName("deadline이 NULL인 글이 섞여 있어도 전체 글 수가 페이지 크기를 넘으면 정상 글만 페이지 크기만큼 반환된다"
            + " — 회귀 재현: 이 필터가 없으면 NULL 행이 커서 경계로 뽑힐 때 GroupPurchaseServiceImpl#toCursor가 NPE로 500을 던졌다")
    void should_returnOnlyNormalRows_when_totalRowCountExceedsPageSize_withNullDeadlineMixedIn() {
        LocalDateTime now = LocalDateTime.now();
        List<Long> normalIds = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            normalIds.add(insertGroupPurchase(99L, "OPEN", 3, 10, now.plusDays(i + 1)));
        }
        long corruptedGpId = insertGroupPurchase(99L, "OPEN", 3, 10, null);

        List<Map<String, Object>> result = findList(null, null, null, 10);

        assertEquals(10, result.size());
        assertTrue(result.stream().noneMatch(row -> ((Number) row.get("gp_id")).longValue() == corruptedGpId));
        assertEquals(new HashSet<>(normalIds),
                result.stream().map(row -> ((Number) row.get("gp_id")).longValue()).collect(Collectors.toSet()));
    }

    @Test
    @DisplayName("마감 전이어도 목표 수량을 채웠으면 findList의 COMPLETED 필터에 잡히고 OPEN 필터에서는 빠진다")
    void should_matchCompletedFilterOnFindList_notOpenFilter_when_targetReachedBeforeDeadline() {
        insertGroupPurchase(99L, "OPEN", 10, 10, LocalDateTime.now().plusDays(5));

        assertEquals(1, findList("COMPLETED", null, null, 10).size());
        assertEquals(0, findList("OPEN", null, null, 10).size());
    }

    @Test
    @DisplayName("마감이 지났고 목표 미달이면 findList의 FAILED 필터에만 잡힌다")
    void should_matchFailedFilterOnFindList_when_deadlinePassedAndTargetNotReached() {
        insertGroupPurchase(99L, "OPEN", 3, 10, LocalDateTime.now().minusDays(1));

        assertEquals(1, findList("FAILED", null, null, 10).size());
        assertEquals(0, findList("OPEN", null, null, 10).size());
        assertEquals(0, findList("COMPLETED", null, null, 10).size());
    }

    @Test
    @DisplayName("필터 없이 조회하면 진행중(마감 전+목표 미달) 게시글 그룹 전체가 마감된(목표 달성/미달) 게시글 그룹보다 먼저 온다")
    void should_orderOpenGroupBeforeClosedGroup() {
        LocalDateTime now = LocalDateTime.now();
        long open = insertGroupPurchase(99L, "OPEN", 3, 10, now.plusDays(5), now.minusDays(3));
        long closed = insertGroupPurchase(99L, "OPEN", 10, 10, now.plusDays(1), now.minusHours(1));

        List<Map<String, Object>> result = findList(null, null, null, 10);

        assertEquals(
                List.of(open, closed),
                result.stream().map(row -> ((Number) row.get("gp_id")).longValue()).toList());
    }

    @Test
    @DisplayName("각 그룹 안에서는 마감이 임박한(deadline 오름차순) 게시글이 먼저 온다 — 등록일이 더 최근이어도 마감이 늦으면 뒤로 밀린다")
    void should_orderByDeadlineAscending_withinEachGroup() {
        LocalDateTime now = LocalDateTime.now();
        // soonDeadline은 더 일찍(오래전) 등록됐지만 마감이 임박해서 앞에 와야 한다.
        long soonDeadline = insertGroupPurchase(99L, "OPEN", 3, 10, now.plusDays(1), now.minusDays(5));
        long laterDeadline = insertGroupPurchase(99L, "OPEN", 3, 10, now.plusDays(5), now.minusHours(1));

        List<Map<String, Object>> result = findList(null, null, null, 10);

        assertEquals(
                List.of(soonDeadline, laterDeadline),
                result.stream().map(row -> ((Number) row.get("gp_id")).longValue()).toList());
    }

    @Test
    @DisplayName("마감이 같으면 최근에 등록된 게시글이 먼저 온다")
    void should_orderByCreatedAtDescending_whenDeadlineIsTied() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime sameDeadline = now.plusDays(5);
        long older = insertGroupPurchase(99L, "OPEN", 3, 10, sameDeadline, now.minusDays(3));
        long newer = insertGroupPurchase(99L, "OPEN", 3, 10, sameDeadline, now.minusHours(1));

        List<Map<String, Object>> result = findList(null, null, null, 10);

        assertEquals(
                List.of(newer, older),
                result.stream().map(row -> ((Number) row.get("gp_id")).longValue()).toList());
    }

    @Test
    @DisplayName("마감일과 등록일이 같으면 gp_id 내림차순으로 고정 정렬한다")
    void should_orderByIdDescending_whenDeadlineAndCreatedAtAreTied() {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        LocalDateTime sameDeadline = now.plusDays(5);
        long first = insertGroupPurchase(99L, "OPEN", 3, 10, sameDeadline, now);
        long second = insertGroupPurchase(99L, "OPEN", 3, 10, sameDeadline, now);

        List<Map<String, Object>> result = findList("OPEN", null, null, 10);

        assertEquals(
                List.of(second, first),
                result.stream().map(row -> ((Number) row.get("gp_id")).longValue()).toList());
    }

    @Test
    @DisplayName("findMyGroupPurchases도 마감 임박순, 마감이 같으면 최신 등록순으로 정렬한다")
    void should_orderMyGroupPurchasesByDeadlineThenCreatedAt() {
        LocalDateTime now = LocalDateTime.now();
        long soonDeadline = insertGroupPurchase(99L, "OPEN", 3, 10, now.plusDays(1), now.minusDays(5));
        long laterDeadlineButNewer = insertGroupPurchase(99L, "OPEN", 3, 10, now.plusDays(5), now.minusHours(1));
        insertParticipant(soonDeadline, 1L);
        insertParticipant(laterDeadlineButNewer, 1L);

        List<Map<String, Object>> result = findMyGroupPurchases("1", null);

        assertEquals(
                List.of(soonDeadline, laterDeadlineButNewer),
                result.stream().map(row -> ((Number) row.get("gp_id")).longValue()).toList());
    }

    @Test
    @DisplayName("sort=DEADLINE_ASC이면 등록일과 무관하게 마감이 가장 빠른 것부터 정렬한다")
    void should_orderByDeadlineAscending_when_sortIsDeadlineAsc() {
        LocalDateTime now = LocalDateTime.now();
        // created_at을 일부러 반대로 둬서, 최신 등록순이 아니라 진짜 deadline 기준으로
        // 정렬됐는지 구분되게 한다.
        long later = insertGroupPurchase(99L, "OPEN", 0, 10, now.plusDays(10), now.minusHours(1));
        long soonest = insertGroupPurchase(99L, "OPEN", 0, 10, now.plusHours(2), now.minusDays(3));
        long soon = insertGroupPurchase(99L, "OPEN", 0, 10, now.plusDays(1), now.minusDays(2));

        List<Map<String, Object>> result = findList("OPEN", null, null, 10, "DEADLINE_ASC");

        assertEquals(
                List.of(soonest, soon, later),
                result.stream().map(row -> ((Number) row.get("gp_id")).longValue()).toList());
    }

    @Test
    @DisplayName("마감이 지났고 목표 미달이면 FAILED 필터에만 잡힌다")
    void should_matchFailedFilterOnly_when_deadlinePassedAndTargetNotReached() {
        long gpId = insertGroupPurchase(99L, "OPEN", 3, 10, LocalDateTime.now().minusDays(1));
        insertParticipant(gpId, 1L);

        assertEquals(1, findMyGroupPurchases("1", "FAILED").size());
        assertEquals(0, findMyGroupPurchases("1", "OPEN").size());
        assertEquals(0, findMyGroupPurchases("1", "COMPLETED").size());
        assertEquals(0, findMyGroupPurchases("1", "CANCELLED").size());
    }

    @Test
    @DisplayName("작성자(관리자)가 취소한 공동구매는 마감 전이어도 CANCELLED 필터에만 잡히고 FAILED 필터에는 잡히지 않는다")
    void should_matchCancelledFilterOnly_when_ownerCancelled() {
        long gpId = insertGroupPurchase(99L, "CANCELLED", 3, 10, LocalDateTime.now().plusDays(5));
        insertParticipant(gpId, 1L);

        assertEquals(1, findMyGroupPurchases("1", "CANCELLED").size());
        assertEquals(0, findMyGroupPurchases("1", "OPEN").size());
        assertEquals(0, findMyGroupPurchases("1", "COMPLETED").size());
        assertEquals(0, findMyGroupPurchases("1", "FAILED").size());
    }

    @Test
    @DisplayName("status 필터가 없으면 상태와 무관하게 참여한 공동구매를 모두 반환한다")
    void should_returnAll_when_statusFilterIsNull() {
        long openGpId = insertGroupPurchase(99L, "OPEN", 3, 10, LocalDateTime.now().plusDays(5));
        insertParticipant(openGpId, 1L);
        long cancelledGpId = insertGroupPurchase(99L, "CANCELLED", 1, 10, LocalDateTime.now().plusDays(5));
        insertParticipant(cancelledGpId, 1L);
        long failedGpId = insertGroupPurchase(99L, "OPEN", 10, 10, LocalDateTime.now().minusDays(1));
        insertParticipant(failedGpId, 1L);

        assertTrue(findMyGroupPurchases("1", null).size() >= 3);
    }

    @Test
    @DisplayName("취소(CANCELLED)된 참여는 마이페이지 목록에서 제외된다 — 취소 후 재참여 시 중복 노출을 막는다")
    void should_excludeCancelledParticipant_fromMyGroupPurchases() {
        long gpId = insertGroupPurchase(99L, "OPEN", 1, 10, LocalDateTime.now().plusDays(5));
        insertParticipant(gpId, 1L, "CANCELLED");

        assertEquals(0, findMyGroupPurchases("1", null).size());
    }

    @Test
    @DisplayName("취소되지 않은 참여만 findParticipant로 조회된다 — CANCELLED 참여는 재참여를 막지 않아야 한다")
    void should_excludeCancelledParticipant_fromFindParticipant() {
        long gpId = insertGroupPurchase(99L, "OPEN", 1, 10, LocalDateTime.now().plusDays(5));
        insertParticipant(gpId, 1L, "CANCELLED");

        assertNull(findParticipant(gpId, 1L));
    }

    @Test
    @DisplayName("취소되지 않은(PAID) 참여는 findParticipant로 정상 조회된다")
    void should_returnParticipant_fromFindParticipant_when_notCancelled() {
        long gpId = insertGroupPurchase(99L, "OPEN", 1, 10, LocalDateTime.now().plusDays(5));
        insertParticipant(gpId, 1L, "PAID");

        assertEquals("PAID", findParticipant(gpId, 1L).get("payment_status").toString());
    }

    @Test
    @DisplayName("findParticipatingGpIds는 현재 페이지 중 CANCELLED가 아닌 참여 gp_id만 반환한다")
    void should_returnOnlyActiveParticipatingGpIds() {
        long joined = insertGroupPurchase(99L, "OPEN", 1, 10, LocalDateTime.now().plusDays(5));
        long cancelled = insertGroupPurchase(99L, "OPEN", 1, 10, LocalDateTime.now().plusDays(5));
        long other = insertGroupPurchase(99L, "OPEN", 1, 10, LocalDateTime.now().plusDays(5));
        insertParticipant(joined, 1L, "PAID");
        insertParticipant(cancelled, 1L, "CANCELLED");

        List<Long> result = findParticipatingGpIds(1L, List.of(joined, cancelled, other));

        assertEquals(1, result.size());
        assertEquals(joined, result.get(0));
    }

    @Test
    @DisplayName("진행중(마감 전, 목표 미달) 상태면 참여 취소로 수량이 감소한다")
    void should_decreaseQuantity_when_waitingState() {
        long gpId = insertGroupPurchase(99L, "OPEN", 3, 10, LocalDateTime.now().plusDays(5));

        assertEquals(1, decreaseQuantity(gpId, 2));
        assertEquals(1, findCurrentQuantity(gpId));
    }

    @Test
    @DisplayName("목표 수량을 달성(confirmed)했으면 참여 취소로 수량을 감소시킬 수 없다")
    void should_notDecreaseQuantity_when_targetReached() {
        long gpId = insertGroupPurchase(99L, "OPEN", 10, 10, LocalDateTime.now().plusDays(5));

        assertEquals(0, decreaseQuantity(gpId, 1));
        assertEquals(10, findCurrentQuantity(gpId));
    }

    @Test
    @DisplayName("마감이 지났으면 목표 미달이어도 참여 취소로 수량을 감소시킬 수 없다")
    void should_notDecreaseQuantity_when_deadlinePassed() {
        long gpId = insertGroupPurchase(99L, "OPEN", 3, 10, LocalDateTime.now().minusDays(1));

        assertEquals(0, decreaseQuantity(gpId, 2));
        assertEquals(3, findCurrentQuantity(gpId));
    }

    @Test
    @DisplayName("작성자가 취소(CANCELLED)한 공동구매는 참여 취소로 수량을 감소시킬 수 없다")
    void should_notDecreaseQuantity_when_ownerCancelled() {
        long gpId = insertGroupPurchase(99L, "CANCELLED", 3, 10, LocalDateTime.now().plusDays(5));

        assertEquals(0, decreaseQuantity(gpId, 2));
    }

    @Test
    @DisplayName("참여 취소는 row를 삭제하지 않고 CANCELLED로 남기며, 이미 취소된 참여를 다시 취소하면 영향 행이 0이다")
    void should_cancelParticipantOnce_thenReturnZero_onSecondAttempt() {
        long gpId = insertGroupPurchase(99L, "OPEN", 1, 10, LocalDateTime.now().plusDays(5));
        insertParticipant(gpId, 1L, "PAID");

        assertEquals(1, cancelParticipant(gpId, 1L));
        assertEquals(0, cancelParticipant(gpId, 1L));
        assertEquals("CANCELLED", findParticipantIncludingCancelled(gpId, 1L).get("payment_status").toString());
    }

    @Test
    @DisplayName("취소 후에도 (gp_id, member_id) 조합으로 같은 회원이 재참여할 수 있다")
    void should_allowReJoin_afterCancel() {
        long gpId = insertGroupPurchase(99L, "OPEN", 1, 10, LocalDateTime.now().plusDays(5));
        insertParticipant(gpId, 1L, "PAID");
        cancelParticipant(gpId, 1L);

        insertParticipant(gpId, 1L, "PENDING");

        assertNotNull(findParticipant(gpId, 1L));
    }

    @Test
    @DisplayName("마감이 지났고 목표 미달인 공동구매의 PAID 참여자는 자동환불 후보로 조회된다")
    void should_includeCandidate_when_deadlinePassedTargetNotReachedAndPaid() {
        long gpId = insertGroupPurchase(99L, "OPEN", 3, 10, LocalDateTime.now().minusDays(1));
        insertParticipant(gpId, 1L, "PAID");

        List<Map<String, Object>> result = findExpiredUnfulfilledPaidParticipants();

        assertEquals(1, result.size());
        assertEquals(1L, ((Number) result.get(0).get("member_id")).longValue());
    }

    @Test
    @DisplayName("마감 전이면 PAID 참여자여도 자동환불 후보에서 빠진다")
    void should_excludeCandidate_when_deadlineNotPassed() {
        long gpId = insertGroupPurchase(99L, "OPEN", 3, 10, LocalDateTime.now().plusDays(5));
        insertParticipant(gpId, 1L, "PAID");

        assertEquals(0, findExpiredUnfulfilledPaidParticipants().size());
    }

    @Test
    @DisplayName("목표 수량을 달성했으면 마감이 지났어도 자동환불 후보에서 빠진다")
    void should_excludeCandidate_when_targetReached() {
        long gpId = insertGroupPurchase(99L, "OPEN", 10, 10, LocalDateTime.now().minusDays(1));
        insertParticipant(gpId, 1L, "PAID");

        assertEquals(0, findExpiredUnfulfilledPaidParticipants().size());
    }

    @Test
    @DisplayName("목표 수량이 0 이하인 비정상 데이터는 마감이 지났으면 자동환불 후보에 포함된다 — 절대 채워질 수 없으므로 미달로 취급한다")
    void should_includeCandidate_when_targetQuantityIsZeroAndDeadlinePassed() {
        long gpId = insertGroupPurchase(99L, "OPEN", 0, 0, LocalDateTime.now().minusDays(1));
        insertParticipant(gpId, 1L, "PAID");

        assertEquals(1, findExpiredUnfulfilledPaidParticipants().size());
    }

    @Test
    @DisplayName("작성자가 취소(CANCELLED)한 공동구매는 자동환불 후보에서 빠진다 — 작성자 취소 API가 이미 환불을 처리한다")
    void should_excludeCandidate_when_ownerCancelled() {
        long gpId = insertGroupPurchase(99L, "CANCELLED", 3, 10, LocalDateTime.now().minusDays(1));
        insertParticipant(gpId, 1L, "PAID");

        assertEquals(0, findExpiredUnfulfilledPaidParticipants().size());
    }

    @Test
    @DisplayName("결제 전(PENDING) 참여자는 자동환불 후보에서 빠진다")
    void should_excludeCandidate_when_paymentStatusIsPending() {
        long gpId = insertGroupPurchase(99L, "OPEN", 3, 10, LocalDateTime.now().minusDays(1));
        insertParticipant(gpId, 1L, "PENDING");

        assertEquals(0, findExpiredUnfulfilledPaidParticipants().size());
    }

    @Test
    @DisplayName("이미 환불 처리(CANCELLED)된 참여자는 자동환불 후보에서 빠진다 — 재실행해도 중복 환불되지 않는다")
    void should_excludeCandidate_when_alreadyCancelled() {
        long gpId = insertGroupPurchase(99L, "OPEN", 3, 10, LocalDateTime.now().minusDays(1));
        insertParticipant(gpId, 1L, "CANCELLED");

        assertEquals(0, findExpiredUnfulfilledPaidParticipants().size());
    }

    @Test
    @DisplayName("마감이 지났고 목표 미달이면 참여 취소로 수량이 감소한다(자동환불 배치 전용)")
    void should_decreaseQuantityForExpired_when_deadlinePassedAndTargetNotReached() {
        long gpId = insertGroupPurchase(99L, "OPEN", 3, 10, LocalDateTime.now().minusDays(1));

        assertEquals(1, decreaseQuantityForExpired(gpId, 2));
        assertEquals(1, findCurrentQuantity(gpId));
    }

    @Test
    @DisplayName("마감 전이면 decreaseQuantityForExpired는 수량을 감소시키지 않는다 — 아직 진행중인 공동구매를 건드리면 안 된다")
    void should_notDecreaseQuantityForExpired_when_deadlineNotPassed() {
        long gpId = insertGroupPurchase(99L, "OPEN", 3, 10, LocalDateTime.now().plusDays(5));

        assertEquals(0, decreaseQuantityForExpired(gpId, 2));
        assertEquals(3, findCurrentQuantity(gpId));
    }

    @Test
    @DisplayName("마감 전이고 목표 미달이면 cancelGroupPurchase가 성공해 status가 CANCELLED로 바뀐다")
    void should_cancelGroupPurchase_when_openAndTargetNotReached() {
        long gpId = insertGroupPurchase(99L, "OPEN", 3, 10, LocalDateTime.now().plusDays(5));

        assertEquals(1, cancelGroupPurchase(gpId));
        assertEquals("CANCELLED", findStatus(gpId));
        // V49: 취소된 게시글은 findList의 status != 'CANCELLED' 조건에서 어차피 제외되지만,
        // is_urgent_active도 같이 0으로 내려서 컬럼값 자체가 항상 정확하게 유지되게 한다.
        assertEquals(0, findIsUrgentActive(gpId));
    }

    @Test
    @DisplayName("참여로 목표 수량에 도달하면 updateQuantity가 같은 문장 안에서 is_urgent_active를 0으로 내린다")
    void should_deactivateUrgentFlag_when_updateQuantityReachesTarget() {
        long gpId = insertGroupPurchase(99L, "OPEN", 8, 10, LocalDateTime.now().plusDays(5));

        assertEquals(1, updateQuantity(gpId, 2));

        assertEquals(10, findCurrentQuantity(gpId));
        assertEquals(0, findIsUrgentActive(gpId));
    }

    @Test
    @DisplayName("참여 후에도 목표 수량에 못 미치면 updateQuantity는 is_urgent_active를 1로 유지한다")
    void should_keepUrgentFlagActive_when_updateQuantityDoesNotReachTarget() {
        long gpId = insertGroupPurchase(99L, "OPEN", 3, 10, LocalDateTime.now().plusDays(5));

        assertEquals(1, updateQuantity(gpId, 2));

        assertEquals(5, findCurrentQuantity(gpId));
        assertEquals(1, findIsUrgentActive(gpId));
    }

    @Test
    @DisplayName("deactivateExpiredUrgentFlags는 마감이 지난 is_urgent_active=1 행만 0으로 내린다")
    void should_deactivateOnlyExpiredUrgentFlags_onBatchUpdate() {
        LocalDateTime now = LocalDateTime.now();
        long expiredButStillFlagged = insertGroupPurchase(99L, "OPEN", 3, 10, now.minusDays(1));
        // insertGroupPurchase 픽스처는 삽입 시점 기준으로 is_urgent_active를 계산하므로, 이미
        // 마감이 지난 행은 여기서도 정상적으로 0으로 들어간다. 배치가 "잘못 켜진 채로 남아있는"
        // 행만 고친다는 걸 확인하려면 강제로 1을 넣어 시간이 지나도록 방치된 상태를 흉내 내야 한다.
        jdbcTemplate.update("UPDATE group_purchase SET is_urgent_active = 1 WHERE gp_id = ?", expiredButStillFlagged);
        long stillOpen = insertGroupPurchase(99L, "OPEN", 3, 10, now.plusDays(5));

        int updated = deactivateExpiredUrgentFlags();

        assertEquals(1, updated);
        assertEquals(0, findIsUrgentActive(expiredButStillFlagged));
        assertEquals(1, findIsUrgentActive(stillOpen));
    }

    @Test
    @DisplayName("커서를 넘기면 그 다음 행부터 이어서 조회한다 — 이미 받은 행을 중복으로 다시 받지 않는다")
    void should_continueFromCursor_withoutDuplicatingPreviousRows() {
        LocalDateTime now = LocalDateTime.now();
        long first = insertGroupPurchase(99L, "OPEN", 3, 10, now.plusDays(1), now.minusDays(3));
        long second = insertGroupPurchase(99L, "OPEN", 3, 10, now.plusDays(2), now.minusDays(2));
        long third = insertGroupPurchase(99L, "OPEN", 3, 10, now.plusDays(3), now.minusDays(1));

        List<Map<String, Object>> firstPage = findList("OPEN", null, null, 2);
        assertEquals(List.of(first, second),
                firstPage.stream().map(row -> ((Number) row.get("gp_id")).longValue()).toList());
        Map<String, Object> cursorRow = firstPage.get(firstPage.size() - 1);

        List<Map<String, Object>> secondPage = findListAfterCursor("OPEN", cursorRow, 2);

        assertEquals(List.of(third),
                secondPage.stream().map(row -> ((Number) row.get("gp_id")).longValue()).toList());
    }

    @Test
    @DisplayName("배포 전환 기간 호환: legacyOffset이 있으면 커서 없이도 새 정렬 기준(is_urgent_active 포함)으로 OFFSET 페이지네이션이 동작한다")
    void should_paginateWithLegacyOffset_usingNewSortOrder() {
        LocalDateTime now = LocalDateTime.now();
        long first = insertGroupPurchase(99L, "OPEN", 3, 10, now.plusDays(1), now.minusDays(3));
        long second = insertGroupPurchase(99L, "OPEN", 3, 10, now.plusDays(2), now.minusDays(2));
        long third = insertGroupPurchase(99L, "OPEN", 3, 10, now.plusDays(3), now.minusDays(1));

        List<Map<String, Object>> firstPage = findListWithLegacyOffset(null, 2, 0);
        List<Map<String, Object>> secondPage = findListWithLegacyOffset(null, 2, 2);

        assertEquals(List.of(first, second),
                firstPage.stream().map(row -> ((Number) row.get("gp_id")).longValue()).toList());
        assertEquals(List.of(third),
                secondPage.stream().map(row -> ((Number) row.get("gp_id")).longValue()).toList());
    }

    private List<Map<String, Object>> findListWithLegacyOffset(String status, int limit, int legacyOffset) {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            return session.getMapper(GroupPurchaseMapper.class)
                    .findList(status, null, null, limit, null, null, null, null, legacyOffset, null);
        }
    }

    @Test
    @DisplayName("목표 수량을 이미 채웠으면 cancelGroupPurchase는 취소하지 않는다")
    void should_notCancelGroupPurchase_when_targetReached() {
        long gpId = insertGroupPurchase(99L, "OPEN", 10, 10, LocalDateTime.now().plusDays(5));

        assertEquals(0, cancelGroupPurchase(gpId));
        assertEquals("OPEN", findStatus(gpId));
    }

    @Test
    @DisplayName("마감이 지났으면 cancelGroupPurchase는 취소하지 않는다 — 자동환불 배치의 대상이다")
    void should_notCancelGroupPurchase_when_deadlinePassed() {
        long gpId = insertGroupPurchase(99L, "OPEN", 3, 10, LocalDateTime.now().minusDays(1));

        assertEquals(0, cancelGroupPurchase(gpId));
        assertEquals("OPEN", findStatus(gpId));
    }

    @Test
    @DisplayName("이미 취소된 공동구매는 cancelGroupPurchase를 다시 호출해도 영향 행이 0이다")
    void should_notCancelGroupPurchase_when_alreadyCancelled() {
        long gpId = insertGroupPurchase(99L, "CANCELLED", 3, 10, LocalDateTime.now().plusDays(5));

        assertEquals(0, cancelGroupPurchase(gpId));
    }

    @Test
    @DisplayName("target_quantity가 0 이하인 비정상 데이터도 마감 전이면 cancelGroupPurchase가 취소한다")
    void should_cancelGroupPurchase_when_targetQuantityIsAbnormal() {
        // findExpiredUnfulfilledPaidParticipants/findMyGroupPurchases와 동일하게 target_quantity<=0을
        // "목표 미달" 취급해야 한다 — 그렇지 않으면 이 행은 마감 전까지 영구히 취소 불가 상태로 남는다.
        long gpId = insertGroupPurchase(99L, "OPEN", 0, 0, LocalDateTime.now().plusDays(5));

        assertEquals(1, cancelGroupPurchase(gpId));
        assertEquals("CANCELLED", findStatus(gpId));
    }

    @Test
    @DisplayName("findActiveParticipants는 PAID/PENDING을 반환하고 CANCELLED는 제외한다")
    void should_returnActiveParticipants_excludingCancelled() {
        long gpId = insertGroupPurchase(99L, "OPEN", 3, 10, LocalDateTime.now().plusDays(5));
        insertParticipant(gpId, 1L, "PAID");
        insertParticipant(gpId, 2L, "PENDING");
        insertParticipant(gpId, 3L, "CANCELLED");

        List<Map<String, Object>> result = findActiveParticipants(gpId);

        assertEquals(2, result.size());
        assertTrue(result.stream().anyMatch(row -> 1L == ((Number) row.get("member_id")).longValue()));
        assertTrue(result.stream().anyMatch(row -> 2L == ((Number) row.get("member_id")).longValue()));
    }

    @Test
    @DisplayName("목표 수량을 달성했으면 마감이 지났어도 decreaseQuantityForExpired는 수량을 감소시키지 않는다")
    void should_notDecreaseQuantityForExpired_when_targetReached() {
        long gpId = insertGroupPurchase(99L, "OPEN", 10, 10, LocalDateTime.now().minusDays(1));

        assertEquals(0, decreaseQuantityForExpired(gpId, 1));
        assertEquals(10, findCurrentQuantity(gpId));
    }

    private List<Map<String, Object>> findExpiredUnfulfilledPaidParticipants() {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            return session.getMapper(GroupPurchaseMapper.class).findExpiredUnfulfilledPaidParticipants();
        }
    }

    private int decreaseQuantityForExpired(long gpId, int quantity) {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            return session.getMapper(GroupPurchaseMapper.class).decreaseQuantityForExpired(String.valueOf(gpId), quantity);
        }
    }

    private int cancelGroupPurchase(long gpId) {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            return session.getMapper(GroupPurchaseMapper.class).cancelGroupPurchase(String.valueOf(gpId));
        }
    }

    private List<Map<String, Object>> findActiveParticipants(long gpId) {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            return session.getMapper(GroupPurchaseMapper.class).findActiveParticipants(String.valueOf(gpId));
        }
    }

    private String findStatus(long gpId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM group_purchase WHERE gp_id = ?", String.class, gpId);
    }

    private int findIsUrgentActive(long gpId) {
        return jdbcTemplate.queryForObject(
                "SELECT is_urgent_active FROM group_purchase WHERE gp_id = ?", Integer.class, gpId);
    }

    private int updateQuantity(long gpId, int quantity) {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            return session.getMapper(GroupPurchaseMapper.class).updateQuantity(String.valueOf(gpId), quantity);
        }
    }

    private int deactivateExpiredUrgentFlags() {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            return session.getMapper(GroupPurchaseMapper.class).deactivateExpiredUrgentFlags();
        }
    }

    private Map<String, Object> findParticipant(long gpId, long memberId) {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            return session.getMapper(GroupPurchaseMapper.class).findParticipant(String.valueOf(gpId), String.valueOf(memberId));
        }
    }

    private List<Long> findParticipatingGpIds(long memberId, List<Long> gpIds) {
        List<String> ids = gpIds.stream().map(String::valueOf).toList();
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            return session.getMapper(GroupPurchaseMapper.class)
                    .findParticipatingGpIds(String.valueOf(memberId), ids);
        }
    }

    private Map<String, Object> findParticipantIncludingCancelled(long gpId, long memberId) {
        return jdbcTemplate.queryForMap(
                "SELECT * FROM group_purchase_participant WHERE gp_id = ? AND member_id = ?", gpId, memberId);
    }

    private int decreaseQuantity(long gpId, int quantity) {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            return session.getMapper(GroupPurchaseMapper.class).decreaseQuantity(String.valueOf(gpId), quantity);
        }
    }

    private int cancelParticipant(long gpId, long memberId) {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            return session.getMapper(GroupPurchaseMapper.class)
                    .cancelParticipant(String.valueOf(gpId), String.valueOf(memberId), LocalDateTime.now());
        }
    }

    private int findCurrentQuantity(long gpId) {
        return jdbcTemplate.queryForObject(
                "SELECT current_quantity FROM group_purchase WHERE gp_id = ?", Integer.class, gpId);
    }

    private List<Map<String, Object>> findMyGroupPurchases(String memberId, String status) {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            return session.getMapper(GroupPurchaseMapper.class).findMyGroupPurchases(memberId, status);
        }
    }

    private List<Map<String, Object>> findList(String status, String keyword, String category, int limit) {
        return findList(status, keyword, category, limit, null);
    }

    // 커서(keyset) 페이지네이션 도입 후에도 이 파일의 모든 테스트는 첫 페이지(커서 없음)만
    // 검증한다 — 커서 이어받기 자체는 GroupPurchaseServiceImplTest(인코딩/디코딩)와
    // GroupPurchaseCursorTest에서 다룬다.
    private List<Map<String, Object>> findList(String status, String keyword, String category, int limit, String sort) {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            return session.getMapper(GroupPurchaseMapper.class)
                    .findList(status, keyword, category, limit, null, null, null, null, null, sort);
        }
    }

    /** cursorRow는 이전 페이지의 마지막 행(findList 원본 결과 Map)이다 — 실제 서비스가 GroupPurchaseCursor로
     *  인코딩/디코딩하는 것과 동일한 필드(is_urgent_active, deadline, created_at, gp_id)를 그대로 넘긴다. */
    private List<Map<String, Object>> findListAfterCursor(String status, Map<String, Object> cursorRow, int limit) {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            return session.getMapper(GroupPurchaseMapper.class).findList(status, null, null, limit,
                    ((Number) cursorRow.get("is_urgent_active")).intValue(),
                    toLocalDateTime(cursorRow.get("deadline")),
                    toLocalDateTime(cursorRow.get("created_at")),
                    ((Number) cursorRow.get("gp_id")).longValue(),
                    null, null);
        }
    }

    /** H2가 DATETIME 컬럼을 java.sql.Timestamp로 돌려주는 경우가 있어(드라이버/버전에 따라 다름) 방어적으로 변환한다. */
    private static LocalDateTime toLocalDateTime(Object value) {
        if (value instanceof LocalDateTime) {
            return (LocalDateTime) value;
        }
        return ((java.sql.Timestamp) value).toLocalDateTime();
    }

    private long insertGroupPurchase(long memberId, String status, int currentQuantity, int targetQuantity,
                                      LocalDateTime deadline) {
        return insertGroupPurchase(memberId, status, currentQuantity, targetQuantity, deadline, LocalDateTime.now());
    }

    private long insertGroupPurchase(long memberId, String status, int currentQuantity, int targetQuantity,
                                      LocalDateTime deadline, LocalDateTime createdAt) {
        // is_urgent_active는 GroupPurchaseMapper.xml의 insert가 실제로 넣는 규칙과 동일하게
        // 여기서도 직접 계산해 채운다 — production 코드에서는 항상 생성 시점에 1이지만(등록
        // 검증상 current=0<target, deadline은 미래), 이 픽스처는 임의의 current/target/deadline
        // 조합으로 과거 상태를 흉내 내야 해서 매번 다시 계산해야 한다.
        boolean urgentActive = currentQuantity < targetQuantity
                && (deadline == null || !deadline.isBefore(LocalDateTime.now()));
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO group_purchase "
                            + "(member_id, product_name, status, current_quantity, target_quantity, is_urgent_active, deadline, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, memberId);
            statement.setString(2, "테스트 상품");
            statement.setString(3, status);
            statement.setInt(4, currentQuantity);
            statement.setInt(5, targetQuantity);
            statement.setInt(6, urgentActive ? 1 : 0);
            statement.setObject(7, deadline);
            statement.setObject(8, createdAt);
            return statement;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    private void insertParticipant(long gpId, long memberId) {
        jdbcTemplate.update(
                "INSERT INTO group_purchase_participant (gp_id, member_id, purchase_quantity) VALUES (?, ?, 1)",
                gpId, memberId);
    }

    private void insertParticipant(long gpId, long memberId, String paymentStatus) {
        jdbcTemplate.update(
                "INSERT INTO group_purchase_participant (gp_id, member_id, purchase_quantity, payment_status) VALUES (?, ?, 1, ?)",
                gpId, memberId, paymentStatus);
    }

    private SqlSessionFactory createSqlSessionFactory(JdbcDataSource dataSource) {
        Configuration configuration = new Configuration();
        configuration.setEnvironment(new Environment("test", new JdbcTransactionFactory(), dataSource));
        String resource = "mapper/grouppurchase/GroupPurchaseMapper.xml";
        try (InputStream inputStream = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(inputStream, configuration, resource,
                    configuration.getSqlFragments()).parse();
        } catch (Exception exception) {
            throw new IllegalStateException("GroupPurchaseMapper를 초기화하지 못했습니다.", exception);
        }
        return new SqlSessionFactoryBuilder().build(configuration);
    }
}
