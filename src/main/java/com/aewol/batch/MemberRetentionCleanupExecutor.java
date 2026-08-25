package com.aewol.batch;

import com.aewol.common.cache.MemberAuthStateCache;
import com.aewol.common.storage.FileStorage;
import com.aewol.domain.auth.service.AuthCredentialStore;
import com.aewol.domain.member.mapper.MemberMapper;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 탈퇴 후 30일을 초과한 회원 한 명을 독립 트랜잭션으로 비식별 처리한다. */
@Component
@RequiredArgsConstructor
@Slf4j
public class MemberRetentionCleanupExecutor {

    private final MemberMapper memberMapper;
    private final FileStorage fileStorage;
    private final AuthCredentialStore authCredentialStore;
    private final MemberAuthStateCache authStateCache;

    /**
     * @return 비식별 처리했으면 {@code true}, 복구·선행 cleanup 등으로 대상이 아니면 {@code false}
     */
    @Transactional
    public boolean execute(Long memberId) {
        Map<String, Object> locked = memberMapper.findRetentionCleanupTargetForUpdate(memberId);
        if (locked == null) {
            return false;
        }

        String profileImageKey = text(locked.get("profile_img"));

        // 계좌 row와 member FK는 유지한다. 계좌 identity만 제거하고 더 이상 사용되지 않게 한다.
        memberMapper.anonymizeLinkedAccounts(memberId);
        memberMapper.deleteAccountVerifications(memberId);

        // 계정 종료 뒤 의미가 없는 설정·캐시성 데이터만 제거한다.
        memberMapper.deleteNotifications(memberId);
        memberMapper.deleteNotificationSetting(memberId);
        memberMapper.deleteDonationSetting(memberId);
        memberMapper.deleteDonationPreferences(memberId);
        memberMapper.deleteSupportProgramInterests(memberId);
        memberMapper.deleteHomeInsights(memberId);

        if (memberMapper.purgeMemberIdentity(memberId) != 1) {
            throw new IllegalStateException("회원 영구 비식별 처리 조건이 트랜잭션 안에서 변경되었습니다.");
        }

        String memberIdValue = String.valueOf(memberId);
        authStateCache.evictAfterCommit(memberIdValue);
        registerExternalCleanup(memberIdValue, profileImageKey);
        return true;
    }

    private void registerExternalCleanup(String memberId, String profileImageKey) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    authCredentialStore.deleteRefresh(memberId);
                } catch (RuntimeException e) {
                    log.warn("[MEMBER_RETENTION_REFRESH_CLEANUP_FAILED] memberId={}", memberId, e);
                }

                if (profileImageKey == null) {
                    return;
                }
                try {
                    fileStorage.delete(profileImageKey);
                } catch (RuntimeException e) {
                    // FileStorage 구현 계약은 실패를 삼키지만 잘못된 구현도 DB purge를 되돌리지 못하게 한다.
                    log.warn("[MEMBER_RETENTION_PROFILE_CLEANUP_FAILED] memberId={}", memberId, e);
                }
            }
        });
    }

    private String text(Object value) {
        if (!(value instanceof String) || ((String) value).isBlank()) {
            return null;
        }
        return (String) value;
    }
}
