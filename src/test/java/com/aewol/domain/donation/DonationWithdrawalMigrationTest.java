package com.aewol.domain.donation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.aewol.domain.donation.mapper.DonationMapper;
import java.io.InputStream;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

class DonationWithdrawalMigrationTest {

    private JdbcDataSource dataSource;
    private JdbcTemplate jdbcTemplate;
    private SqlSessionFactory sqlSessionFactory;

    @BeforeEach
    void setUp() {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:withdrawal_idempotency_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("""
                CREATE TABLE wallet (
                    wallet_id BIGINT NOT NULL,
                    member_id VARCHAR(50) NOT NULL,
                    wallet_type VARCHAR(20) NOT NULL,
                    balance DECIMAL(15,2) NOT NULL,
                    PRIMARY KEY (wallet_id)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE `transaction` (
                    txn_id BIGINT NOT NULL AUTO_INCREMENT,
                    wallet_id BIGINT NOT NULL,
                    txn_type VARCHAR(20) NULL,
                    price DECIMAL(15,2) NULL,
                    auto_tagged CHAR(1) NULL DEFAULT 'N',
                    transfer_purpose VARCHAR(32) NULL,
                    PRIMARY KEY (txn_id)
                )
                """);

        Flyway.configure()
                .dataSource(dataSource)
                .locations("filesystem:src/main/resources/db/migration")
                .baselineOnMigrate(true)
                .baselineVersion(MigrationVersion.fromVersion("10"))
                .target(MigrationVersion.fromVersion("11"))
                .load()
                .migrate();

        sqlSessionFactory = createSqlSessionFactory();
    }

    @Test
    @DisplayName("V11은 같은 저금통과 멱등키의 중복 거래를 차단한다")
    void should_rejectDuplicateKey_when_walletAndIdempotencyKeyAreSame() {
        insertTransaction(1L, "withdraw-1");

        assertThrows(DataIntegrityViolationException.class,
                () -> insertTransaction(1L, "withdraw-1"));
    }

    @Test
    @DisplayName("V11은 서로 다른 저금통에서 같은 멱등키를 사용할 수 있게 한다")
    void should_allowSameKey_when_walletIsDifferent() {
        insertTransaction(1L, "withdraw-1");

        assertDoesNotThrow(() -> insertTransaction(2L, "withdraw-1"));
    }

    @Test
    @Timeout(10)
    @DisplayName("잠금 대기 중 재시도는 첫 출금 커밋 후 최신 거래를 조회한다")
    void should_readCommittedWithdrawal_when_retryWaitsForPotLock() throws Exception {
        jdbcTemplate.update(
                "INSERT INTO wallet (wallet_id, member_id, wallet_type, balance) VALUES (1, 'member-1', 'DONATION', 10000)");
        CountDownLatch firstWithdrawalInserted = new CountDownLatch(1);
        CountDownLatch retrySnapshotCreated = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> firstRequest = executor.submit(() -> {
                try (SqlSession session = sqlSessionFactory.openSession(false)) {
                    session.getConnection().setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                    DonationMapper mapper = session.getMapper(DonationMapper.class);
                    assertNotNull(mapper.findPotForUpdate("member-1"));
                    insertWithdrawal(session.getConnection(), 1L, "withdraw-1", new BigDecimal("2000"));
                    firstWithdrawalInserted.countDown();
                    if (!retrySnapshotCreated.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("재시도 트랜잭션이 스냅샷을 생성하지 못했습니다.");
                    }
                    Thread.sleep(200);
                    session.commit();
                }
                return null;
            });

            Future<Map<String, Object>> retryRequest = executor.submit(() -> {
                if (!firstWithdrawalInserted.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("첫 출금 트랜잭션이 준비되지 않았습니다.");
                }
                try (SqlSession session = sqlSessionFactory.openSession(false)) {
                    session.getConnection().setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                    DonationMapper mapper = session.getMapper(DonationMapper.class);
                    assertNotNull(mapper.findPotByMemberId("member-1"));
                    retrySnapshotCreated.countDown();
                    assertNotNull(mapper.findPotForUpdate("member-1"));
                    Map<String, Object> existing = mapper.findWithdrawalByIdempotencyKey(
                            "member-1", "withdraw-1");
                    session.commit();
                    return existing;
                }
            });

            firstRequest.get(5, TimeUnit.SECONDS);
            Map<String, Object> existing = retryRequest.get(5, TimeUnit.SECONDS);

            assertNotNull(existing);
            assertEquals(0, new BigDecimal(String.valueOf(existing.get("price")))
                    .compareTo(new BigDecimal("2000")));
        } finally {
            executor.shutdownNow();
        }
    }

    private void insertTransaction(long walletId, String idempotencyKey) {
        jdbcTemplate.update(
                "INSERT INTO `transaction` (wallet_id, auto_tagged, idempotency_key) VALUES (?, 'N', ?)",
                walletId, idempotencyKey);
    }

    private void insertWithdrawal(Connection connection, long walletId,
                                  String idempotencyKey, BigDecimal amount) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO `transaction` "
                        + "(wallet_id, txn_type, price, auto_tagged, idempotency_key, transfer_purpose) "
                        + "VALUES (?, 'TRANSFER', ?, 'N', ?, 'POT_WITHDRAW')")) {
            statement.setLong(1, walletId);
            statement.setBigDecimal(2, amount);
            statement.setString(3, idempotencyKey);
            statement.executeUpdate();
        }
    }

    private SqlSessionFactory createSqlSessionFactory() {
        Configuration configuration = new Configuration();
        configuration.setEnvironment(new Environment(
                "test", new JdbcTransactionFactory(), dataSource));
        String resource = "mapper/donation/DonationMapper.xml";
        try (InputStream inputStream = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(inputStream, configuration, resource,
                    configuration.getSqlFragments()).parse();
        } catch (Exception exception) {
            throw new IllegalStateException("DonationMapper를 초기화하지 못했습니다.", exception);
        }
        return new SqlSessionFactoryBuilder().build(configuration);
    }
}
