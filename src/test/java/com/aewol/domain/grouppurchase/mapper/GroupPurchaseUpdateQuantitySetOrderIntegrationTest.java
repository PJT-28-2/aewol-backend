package com.aewol.domain.grouppurchase.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * updateQuantity의 is_urgent_active SET 절 순서 회귀를 실제 GroupPurchaseMapper.xml 문장으로
 * 실 MySQL에 대해 검증한다(리뷰로 발견).
 *
 * MySQL은 같은 UPDATE 문의 SET 절을 왼쪽에서 오른쪽으로 순차 평가하고, 뒤 절은 앞에서 이미
 * 갱신된 값을 본다. is_urgent_active의 CASE가 current_quantity 갱신보다 뒤에 있으면 그 안의
 * current_quantity가 이미 quantity만큼 더해진 값을 가리켜, 실질적으로 quantity를 두 번 더한
 * 기준으로 목표 도달을 오판한다. 예: current=7, target=10, quantity=2로 참여하면 실제 새
 * current_quantity=9(아직 미달, urgent 유지돼야 함)인데, 순서가 잘못되면 9+2=11>=10으로
 * 오판해 is_urgent_active를 0으로 내려버린다 — 이후 leave()도 자정 배치도 되돌리지 않아
 * V50 주석이 경계한 "영구히 잘못 박힌 채 남는" 상태가 된다.
 *
 * H2(GroupPurchaseMapperTest)는 이 순차 평가를 따르지 않아(모든 SET 절이 갱신 전 스냅숏을
 * 봄, ANSI 표준에 가까움) 이 회귀를 재현하지 못한다 — 그래서 실 MySQL(localhost:3307)로
 * 별도 검증한다. group_purchase는 실제 매퍼 문장이 테이블명을 하드코딩하고 있어(파라미터화
 * 불가) 전용 임시 테이블을 쓸 수 없다 — 전용 시드 회원(gp-set-order-test@example.test)
 * 소유 행만 만들고 테스트 후 정리한다. 로컬/CI에 MySQL이 없으면 건너뛴다.
 */
class GroupPurchaseUpdateQuantitySetOrderIntegrationTest {

    private static final String URL = "jdbc:mysql://localhost:3307/aewol?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Seoul";
    private static final String USER = "aewol";
    private static final String PASSWORD = "aewol1234";
    private static final String SEED_EMAIL = "gp-set-order-test@example.test";

    private Connection connection;
    private SqlSessionFactory sqlSessionFactory;
    private long seedMemberId;

    @BeforeEach
    void setUp() throws SQLException {
        try {
            connection = DriverManager.getConnection(URL, USER, PASSWORD);
        } catch (SQLException e) {
            Assumptions.abort("로컬/CI MySQL(localhost:3307)에 연결할 수 없어 건너뜁니다: " + e.getMessage());
            return;
        }
        seedMemberId = ensureSeedMember();
        sqlSessionFactory = createSqlSessionFactory();
    }

    @AfterEach
    void tearDown() throws SQLException {
        if (connection == null) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM group_purchase WHERE member_id = " + seedMemberId);
        } finally {
            connection.close();
        }
    }

    @Test
    @DisplayName("목표 미달로 끝나는 참여는 is_urgent_active를 1로 유지한다 — 두 배로 오판하지 않는다")
    void should_keepUrgentActive_when_stillBelowTargetAfterUpdate() throws SQLException {
        long gpId = insertGroupPurchase(7, 10);

        int updated = updateQuantity(gpId, 2);

        assertEquals(1, updated);
        assertEquals(9, findCurrentQuantity(gpId));
        assertEquals(1, findIsUrgentActive(gpId));
    }

    @Test
    @DisplayName("목표를 정확히 채우는 참여는 is_urgent_active를 0으로 내린다")
    void should_deactivateUrgentActive_when_targetReachedAfterUpdate() throws SQLException {
        long gpId = insertGroupPurchase(8, 10);

        int updated = updateQuantity(gpId, 2);

        assertEquals(1, updated);
        assertEquals(10, findCurrentQuantity(gpId));
        assertEquals(0, findIsUrgentActive(gpId));
    }

    private int updateQuantity(long gpId, int quantity) {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            return session.getMapper(GroupPurchaseMapper.class).updateQuantity(String.valueOf(gpId), quantity);
        }
    }

    private int findCurrentQuantity(long gpId) throws SQLException {
        return findIntColumn("current_quantity", gpId);
    }

    private int findIsUrgentActive(long gpId) throws SQLException {
        return findIntColumn("is_urgent_active", gpId);
    }

    private int findIntColumn(String column, long gpId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + column + " FROM group_purchase WHERE gp_id = ?")) {
            statement.setLong(1, gpId);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        }
    }

    private long insertGroupPurchase(int currentQuantity, int targetQuantity) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO group_purchase (member_id, product_name, target_quantity, current_quantity, status, is_urgent_active, deadline, created_at) "
                        + "VALUES (?, ?, ?, ?, 'OPEN', 1, ?, NOW())",
                Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, seedMemberId);
            statement.setString(2, "[SET순서 회귀 테스트] 상품");
            statement.setInt(3, targetQuantity);
            statement.setInt(4, currentQuantity);
            statement.setObject(5, LocalDateTime.now().plusDays(5));
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private long ensureSeedMember() throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO member (email, password, name, provider, role, zip_code, address) "
                        + "VALUES (?, NULL, 'SET순서 회귀 테스트', 'LOCAL', 'USER', '00000', '(테스트 전용, 실주소 아님)') "
                        + "ON DUPLICATE KEY UPDATE member_id = LAST_INSERT_ID(member_id)",
                Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, SEED_EMAIL);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private SqlSessionFactory createSqlSessionFactory() {
        UnpooledDataSource dataSource = new UnpooledDataSource("com.mysql.cj.jdbc.Driver", URL, USER, PASSWORD);
        Configuration configuration = new Configuration();
        configuration.setEnvironment(new Environment("test", new JdbcTransactionFactory(), dataSource));
        String resource = "mapper/grouppurchase/GroupPurchaseMapper.xml";
        try (InputStream inputStream = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(inputStream, configuration, resource, configuration.getSqlFragments()).parse();
        } catch (Exception exception) {
            throw new IllegalStateException("GroupPurchaseMapper를 초기화하지 못했습니다.", exception);
        }
        return new SqlSessionFactoryBuilder().build(configuration);
    }
}
