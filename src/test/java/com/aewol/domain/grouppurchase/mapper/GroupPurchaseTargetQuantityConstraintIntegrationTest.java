package com.aewol.domain.grouppurchase.mapper;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * V49의 chk_gp_target_quantity_positive CHECK 제약이 실제로 target_quantity &lt;= 0 INSERT를
 * 막는지 검증한다. H2(GroupPurchaseMapperTest)에는 일부러 이 제약을 넣지 않았다 — 그 테스트
 * 파일의 여러 케이스가 "target_quantity <= 0인 비정상 데이터가 이미 있어도 앱이 방어적으로
 * 처리한다"를 검증하려고 그런 행을 직접 INSERT하는데, CHECK을 추가하면 그 픽스처 자체가
 * 불가능해진다. 그래서 이 제약은 여기서 실제 MySQL 문법(CHECK, ENGINE=InnoDB)으로 별도
 * 검증한다. GroupPurchaseFullTextSearchIntegrationTest와 동일하게 전용 임시 테이블을 쓰고,
 * 로컬/CI에 MySQL이 없으면 건너뛴다.
 */
class GroupPurchaseTargetQuantityConstraintIntegrationTest {

    private static final String URL = "jdbc:mysql://localhost:3307/aewol?useSSL=false&allowPublicKeyRetrieval=true";
    private static final String USER = "aewol";
    private static final String PASSWORD = "aewol1234";
    private static final String TABLE = "gp_target_quantity_check_probe_test";

    private static Connection connection;

    @BeforeAll
    static void setUp() {
        try {
            connection = DriverManager.getConnection(URL, USER, PASSWORD);
        } catch (SQLException e) {
            Assumptions.abort("로컬/CI MySQL(localhost:3307)에 연결할 수 없어 CHECK 제약 통합 테스트를 건너뜁니다: " + e.getMessage());
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS " + TABLE);
            statement.execute("CREATE TABLE " + TABLE
                    + " (id INT PRIMARY KEY AUTO_INCREMENT, target_quantity INT NOT NULL DEFAULT 1,"
                    + " CONSTRAINT chk_probe_target_quantity_positive CHECK (target_quantity > 0)) ENGINE=InnoDB");
        } catch (SQLException e) {
            throw new IllegalStateException("CHECK 제약 테스트 테이블 준비에 실패했습니다.", e);
        }
    }

    @AfterAll
    static void tearDown() {
        if (connection == null) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS " + TABLE);
        } catch (SQLException ignored) {
        } finally {
            try {
                connection.close();
            } catch (SQLException ignored) {
            }
        }
    }

    @Test
    @DisplayName("target_quantity=0 INSERT는 CHECK 제약 위반으로 거부된다")
    void should_rejectInsert_when_targetQuantityIsZero() {
        SQLException exception = assertThrows(SQLException.class, () -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("INSERT INTO " + TABLE + " (target_quantity) VALUES (0)");
            }
        });
        assertTrue(exception.getMessage().contains("chk_probe_target_quantity_positive")
                || exception.getMessage().toLowerCase().contains("check"));
    }

    @Test
    @DisplayName("target_quantity가 양수면 정상적으로 INSERT된다")
    void should_acceptInsert_when_targetQuantityIsPositive() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO " + TABLE + " (target_quantity) VALUES (5)");
        }
    }
}
