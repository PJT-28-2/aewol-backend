package com.aewol.domain.grouppurchase.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 * findMyGroupPurchases의 status 필터 SQL이 GroupPurchaseServiceImpl#toMyStatus와
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
                    status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
                    current_quantity INT NOT NULL DEFAULT 0,
                    target_quantity INT NOT NULL DEFAULT 1,
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
    @DisplayName("target_quantity가 0 이하인 비정상 데이터는 status 필터 없이도 findList 결과에서 제외된다")
    void should_excludeNonPositiveTargetQuantity_fromFindList_regardlessOfStatusFilter() {
        long corruptedGpId = insertGroupPurchase(99L, "OPEN", 0, 0, LocalDateTime.now().plusDays(5));
        long normalGpId = insertGroupPurchase(99L, "OPEN", 3, 10, LocalDateTime.now().plusDays(5));

        List<Map<String, Object>> result = findList(null, null, null, 10, 0);

        assertEquals(1, result.size());
        assertEquals(normalGpId, ((Number) result.get(0).get("gp_id")).longValue());
        assertTrue(result.stream().noneMatch(row -> ((Number) row.get("gp_id")).longValue() == corruptedGpId));
    }

    @Test
    @DisplayName("target_quantity가 0 이하인 비정상 데이터는 OPEN 필터에서도 제외된다")
    void should_excludeNonPositiveTargetQuantity_fromFindList_underOpenFilter() {
        insertGroupPurchase(99L, "OPEN", 0, 0, LocalDateTime.now().plusDays(5));

        assertEquals(0, findList("OPEN", null, null, 10, 0).size());
    }

    @Test
    @DisplayName("마감 전이어도 목표 수량을 채웠으면 findList의 COMPLETED 필터에 잡히고 OPEN 필터에서는 빠진다")
    void should_matchCompletedFilterOnFindList_notOpenFilter_when_targetReachedBeforeDeadline() {
        insertGroupPurchase(99L, "OPEN", 10, 10, LocalDateTime.now().plusDays(5));

        assertEquals(1, findList("COMPLETED", null, null, 10, 0).size());
        assertEquals(0, findList("OPEN", null, null, 10, 0).size());
    }

    @Test
    @DisplayName("마감이 지났고 목표 미달이면 findList의 FAILED 필터에만 잡힌다")
    void should_matchFailedFilterOnFindList_when_deadlinePassedAndTargetNotReached() {
        insertGroupPurchase(99L, "OPEN", 3, 10, LocalDateTime.now().minusDays(1));

        assertEquals(1, findList("FAILED", null, null, 10, 0).size());
        assertEquals(0, findList("OPEN", null, null, 10, 0).size());
        assertEquals(0, findList("COMPLETED", null, null, 10, 0).size());
    }

    @Test
    @DisplayName("필터 없이 조회하면 진행중(마감 전+목표 미달) 게시글이 그룹 전체가 먼저, 마감된(목표 달성/미달) 게시글이 뒤에 오고 각 그룹 안에서는 최신 등록순이다")
    void should_orderOpenGroupBeforeClosedGroup_andLatestFirstWithinEachGroup() {
        LocalDateTime now = LocalDateTime.now();
        long oldOpen = insertGroupPurchase(99L, "OPEN", 3, 10, now.plusDays(5), now.minusDays(3));
        long newOpen = insertGroupPurchase(99L, "OPEN", 3, 10, now.plusDays(5), now.minusHours(1));
        long oldClosed = insertGroupPurchase(99L, "OPEN", 10, 10, now.plusDays(5), now.minusDays(2));
        long newClosed = insertGroupPurchase(99L, "OPEN", 3, 10, now.minusDays(1), now.minusHours(2));

        List<Map<String, Object>> result = findList(null, null, null, 10, 0);

        assertEquals(
                List.of(newOpen, oldOpen, newClosed, oldClosed),
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
    @DisplayName("findPaidParticipants는 PAID 참여자만 반환하고 PENDING/CANCELLED는 제외한다")
    void should_returnOnlyPaidParticipants() {
        long gpId = insertGroupPurchase(99L, "OPEN", 3, 10, LocalDateTime.now().plusDays(5));
        insertParticipant(gpId, 1L, "PAID");
        insertParticipant(gpId, 2L, "PENDING");
        insertParticipant(gpId, 3L, "CANCELLED");

        List<Map<String, Object>> result = findPaidParticipants(gpId);

        assertEquals(1, result.size());
        assertEquals(1L, ((Number) result.get(0).get("member_id")).longValue());
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

    private List<Map<String, Object>> findPaidParticipants(long gpId) {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            return session.getMapper(GroupPurchaseMapper.class).findPaidParticipants(String.valueOf(gpId));
        }
    }

    private String findStatus(long gpId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM group_purchase WHERE gp_id = ?", String.class, gpId);
    }

    private Map<String, Object> findParticipant(long gpId, long memberId) {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            return session.getMapper(GroupPurchaseMapper.class).findParticipant(String.valueOf(gpId), String.valueOf(memberId));
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

    private List<Map<String, Object>> findList(String status, String keyword, String category, int limit, int offset) {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            return session.getMapper(GroupPurchaseMapper.class).findList(status, keyword, category, limit, offset);
        }
    }

    private long insertGroupPurchase(long memberId, String status, int currentQuantity, int targetQuantity,
                                      LocalDateTime deadline) {
        return insertGroupPurchase(memberId, status, currentQuantity, targetQuantity, deadline, LocalDateTime.now());
    }

    private long insertGroupPurchase(long memberId, String status, int currentQuantity, int targetQuantity,
                                      LocalDateTime deadline, LocalDateTime createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO group_purchase "
                            + "(member_id, product_name, status, current_quantity, target_quantity, deadline, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, memberId);
            statement.setString(2, "테스트 상품");
            statement.setString(3, status);
            statement.setInt(4, currentQuantity);
            statement.setInt(5, targetQuantity);
            statement.setObject(6, deadline);
            statement.setObject(7, createdAt);
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
