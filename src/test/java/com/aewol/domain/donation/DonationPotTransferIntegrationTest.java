package com.aewol.domain.donation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.aewol.domain.donation.mapper.DonationMapper;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
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
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.jdbc.core.JdbcTemplate;

class DonationPotTransferIntegrationTest {

    private JdbcDataSource dataSource;
    private JdbcTemplate jdbcTemplate;
    private SqlSessionFactory sqlSessionFactory;

    @BeforeEach
    void setUp() {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:pot_transfer_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=8000");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("""
                CREATE TABLE wallet (
                    wallet_id BIGINT NOT NULL AUTO_INCREMENT,
                    member_id VARCHAR(50) NOT NULL,
                    wallet_type VARCHAR(20) NOT NULL,
                    balance DECIMAL(15,2) NOT NULL,
                    updated_at TIMESTAMP NULL,
                    PRIMARY KEY (wallet_id)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE `transaction` (
                    txn_id BIGINT NOT NULL AUTO_INCREMENT,
                    wallet_id BIGINT NOT NULL,
                    counter_wallet_id BIGINT NULL,
                    pet_id BIGINT NULL,
                    txn_type VARCHAR(20) NOT NULL,
                    price DECIMAL(15,2) NOT NULL,
                    category VARCHAR(20) NULL,
                    merchant_name VARCHAR(100) NULL,
                    memo VARCHAR(200) NULL,
                    auto_tagged CHAR(1) NULL DEFAULT 'N',
                    idempotency_key VARCHAR(80) NULL,
                    transfer_purpose VARCHAR(32) NULL,
                    txn_date TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (txn_id),
                    UNIQUE KEY uk_txn_wallet_idempotency_key (wallet_id, idempotency_key)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE donation_setting (
                    member_id VARCHAR(50) NOT NULL,
                    piggy_bank_enabled TINYINT NOT NULL DEFAULT 1,
                    saving_unit DECIMAL(15,2) NOT NULL DEFAULT 1000,
                    auto_donate_enabled TINYINT NOT NULL DEFAULT 0,
                    last_spare_trimmed_on DATE NULL,
                    last_auto_donated_year_month VARCHAR(7) NULL,
                    updated_at TIMESTAMP NULL,
                    PRIMARY KEY (member_id)
                )
                """);
        jdbcTemplate.update(
                "INSERT INTO wallet (wallet_id, member_id, wallet_type, balance) VALUES (1, 'member-1', 'MAIN', 10000)");
        jdbcTemplate.update(
                "INSERT INTO wallet (wallet_id, member_id, wallet_type, balance) VALUES (2, 'member-1', 'DONATION', 5000)");
        jdbcTemplate.update(
                "INSERT INTO donation_setting (member_id, piggy_bank_enabled, auto_donate_enabled) "
                        + "VALUES ('member-1', 1, 1)");
        sqlSessionFactory = createSqlSessionFactory();
    }

    @Test
    @DisplayName("자동 절삭 키와 같은 수동 넣기 키는 네임스페이스·작업 유형으로 분리되어 함께 저장된다")
    void should_keepSpareTrimAndManualDepositSeparate_when_clientKeyMatchesTrimKey() {
        String trimKey = PotTransfer.spareTrimKey("member-1", LocalDate.parse("2026-08-25"));
        String depositKey = PotTransfer.depositKey(trimKey);

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            DonationMapper mapper = session.getMapper(DonationMapper.class);
            mapper.insertWalletTransaction(transfer(1L, 2L, "100", trimKey, PotTransfer.PURPOSE_SPARE_TRIM));
            mapper.insertWalletTransaction(transfer(1L, 2L, "2000", depositKey, PotTransfer.PURPOSE_DEPOSIT));

            assertNotNull(mapper.findDepositByIdempotencyKey("member-1", depositKey));
            assertNull(mapper.findDepositByIdempotencyKey("member-1", trimKey));
        }

        Integer rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM `transaction` WHERE wallet_id = 1", Integer.class);
        assertEquals(2, rows);
    }

    @Test
    @Timeout(10)
    @DisplayName("같은 넣기 키의 동시 요청은 MAIN 잠금 뒤 한 건만 이체하고 나머지는 기존 거래를 읽는다")
    void should_reuseExistingDeposit_when_concurrentRequestsShareKey() throws Exception {
        String depositKey = PotTransfer.depositKey("deposit-same");
        CountDownLatch firstLocked = new CountDownLatch(1);
        CountDownLatch retryWaiting = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> firstRequest = executor.submit(() -> {
                try (SqlSession session = sqlSessionFactory.openSession(false)) {
                    DonationMapper mapper = session.getMapper(DonationMapper.class);
                    assertNotNull(mapper.findMainWalletForUpdate("member-1"));
                    mapper.insertWalletTransaction(transfer(
                            1L, 2L, "2000", depositKey, PotTransfer.PURPOSE_DEPOSIT));
                    firstLocked.countDown();
                    if (!retryWaiting.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("재시도 트랜잭션이 잠금을 기다리지 못했습니다.");
                    }
                    Thread.sleep(200);
                    session.commit();
                }
                return null;
            });

            Future<Map<String, Object>> retryRequest = executor.submit(() -> {
                if (!firstLocked.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("첫 넣기 트랜잭션이 준비되지 않았습니다.");
                }
                try (SqlSession session = sqlSessionFactory.openSession(false)) {
                    DonationMapper mapper = session.getMapper(DonationMapper.class);
                    retryWaiting.countDown();
                    assertNotNull(mapper.findMainWalletForUpdate("member-1"));
                    Map<String, Object> existing = mapper.findDepositByIdempotencyKey(
                            "member-1", depositKey);
                    session.commit();
                    return existing;
                }
            });

            firstRequest.get(8, TimeUnit.SECONDS);
            Map<String, Object> existing = retryRequest.get(8, TimeUnit.SECONDS);

            assertNotNull(existing);
            assertEquals(0, new BigDecimal(String.valueOf(existing.get("price")))
                    .compareTo(new BigDecimal("2000")));
            Integer rows = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM `transaction` WHERE idempotency_key = ?",
                    Integer.class, depositKey);
            assertEquals(1, rows);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @Timeout(10)
    @DisplayName("절삭(MAIN→DONATION→setting)과 자동기부(DONATION→setting)가 겹쳐도 교착 없이 끝난다")
    void should_completeWithoutDeadlock_whenTrimAndAutoDonateOverlap() throws Exception {
        CountDownLatch trimLockedMain = new CountDownLatch(1);
        CountDownLatch autoLockedDonation = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> trim = executor.submit(() -> {
                try (SqlSession session = sqlSessionFactory.openSession(false)) {
                    DonationMapper mapper = session.getMapper(DonationMapper.class);
                    assertNotNull(mapper.findMainWalletForUpdate("member-1"));
                    trimLockedMain.countDown();
                    if (!autoLockedDonation.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("자동기부가 저금통을 잠그지 못했습니다.");
                    }
                    assertNotNull(mapper.findPotForUpdate("member-1"));
                    assertNotNull(mapper.findSettingsForUpdate("member-1"));
                    session.commit();
                }
                return null;
            });

            Future<?> autoDonate = executor.submit(() -> {
                if (!trimLockedMain.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("절삭이 MAIN을 잠그지 못했습니다.");
                }
                try (SqlSession session = sqlSessionFactory.openSession(false)) {
                    DonationMapper mapper = session.getMapper(DonationMapper.class);
                    assertNotNull(mapper.findPotForUpdate("member-1"));
                    autoLockedDonation.countDown();
                    assertNotNull(mapper.findSettingsForUpdate("member-1"));
                    session.commit();
                }
                return null;
            });

            trim.get(8, TimeUnit.SECONDS);
            autoDonate.get(8, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
    }

    private static Map<String, Object> transfer(long sourceWalletId, long counterWalletId,
                                                String amount, String key, String purpose) {
        Map<String, Object> transaction = new java.util.HashMap<>();
        transaction.put("sourceWalletId", sourceWalletId);
        transaction.put("counterWalletId", counterWalletId);
        transaction.put("amount", new BigDecimal(amount));
        transaction.put("memo", "test");
        transaction.put("idempotencyKey", key);
        transaction.put("transferPurpose", purpose);
        return transaction;
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
