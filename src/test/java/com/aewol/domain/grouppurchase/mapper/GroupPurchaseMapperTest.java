package com.aewol.domain.grouppurchase.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * 동일한 기준(목표 수량 달성 시 마감 여부 무관 COMPLETED)으로 판정하는지 실제 H2 DB에 대해 검증한다.
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
                    PRIMARY KEY (participant_id)
                )
                """);

        sqlSessionFactory = createSqlSessionFactory(dataSource);
    }

    @Test
    @DisplayName("작성글과 참여글을 합쳐서 조회하고, 자기 글에 자기가 참여해도 중복 없이 반환한다")
    void should_returnAuthoredAndParticipatedGroupPurchases_withoutDuplicates() {
        long ownGpId = insertGroupPurchase(1L, "OPEN", 3, 10, LocalDateTime.now().plusDays(5));
        insertParticipant(ownGpId, 1L); // 작성자 본인이 자기 글에 참여한 경우 (LEFT JOIN 중복 유발 케이스)
        long othersGpId = insertGroupPurchase(2L, "OPEN", 1, 5, LocalDateTime.now().plusDays(5));
        insertParticipant(othersGpId, 1L); // 다른 사람 글에 참여

        List<Map<String, Object>> result = findMyGroupPurchases("1", null);

        assertEquals(2, result.size());
    }

    @Test
    @DisplayName("마감 전이어도 목표 수량을 채웠으면 COMPLETED 필터에 잡히고 OPEN 필터에서는 빠진다")
    void should_matchCompletedFilter_notOpenFilter_when_targetReachedBeforeDeadline() {
        insertGroupPurchase(1L, "OPEN", 10, 10, LocalDateTime.now().plusDays(5));

        assertEquals(1, findMyGroupPurchases("1", "COMPLETED").size());
        assertEquals(0, findMyGroupPurchases("1", "OPEN").size());
    }

    @Test
    @DisplayName("마감 전이고 목표 미달이면 OPEN 필터에만 잡힌다")
    void should_matchOpenFilterOnly_when_deadlineNotPassedAndTargetNotReached() {
        insertGroupPurchase(1L, "OPEN", 3, 10, LocalDateTime.now().plusDays(5));

        assertEquals(1, findMyGroupPurchases("1", "OPEN").size());
        assertEquals(0, findMyGroupPurchases("1", "COMPLETED").size());
        assertEquals(0, findMyGroupPurchases("1", "CANCELLED").size());
    }

    @Test
    @DisplayName("마감이 지났고 목표 미달이면 CANCELLED 필터에만 잡힌다")
    void should_matchCancelledFilterOnly_when_deadlinePassedAndTargetNotReached() {
        insertGroupPurchase(1L, "OPEN", 3, 10, LocalDateTime.now().minusDays(1));

        assertEquals(1, findMyGroupPurchases("1", "CANCELLED").size());
        assertEquals(0, findMyGroupPurchases("1", "OPEN").size());
        assertEquals(0, findMyGroupPurchases("1", "COMPLETED").size());
    }

    @Test
    @DisplayName("작성자가 취소한 공동구매는 마감 전이어도 CANCELLED 필터에만 잡힌다")
    void should_matchCancelledFilterOnly_when_ownerCancelled() {
        insertGroupPurchase(1L, "CANCELLED", 3, 10, LocalDateTime.now().plusDays(5));

        assertEquals(1, findMyGroupPurchases("1", "CANCELLED").size());
        assertEquals(0, findMyGroupPurchases("1", "OPEN").size());
        assertEquals(0, findMyGroupPurchases("1", "COMPLETED").size());
    }

    @Test
    @DisplayName("status 필터가 없으면 상태와 무관하게 모두 반환한다")
    void should_returnAll_when_statusFilterIsNull() {
        insertGroupPurchase(1L, "OPEN", 3, 10, LocalDateTime.now().plusDays(5));
        insertGroupPurchase(1L, "CANCELLED", 1, 10, LocalDateTime.now().plusDays(5));
        insertGroupPurchase(1L, "OPEN", 10, 10, LocalDateTime.now().minusDays(1));

        assertTrue(findMyGroupPurchases("1", null).size() >= 3);
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
