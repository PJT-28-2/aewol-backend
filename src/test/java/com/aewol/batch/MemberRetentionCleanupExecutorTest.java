package com.aewol.batch;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aewol.common.cache.MemberAuthStateCache;
import com.aewol.common.storage.FileStorage;
import com.aewol.domain.auth.service.AuthCredentialStore;
import com.aewol.domain.member.mapper.MemberMapper;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

@ExtendWith(MockitoExtension.class)
class MemberRetentionCleanupExecutorTest {

    @Mock MemberMapper memberMapper;
    @Mock FileStorage fileStorage;
    @Mock AuthCredentialStore authCredentialStore;
    @Mock MemberAuthStateCache authStateCache;

    private MemberRetentionCleanupExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new MemberRetentionCleanupExecutor(
                memberMapper, fileStorage, authCredentialStore, authStateCache);
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void eligibleMemberIsPurgedWithSettingsWhileExternalCleanupWaitsForCommit() {
        when(memberMapper.findRetentionCleanupTargetForUpdate(7L))
                .thenReturn(target("profiles/member-7.png"));
        when(memberMapper.purgeMemberIdentity(7L)).thenReturn(1);

        assertTrue(executor.execute(7L));

        verify(memberMapper).anonymizeLinkedAccounts(7L);
        verify(memberMapper).deleteAccountVerifications(7L);
        verify(memberMapper).deleteNotifications(7L);
        verify(memberMapper).deleteNotificationSetting(7L);
        verify(memberMapper).deleteDonationSetting(7L);
        verify(memberMapper).deleteDonationPreferences(7L);
        verify(memberMapper).deleteSupportProgramInterests(7L);
        verify(memberMapper).deleteHomeInsights(7L);
        verify(memberMapper).purgeMemberIdentity(7L);
        verify(authStateCache).evictAfterCommit("7");
        verify(authCredentialStore, never()).deleteRefresh("7");
        verify(fileStorage, never()).delete("profiles/member-7.png");

        TransactionSynchronizationUtils.triggerAfterCommit();

        verify(authCredentialStore).deleteRefresh("7");
        verify(fileStorage).delete("profiles/member-7.png");
    }

    @Test
    void memberThatIsNotEligibleAfterRowLockIsSkippedIdempotently() {
        when(memberMapper.findRetentionCleanupTargetForUpdate(7L)).thenReturn(null);

        assertFalse(executor.execute(7L));
        assertFalse(executor.execute(7L));

        verify(memberMapper, never()).purgeMemberIdentity(7L);
        verifyNoInteractions(fileStorage, authCredentialStore, authStateCache);
    }

    @Test
    void memberWithdrawnTwentyNineDaysAgoIsNotCleanedUp() {
        when(memberMapper.findRetentionCleanupTargetForUpdate(29L)).thenReturn(null);

        assertFalse(executor.execute(29L));

        verify(memberMapper, never()).purgeMemberIdentity(29L);
    }

    @Test
    void memberAtExactlyThirtyDaysIsNotCleanedUp() {
        when(memberMapper.findRetentionCleanupTargetForUpdate(30L)).thenReturn(null);

        assertFalse(executor.execute(30L));

        verify(memberMapper, never()).purgeMemberIdentity(30L);
    }

    @Test
    void failedFinalConditionalUpdateDoesNotScheduleExternalCleanup() {
        when(memberMapper.findRetentionCleanupTargetForUpdate(7L)).thenReturn(target(null));
        when(memberMapper.purgeMemberIdentity(7L)).thenReturn(0);

        assertThrows(IllegalStateException.class, () -> executor.execute(7L));

        verifyNoInteractions(fileStorage, authCredentialStore, authStateCache);
    }

    @Test
    void externalCleanupFailureAfterCommitDoesNotEscapeCompletedDbPurge() {
        when(memberMapper.findRetentionCleanupTargetForUpdate(7L))
                .thenReturn(target("profiles/member-7.png"));
        when(memberMapper.purgeMemberIdentity(7L)).thenReturn(1);
        doThrow(new RuntimeException("redis unavailable"))
                .when(authCredentialStore).deleteRefresh("7");
        doThrow(new RuntimeException("storage unavailable"))
                .when(fileStorage).delete("profiles/member-7.png");

        executor.execute(7L);

        assertDoesNotThrow(TransactionSynchronizationUtils::triggerAfterCommit);
    }

    private Map<String, Object> target(String profileImage) {
        Map<String, Object> target = new HashMap<>();
        target.put("member_id", 7L);
        target.put("profile_img", profileImage);
        return target;
    }
}
