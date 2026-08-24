package com.aewol.domain.donation.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.InputStream;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
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
 * insertRoundUp의 재실행 멱등성을 실제 DonationMapper.xml 문장으로, 이 프로젝트의 JDBC URL에
 * useAffectedRows를 명시하지 않은(=커넥터 기본값, CLIENT_FOUND_ROWS 모드) 설정 그대로 실 MySQL에
 * 대해 검증한다(리뷰로 발견).
 *
 * <p>원래 문장은 {@code ON DUPLICATE KEY UPDATE source_txn_id = VALUES(source_txn_id)}로 값이
 * 그대로인 no-op 업데이트를 흉내 내 "중복이면 영향 행 0"을 기대했다. 하지만 MySQL Connector/J의
 * useAffectedRows 기본값(false, found-rows 모드)에서는 이런 no-op 업데이트도 영향 행이 1로
 * 보고된다 — affected-rows(기본, 비-found-rows) 모드에서만 0이 된다. 이 프로젝트의 JDBC URL
 * (application-dev.yml, 이 테스트 모두)이 useAffectedRows를 켜지 않으므로, 배치가 같은 후보를
 * 재실행하면 {@code DonationRoundUpExecutor.execute()}가 중복 건을 신규 삽입으로 오인해 잔돈을
 * 두 번 적립할 수 있었다. INSERT IGNORE로 바꾸면 커넥터 모드와 무관하게 신규 삽입=1, 중복 무시=0이
 * 항상 보장된다.
 *
 * <p>H2(단위테스트)는 커넥터별 affected-rows 해석 차이를 재현하지 않으므로 실 MySQL(localhost:3307)로
 * 별도 검증한다. 로컬/CI에 MySQL이 없으면 건너뛴다.
 */
class DonationRoundUpInsertIdempotencyIntegrationTest {

    private static final String URL = "jdbc:mysql://localhost:3307/aewol?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Seoul";
    private static final String USER = "aewol";
    private static final String PASSWORD = "aewol1234";
    private static final String SEED_EMAIL = "donation-roundup-idempotency-test@example.test";

    private Connection connection;
    private SqlSessionFactory sqlSessionFactory;
    private long seedMemberId;
    private long seedWalletId;

    @BeforeEach
    void setUp() throws SQLException {
        try {
            connection = DriverManager.getConnection(URL, USER, PASSWORD);
        } catch (SQLException e) {
            Assumptions.abort("로컬/CI MySQL(localhost:3307)에 연결할 수 없어 건너뜁니다: " + e.getMessage());
            return;
        }
        seedMemberId = ensureSeedMember();
        seedWalletId = insertWallet(seedMemberId);
        sqlSessionFactory = createSqlSessionFactory();
    }

    @AfterEach
    void tearDown() throws SQLException {
        if (connection == null) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM donation_roundup WHERE wallet_id = " + seedWalletId);
            statement.execute("DELETE FROM transaction WHERE wallet_id = " + seedWalletId);
            statement.execute("DELETE FROM wallet WHERE wallet_id = " + seedWalletId);
        } finally {
            connection.close();
        }
    }

    @Test
    @DisplayName("같은 source_txn_id로 재실행하면 두 번째 삽입은 영향 행 0으로 무시된다 — 1로 오인해 중복 적립하지 않는다")
    void should_ignoreSecondInsert_when_sameSourceTxnId() throws SQLException {
        long txnId = insertTransaction(seedWalletId);

        int first = insertRoundUp(txnId, seedWalletId, "200.00");
        int second = insertRoundUp(txnId, seedWalletId, "200.00");

        assertEquals(1, first, "신규 삽입은 영향 행 1이어야 한다");
        assertEquals(0, second, "중복 재실행은 영향 행 0으로 무시되어야 한다 — 1이면 배치가 중복 적립한다");
        assertEquals(1, countRoundUpRows(txnId), "실제 저장된 행은 한 건이어야 한다");
    }

    private int insertRoundUp(long txnId, long walletId, String amount) {
        Map<String, Object> roundUp = new HashMap<>();
        roundUp.put("sourceTxnId", txnId);
        roundUp.put("walletId", walletId);
        roundUp.put("savingUnit", new BigDecimal("1000.00"));
        roundUp.put("roundupAmount", new BigDecimal(amount));
        roundUp.put("status", "PENDING");
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            return session.getMapper(DonationMapper.class).insertRoundUp(roundUp);
        }
    }

    private int countRoundUpRows(long txnId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM donation_roundup WHERE source_txn_id = ?")) {
            statement.setLong(1, txnId);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        }
    }

    private long insertTransaction(long walletId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO transaction (wallet_id, txn_type, price, category, txn_date, created_at) "
                        + "VALUES (?, 'PAYMENT', 34800, 'FOOD', NOW(), NOW())",
                Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, walletId);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private long insertWallet(long memberId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO wallet (member_id, wallet_type, balance) VALUES (?, 'DONATION', 0.00)",
                Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, memberId);
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
                        + "VALUES (?, NULL, '잔돈적립 멱등성 테스트', 'LOCAL', 'USER', '00000', '(테스트 전용, 실주소 아님)') "
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
        String resource = "mapper/donation/DonationMapper.xml";
        try (InputStream inputStream = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(inputStream, configuration, resource, configuration.getSqlFragments()).parse();
        } catch (Exception exception) {
            throw new IllegalStateException("DonationMapper를 초기화하지 못했습니다.", exception);
        }
        return new SqlSessionFactoryBuilder().build(configuration);
    }
}
