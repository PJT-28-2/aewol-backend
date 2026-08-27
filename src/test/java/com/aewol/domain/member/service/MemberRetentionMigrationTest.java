package com.aewol.domain.member.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class MemberRetentionMigrationTest {

    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:member_retention_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("""
                CREATE TABLE member (
                    member_id BIGINT NOT NULL AUTO_INCREMENT,
                    email VARCHAR(100) NOT NULL,
                    password VARCHAR(255) NULL,
                    simple_password VARCHAR(255) NULL,
                    name VARCHAR(20) NOT NULL,
                    phone VARCHAR(20) NULL,
                    profile_img VARCHAR(500) NULL,
                    provider VARCHAR(10) NOT NULL DEFAULT 'LOCAL',
                    provider_id VARCHAR(100) NULL,
                    email_verified CHAR(1) NOT NULL DEFAULT 'N',
                    is_active TINYINT NOT NULL DEFAULT 1,
                    withdrawn_at DATETIME NULL,
                    zip_code VARCHAR(10) NOT NULL,
                    address VARCHAR(300) NOT NULL,
                    address_detail VARCHAR(300) NULL,
                    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (member_id)
                )
                """);

        assertEquals("NO", nullable("zip_code"));
        assertEquals("NO", nullable("address"));

        Flyway.configure()
                .dataSource(dataSource)
                .locations("filesystem:src/main/resources/db/migration")
                .baselineOnMigrate(true)
                .baselineVersion(MigrationVersion.fromVersion("58"))
                .target(MigrationVersion.fromVersion("59"))
                .load()
                .migrate();
    }

    @Test
    void v59AddsPurgeTimestampNullablePiiAndCleanupIndex() {
        assertEquals("YES", nullable("email"));
        assertEquals("YES", nullable("name"));
        assertEquals("YES", nullable("zip_code"));
        assertEquals("YES", nullable("address"));
        assertEquals("YES", nullable("purged_at"));
        assertNotNull(jdbcTemplate.queryForObject("""
                SELECT index_name
                FROM information_schema.indexes
                WHERE table_name = 'member'
                  AND index_name = 'idx_member_retention_cleanup'
                """, String.class));
    }

    @Test
    void migratedSchemaAllowsActualMemberIdentityPurgeUpdate() {
        jdbcTemplate.update("""
                INSERT INTO member (
                    email, password, simple_password, name, phone, profile_img,
                    provider, provider_id, email_verified, is_active, withdrawn_at,
                    zip_code, address, address_detail
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                "withdrawn@example.com", "encoded-password", "encoded-pin", "탈퇴회원",
                "01012345678", "profiles/member.png", "KAKAO", "kakao-provider-id", "Y", 0,
                LocalDateTime.now().minusDays(31), "12345", "서울시 중구", "101호");

        // H2 MySQL mode에는 DATE_SUB 함수가 없으므로 경계 조건만 동등한 DATEADD로 표현한다.
        // 나머지 할당과 조건은 MemberMapper.purgeMemberIdentity SQL과 동일하다.
        int updated = jdbcTemplate.update("""
                UPDATE member
                SET email = NULL,
                    password = NULL,
                    simple_password = NULL,
                    name = NULL,
                    phone = NULL,
                    profile_img = NULL,
                    provider_id = NULL,
                    zip_code = NULL,
                    address = NULL,
                    address_detail = NULL,
                    email_verified = 'N',
                    purged_at = NOW(),
                    updated_at = NOW()
                WHERE member_id = 1
                  AND is_active = 0
                  AND withdrawn_at < DATEADD('DAY', -30, NOW())
                  AND purged_at IS NULL
                """);

        assertEquals(1, updated);
        Map<String, Object> purged = jdbcTemplate.queryForMap("""
                SELECT email, name, zip_code, address, provider_id, purged_at, is_active
                FROM member
                WHERE member_id = 1
                """);
        assertNull(purged.get("email"));
        assertNull(purged.get("name"));
        assertNull(purged.get("zip_code"));
        assertNull(purged.get("address"));
        assertNull(purged.get("provider_id"));
        assertNotNull(purged.get("purged_at"));
        assertEquals(0, ((Number) purged.get("is_active")).intValue());
    }

    private String nullable(String column) {
        return jdbcTemplate.queryForObject("""
                SELECT is_nullable
                FROM information_schema.columns
                WHERE table_name = 'member' AND column_name = ?
                """, String.class, column);
    }
}
