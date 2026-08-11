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

    private long insertGroupPurchase(long memberId, String status, int currentQuantity, int targetQuantity,
                                      LocalDateTime deadline) {
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
            statement.setObject(7, LocalDateTime.now());
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
