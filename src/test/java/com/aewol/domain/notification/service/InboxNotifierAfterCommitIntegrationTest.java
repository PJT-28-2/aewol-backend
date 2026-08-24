package com.aewol.domain.notification.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.aewol.domain.notification.mapper.NotificationMapper;
import com.aewol.domain.notification.mapper.NotificationSettingMapper;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * afterCommit 시점에 기존 트랜잭션 커넥션이 아직 스레드에 묶여 있어도, 알림 INSERT가
 * REQUIRES_NEW로 따로 커밋되어 행이 남는지 실제 DB로 확인한다.
 */
class InboxNotifierAfterCommitIntegrationTest {

    private JdbcTemplate jdbcTemplate;
    private TransactionTemplate outerTransaction;
    private InboxNotifier notifier;

    @BeforeEach
    void setUp() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:inbox_after_commit_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("""
                CREATE TABLE notification (
                    notification_id BIGINT NOT NULL AUTO_INCREMENT,
                    member_id BIGINT NOT NULL,
                    type VARCHAR(50) NOT NULL,
                    title VARCHAR(100) NOT NULL,
                    message VARCHAR(500) NOT NULL,
                    target_path VARCHAR(500) NULL,
                    event_key VARCHAR(120) NULL,
                    read_at TIMESTAMP NULL,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (notification_id),
                    UNIQUE (event_key)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE notification_setting (
                    member_id VARCHAR(36) NOT NULL,
                    payment_enabled TINYINT NOT NULL DEFAULT 1,
                    recurring_payment_enabled TINYINT NOT NULL DEFAULT 1,
                    family_share_enabled TINYINT NOT NULL DEFAULT 1,
                    community_enabled TINYINT NOT NULL DEFAULT 0,
                    marketing_enabled TINYINT NOT NULL DEFAULT 0,
                    PRIMARY KEY (member_id)
                )
                """);
        jdbcTemplate.update("""
                INSERT INTO notification_setting (
                    member_id, payment_enabled, recurring_payment_enabled,
                    family_share_enabled, community_enabled, marketing_enabled)
                VALUES ('1', 1, 1, 1, 1, 0)
                """);

        DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
        transactionManager.afterPropertiesSet();
        outerTransaction = new TransactionTemplate(transactionManager);

        SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setMapperLocations(new PathMatchingResourcePatternResolver()
                .getResources("classpath:mapper/notification/*.xml"));
        org.apache.ibatis.session.Configuration configuration =
                new org.apache.ibatis.session.Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        factoryBean.setConfiguration(configuration);
        factoryBean.afterPropertiesSet();

        SqlSessionTemplate sqlSessionTemplate = new SqlSessionTemplate(factoryBean.getObject());
        NotificationMapper notificationMapper = sqlSessionTemplate.getMapper(NotificationMapper.class);
        NotificationSettingMapper settingMapper = sqlSessionTemplate.getMapper(NotificationSettingMapper.class);
        NotificationServiceImpl notificationService = new NotificationServiceImpl(notificationMapper);
        InboxNotificationWriter writer = new InboxNotificationWriter(notificationService, transactionManager);
        notifier = new InboxNotifier(writer, settingMapper);
    }

    @Test
    @DisplayName("커밋 뒤에 남긴 알림이 실제 행으로 남아 있다")
    void should_persistNotificationRow_afterOuterTransactionCommits() {
        outerTransaction.executeWithoutResult(status -> {
            notifier.notifyAfterCommit(
                    "1",
                    InboxNotifier.Channel.PAYMENT,
                    "PAYMENT",
                    "결제가 완료됐어요",
                    "가게에서 1000원이 결제됐어요.",
                    "/wallet/history");
            assertEquals(0, notificationCount());
        });

        assertEquals(1, notificationCount());
    }

    @Test
    @DisplayName("같은 event_key로 다시 저장하면 한 행만 남는다")
    void should_keepSingleRow_whenReminderEventKeyIsReplayed() {
        String eventKey = "recurring:1:2026-08-28:RECURRING";
        InboxNotifier.Result first = notifier.notifyQuietly(
                "1", InboxNotifier.Channel.RECURRING, "RECURRING",
                "정기결제가 3일 뒤예요", "예정되어 있어요.", "/payment/recurring", eventKey);
        InboxNotifier.Result second = notifier.notifyQuietly(
                "1", InboxNotifier.Channel.RECURRING, "RECURRING",
                "정기결제가 3일 뒤예요", "예정되어 있어요.", "/payment/recurring", eventKey);

        assertEquals(InboxNotifier.Result.CREATED, first);
        assertEquals(InboxNotifier.Result.DUPLICATE, second);
        assertEquals(1, notificationCount());
    }

    private int notificationCount() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM notification", Integer.class);
        return count == null ? 0 : count;
    }
}
