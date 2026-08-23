package com.aewol.domain.grouppurchase.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * H2는 MySQL의 MATCH...AGAINST 문법을 지원하지 않아(WITH PARSER ngram은 더더욱) GroupPurchaseMapperTest(H2)로는
 * FULLTEXT(ngram) 검색 자체를 검증할 수 없다. 실제 MySQL 8에 대해서만 검증한다 — CI(.github/workflows/ci.yml)는
 * mysql:8 서비스를 localhost:3307에 항상 띄워두고, 로컬 docker-compose.yml 기본 포트도 동일하게 3307이다.
 * 그 외 환경(로컬 MySQL 미기동)에서는 연결 실패를 감지해 조용히 건너뛴다.
 *
 * group_purchase 테이블은 쓰지 않고 전용 임시 테이블에서 검증한다 — CI의 build 단계(이 테스트 포함)는
 * flywayMigrate보다 먼저 실행되어 실제 스키마가 아직 없고, 실제 스키마를 건드리면 다른 테스트와 상태를
 * 공유하게 되어 위험하다.
 */
class GroupPurchaseFullTextSearchIntegrationTest {

    private static final String URL = "jdbc:mysql://localhost:3307/aewol?useSSL=false&allowPublicKeyRetrieval=true";
    private static final String USER = "aewol";
    private static final String PASSWORD = "aewol1234";
    private static final String TABLE = "gp_fulltext_probe_test";

    private static Connection connection;

    @BeforeAll
    static void setUp() {
        try {
            connection = DriverManager.getConnection(URL, USER, PASSWORD);
        } catch (SQLException e) {
            Assumptions.abort("로컬/CI MySQL(localhost:3307)에 연결할 수 없어 FULLTEXT 통합 테스트를 건너뜁니다: " + e.getMessage());
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS " + TABLE);
            statement.execute("CREATE TABLE " + TABLE
                    + " (id INT PRIMARY KEY, product_name VARCHAR(200) NOT NULL) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            statement.execute("CREATE FULLTEXT INDEX ft_probe ON " + TABLE + " (product_name) WITH PARSER ngram");
            statement.execute("INSERT INTO " + TABLE + " (id, product_name) VALUES "
                    + "(1, '프리미엄 츄르 구독팩'), (2, '강아지 사료 5kg'), (3, '고양이 모래 10L')");
        } catch (SQLException e) {
            throw new IllegalStateException("FULLTEXT 테스트 테이블 준비에 실패했습니다.", e);
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
            // 정리 실패는 테스트 결과에 영향 없음 — 다음 실행의 DROP TABLE IF EXISTS가 다시 정리한다.
        } finally {
            try {
                connection.close();
            } catch (SQLException ignored) {
            }
        }
    }

    @Test
    @DisplayName("한글 상품명 중간에 있는 부분 문자열도 ngram FULLTEXT + phrase 검색으로 찾는다 — LIKE '%키워드%'와 같은 포함 검색 의미")
    void should_matchSubstring_insideKoreanProductName() {
        assertEquals(List.of(1), searchIds("츄르"));
    }

    @Test
    @DisplayName("본문에 없는 검색어는 매칭되지 않는다")
    void should_notMatch_whenKeywordNotPresent() {
        assertEquals(List.of(), searchIds("모래사장"));
    }

    @Test
    @DisplayName("검색어에 큰따옴표가 섞여 있어도(직접 이스케이프 전 원문 기준) SQL 오류 없이, 따옴표를 제거한 뒤로도 정상 매칭된다")
    void should_notThrow_andStillMatch_whenKeywordContainsQuote() {
        // GroupPurchaseServiceImpl#toMatchKeyword와 동일하게 phrase 안의 큰따옴표만 공백으로
        // 치환한다 — 그렇지 않으면 phrase가 조기 종료되어 뒤의 텍스트가 boolean 연산자로
        // 해석되거나 구문 오류가 날 수 있다.
        List<Integer> ids = searchIds("사료\"5kg");
        assertTrue(ids.contains(2));
    }

    @Test
    @DisplayName("ngram_token_size(기본값 2) 미만인 한 글자 검색어는 실제로 있는 단어라도 매칭되지 않는다 — 서비스 계층 최소 길이 검증의 근거")
    void should_notMatch_whenKeywordShorterThanNgramTokenSize() {
        // "사료"의 "사" 한 글자만 검색 — 실제 본문에 존재하지만 ngram 색인 대상이 아니라 못 찾는다.
        // GroupPurchaseServiceImpl.MIN_KEYWORD_LENGTH=2가 이 문제를 사전에 막는 이유다.
        assertEquals(List.of(), searchIds("사"));
    }

    private List<Integer> searchIds(String rawKeyword) {
        String phrase = "\"" + rawKeyword.replace("\"", " ") + "\"";
        String sql = "SELECT id FROM " + TABLE + " WHERE MATCH(product_name) AGAINST(? IN BOOLEAN MODE) ORDER BY id";
        List<Integer> ids = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, phrase);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    ids.add(resultSet.getInt("id"));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("FULLTEXT 검색 쿼리 실행에 실패했습니다.", e);
        }
        return ids;
    }
}
