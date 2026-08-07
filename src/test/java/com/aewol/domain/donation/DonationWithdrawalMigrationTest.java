package com.aewol.domain.donation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

class DonationWithdrawalMigrationTest {

    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:withdrawal_idempotency_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("""
                CREATE TABLE `transaction` (
                    txn_id BIGINT NOT NULL AUTO_INCREMENT,
                    wallet_id BIGINT NOT NULL,
                    auto_tagged CHAR(1) NULL DEFAULT 'N',
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

    private void insertTransaction(long walletId, String idempotencyKey) {
        jdbcTemplate.update(
                "INSERT INTO `transaction` (wallet_id, auto_tagged, idempotency_key) VALUES (?, 'N', ?)",
                walletId, idempotencyKey);
    }
}
