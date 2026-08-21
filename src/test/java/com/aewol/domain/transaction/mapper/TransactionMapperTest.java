package com.aewol.domain.transaction.mapper;

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
 * findByWalletId/findRecentByWalletId의 WITHDRAW·PAYMENT 필터가 실제 SQL 레벨에서 환불된
 * 원래 결제(PAYMENT)를 제외하는지 실제 H2 DB에 대해 검증한다. TransactionMapperSqlTest는 XML을
 * 문자열로만 확인하므로, 이 테스트가 그 조건이 실제로 올바르게 동작하는지를 보증한다.
 */
class TransactionMapperTest {

    private JdbcTemplate jdbcTemplate;
    private SqlSessionFactory sqlSessionFactory;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:transaction_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("""
                CREATE TABLE `transaction` (
                    txn_id BIGINT NOT NULL AUTO_INCREMENT,
                    wallet_id BIGINT NOT NULL,
                    txn_type VARCHAR(20) NOT NULL,
                    price DECIMAL(15,2) NOT NULL,
                    category VARCHAR(20) NULL,
                    txn_date DATETIME NOT NULL,
                    PRIMARY KEY (txn_id)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE group_purchase_participant (
                    participant_id BIGINT NOT NULL AUTO_INCREMENT,
                    gp_id BIGINT NOT NULL,
                    member_id BIGINT NOT NULL,
                    txn_id BIGINT NULL,
                    payment_status VARCHAR(10) NOT NULL DEFAULT 'PENDING',
                    PRIMARY KEY (participant_id)
                )
                """);

        sqlSessionFactory = createSqlSessionFactory(dataSource);
    }

    @Test
    @DisplayName("WITHDRAW 필터는 환불된 결제는 빼고, 환불되지 않은 결제만 반환한다")
    void should_excludeRefundedPayment_fromWithdrawFilter() {
        long refundedTxnId = insertTransaction(1L, "PAYMENT", "10000");
        insertParticipant(1L, refundedTxnId, "CANCELLED");
        long keptTxnId = insertTransaction(1L, "PAYMENT", "20000");

        List<Map<String, Object>> result = findByWalletId(1L, "WITHDRAW");

        assertEquals(1, result.size());
        assertEquals(keptTxnId, txnId(result.get(0)));
    }

    @Test
    @DisplayName("PAYMENT 필터는 환불된 결제는 빼고, 환불되지 않은 결제만 반환한다")
    void should_excludeRefundedPayment_fromPaymentFilter() {
        long refundedTxnId = insertTransaction(1L, "PAYMENT", "10000");
        insertParticipant(1L, refundedTxnId, "CANCELLED");
        long keptTxnId = insertTransaction(1L, "PAYMENT", "20000");

        List<Map<String, Object>> result = findByWalletId(1L, "PAYMENT");

        assertEquals(1, result.size());
        assertEquals(keptTxnId, txnId(result.get(0)));
    }

    @Test
    @DisplayName("ALL(필터 없음)에서는 환불된 결제와 REFUND row가 모두 그대로 나온다")
    void should_includeRefundedPaymentAndRefundRow_forAllFilter() {
        long refundedTxnId = insertTransaction(1L, "PAYMENT", "10000");
        insertParticipant(1L, refundedTxnId, "CANCELLED");
        long refundTxnId = insertTransaction(1L, "REFUND", "10000");

        List<Map<String, Object>> result = findByWalletId(1L, "ALL");

        assertEquals(2, result.size());
        assertTrue(result.stream().anyMatch(row -> txnId(row) == refundedTxnId));
        assertTrue(result.stream().anyMatch(row -> txnId(row) == refundTxnId));
    }

    @Test
    @DisplayName("PENDING 참여로 남은 참조는 결제를 제외하지 않는다 — CANCELLED일 때만 제외한다")
    void should_notExcludePayment_when_participantIsNotCancelled() {
        long txnIdValue = insertTransaction(1L, "PAYMENT", "10000");
        insertParticipant(1L, txnIdValue, "PAID");

        List<Map<String, Object>> result = findByWalletId(1L, "PAYMENT");

        assertEquals(1, result.size());
    }

    @Test
    @DisplayName("최근 거래 WITHDRAW 필터도 환불된 결제를 제외한다")
    void should_excludeRefundedPayment_fromRecentWithdrawFilter() {
        long refundedTxnId = insertTransaction(1L, "PAYMENT", "10000");
        insertParticipant(1L, refundedTxnId, "CANCELLED");
        long keptTxnId = insertTransaction(1L, "PAYMENT", "20000");

        List<Map<String, Object>> result = findRecentByWalletId(1L, "WITHDRAW");

        assertEquals(1, result.size());
        assertEquals(keptTxnId, txnId(result.get(0)));
    }

    @Test
    @DisplayName("최근 거래 PAYMENT 필터도 환불된 결제를 제외한다")
    void should_excludeRefundedPayment_fromRecentPaymentFilter() {
        long refundedTxnId = insertTransaction(1L, "PAYMENT", "10000");
        insertParticipant(1L, refundedTxnId, "CANCELLED");
        long keptTxnId = insertTransaction(1L, "PAYMENT", "20000");

        List<Map<String, Object>> result = findRecentByWalletId(1L, "PAYMENT");

        assertEquals(1, result.size());
        assertEquals(keptTxnId, txnId(result.get(0)));
    }

    @Test
    @DisplayName("최근 거래 ALL(필터 없음)에서는 환불된 결제도 그대로 나온다")
    void should_includeRefundedPayment_forRecentAllFilter() {
        long refundedTxnId = insertTransaction(1L, "PAYMENT", "10000");
        insertParticipant(1L, refundedTxnId, "CANCELLED");

        List<Map<String, Object>> result = findRecentByWalletId(1L, null);

        assertEquals(1, result.size());
    }

    private long txnId(Map<String, Object> row) {
        return ((Number) row.get("txn_id")).longValue();
    }

    private long insertTransaction(long walletId, String txnType, String price) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO `transaction` (wallet_id, txn_type, price, txn_date) VALUES (?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, walletId);
            statement.setString(2, txnType);
            statement.setString(3, price);
            statement.setObject(4, LocalDateTime.now());
            return statement;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    private void insertParticipant(long gpId, long txnId, String paymentStatus) {
        jdbcTemplate.update(
                "INSERT INTO group_purchase_participant (gp_id, member_id, txn_id, payment_status) VALUES (?, ?, ?, ?)",
                gpId, 1L, txnId, paymentStatus);
    }

    private List<Map<String, Object>> findByWalletId(long walletId, String txnFilter) {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            return session.getMapper(TransactionMapper.class).findByWalletId(
                    String.valueOf(walletId), txnFilter,
                    LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(1),
                    null, null, 20);
        }
    }

    private List<Map<String, Object>> findRecentByWalletId(long walletId, String txnFilter) {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            return session.getMapper(TransactionMapper.class)
                    .findRecentByWalletId(String.valueOf(walletId), txnFilter, 20);
        }
    }

    private SqlSessionFactory createSqlSessionFactory(JdbcDataSource dataSource) {
        Configuration configuration = new Configuration();
        configuration.setEnvironment(new Environment("test", new JdbcTransactionFactory(), dataSource));
        String resource = "mapper/transaction/TransactionMapper.xml";
        try (InputStream inputStream = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(inputStream, configuration, resource,
                    configuration.getSqlFragments()).parse();
        } catch (Exception exception) {
            throw new IllegalStateException("TransactionMapper를 초기화하지 못했습니다.", exception);
        }
        return new SqlSessionFactoryBuilder().build(configuration);
    }
}
