package com.aewol.domain.grouppurchase.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aewol.common.storage.FileStorage;
import com.aewol.domain.grouppurchase.dto.GroupPurchaseListResponse;
import com.aewol.domain.grouppurchase.mapper.GroupPurchaseMapper;
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
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;

/**
 * 전체 탭(status=null) 목록 조회가 실 MySQL에 대해 페이지 크기를 넘는 순간(hasNext=true)에도
 * 500 없이 커서를 만들어내는지 검증한다(리뷰로 발견).
 *
 * MySQL Connector/J는 TINYINT(1) 컬럼(group_purchase.is_urgent_active)을 기본 설정
 * (tinyInt1isBit=true)에서 Number가 아니라 Boolean으로 반환한다. resultType="map"인
 * findList는 이 값을 가공 없이 그대로 Map에 담아 GroupPurchaseServiceImpl#toCursor로
 * 넘기는데, 예전 toInt()는 Boolean 분기가 없어 String.valueOf(false)="false"가
 * Integer.parseInt에 들어가 NumberFormatException을 던졌다 — 글이 페이지 크기(10)보다
 * 많아지는 순간부터 "전체" 탭 목록 조회 전체가 500으로 죽는 실제 운영 장애였다.
 *
 * H2(GroupPurchaseMapperTest)는 이 드라이버 특이 동작이 없어(TINYINT을 Number로 반환)
 * 재현하지 못한다 — 그래서 GroupPurchaseUpdateQuantitySetOrderIntegrationTest와 동일하게
 * 실 MySQL(localhost:3307)로 별도 검증한다. group_purchase는 실제 매퍼 문장이 테이블명을
 * 하드코딩하고 있어(파라미터화 불가) 전용 임시 테이블을 쓸 수 없다 — 전용 시드 회원
 * (gp-list-cursor-test@example.test) 소유 행만 만들고 테스트 후 정리한다. 로컬/CI에
 * MySQL이 없으면 건너뛴다.
 */
class GroupPurchaseListMysqlIntegrationTest {

    private static final String URL = "jdbc:mysql://localhost:3307/aewol?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Seoul";
    private static final String USER = "aewol";
    private static final String PASSWORD = "aewol1234";
    private static final String SEED_EMAIL = "gp-list-cursor-test@example.test";
    private static final String KEYWORD = "커서회귀테스트X9Z";

    private Connection connection;
    private long seedMemberId;
    private GroupPurchaseServiceImpl service;

    @BeforeEach
    void setUp() throws SQLException {
        try {
            connection = DriverManager.getConnection(URL, USER, PASSWORD);
        } catch (SQLException e) {
            Assumptions.abort("로컬/CI MySQL(localhost:3307)에 연결할 수 없어 건너뜁니다: " + e.getMessage());
            return;
        }
        seedMemberId = ensureSeedMember();
        GroupPurchaseMapper mapper = createMapper();
        // list()는 walletMapper/transactionMapper/simplePasswordVerificationService를 쓰지
        // 않으므로 null로 둬도 안전하다. fileStorage는 signedUrl(image)만 호출되는데 이 값은
        // 이 테스트가 검증하는 범위(hasNext/nextCursor)가 아니므로 스텁 없는 mock으로 충분하다.
        service = new GroupPurchaseServiceImpl(mapper, org.mockito.Mockito.mock(FileStorage.class), null, null, null, null);
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
    @DisplayName("전체 글 수가 페이지 크기를 넘으면 500 없이 hasNext=true와 다음 커서를 반환한다")
    void should_returnNextCursor_withoutThrowing_when_totalRowCountExceedsPageSize() {
        LocalDateTime now = LocalDateTime.now();
        for (int i = 0; i < 11; i++) {
            insertGroupPurchase(now.plusDays(i + 1));
        }

        // findList는 member_id로 좁혀주지 않는 공개 목록이라, 이 DB에 이미 있는 다른 시드/테스트
        // 계정의 글까지 섞여 페이지 경계가 흔들릴 수 있다 — keyword로 이 테스트가 심은 글만
        // FULLTEXT 부분 일치로 좁혀서 다른 데이터의 존재 여부와 무관하게 만든다.
        GroupPurchaseListResponse firstPage = service.list(null, null, KEYWORD, null, null, 10);

        assertEquals(10, firstPage.getItems().size());
        assertTrue(firstPage.isHasNext());
        assertNotNull(firstPage.getNextCursor());

        GroupPurchaseListResponse secondPage =
                service.list(null, null, KEYWORD, null, firstPage.getNextCursor(), 10);

        assertEquals(1, secondPage.getItems().size());
        assertFalse(secondPage.isHasNext());
    }

    private void insertGroupPurchase(LocalDateTime deadline) {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO group_purchase (member_id, product_name, target_quantity, current_quantity, status, is_urgent_active, deadline, created_at) "
                        + "VALUES (?, ?, 10, 3, 'OPEN', 1, ?, NOW())")) {
            statement.setLong(1, seedMemberId);
            statement.setString(2, "[" + KEYWORD + "] 상품");
            statement.setObject(3, deadline);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private long ensureSeedMember() throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO member (email, password, name, provider, role, zip_code, address) "
                        + "VALUES (?, NULL, '리스트 커서 회귀 테스트', 'LOCAL', 'USER', '00000', '(테스트 전용, 실주소 아님)') "
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

    private GroupPurchaseMapper createMapper() {
        UnpooledDataSource dataSource = new UnpooledDataSource("com.mysql.cj.jdbc.Driver", URL, USER, PASSWORD);
        Configuration configuration = new Configuration();
        configuration.setEnvironment(new Environment("test", new JdbcTransactionFactory(), dataSource));
        String resource = "mapper/grouppurchase/GroupPurchaseMapper.xml";
        try (InputStream inputStream = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(inputStream, configuration, resource, configuration.getSqlFragments()).parse();
        } catch (Exception exception) {
            throw new IllegalStateException("GroupPurchaseMapper를 초기화하지 못했습니다.", exception);
        }
        SqlSessionFactory sqlSessionFactory = new SqlSessionFactoryBuilder().build(configuration);
        // openSession(true)로 세션을 매 호출마다 새로 열면 커넥션을 계속 여는 대신, 스프링이
        // 실제로 쓰는 SqlSessionTemplate과 동일하게 매퍼 프록시 하나로 세션 수명을 위임한다.
        SqlSessionTemplate sqlSessionTemplate = new SqlSessionTemplate(sqlSessionFactory);
        return sqlSessionTemplate.getMapper(GroupPurchaseMapper.class);
    }
}
