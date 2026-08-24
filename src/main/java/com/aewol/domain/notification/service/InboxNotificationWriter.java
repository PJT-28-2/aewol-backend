package com.aewol.domain.notification.service;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 알림 INSERT 전용. {@code afterCommit}에서는 기존 트랜잭션 리소스가 아직 스레드에
 * 묶여 있어 {@code REQUIRED}로 저장하면 이미 커밋된 트랜잭션에 참여한 것으로 보고
 * INSERT가 확정되지 않을 수 있다. 항상 {@code REQUIRES_NEW}로 독립 커밋한다.
 */
@Component
public class InboxNotificationWriter {

    private final NotificationService notificationService;
    private final TransactionTemplate requiresNew;

    public InboxNotificationWriter(
            NotificationService notificationService,
            PlatformTransactionManager transactionManager) {
        this.notificationService = notificationService;
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.requiresNew = template;
    }

    public String write(
            String memberId,
            String type,
            String title,
            String message,
            String targetPath,
            String eventKey) {
        return requiresNew.execute(status -> notificationService.createNotification(
                memberId, type, title, message, targetPath, eventKey));
    }
}
