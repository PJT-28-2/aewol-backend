package com.aewol.domain.member.service;

import com.aewol.common.exception.BusinessException;
import com.aewol.common.util.PhoneNumberUtil;
import com.aewol.domain.auth.service.AuthCredentialStore;
import com.aewol.domain.member.dto.MemberPasswordChangeRequest;
import com.aewol.domain.member.dto.MemberPasswordVerifyRequest;
import com.aewol.domain.member.dto.MemberResponse;
import com.aewol.domain.member.dto.MemberUpdateRequest;
import com.aewol.domain.member.dto.MemberWithdrawRequest;
import com.aewol.domain.member.mapper.MemberMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class MemberServiceImpl implements MemberService {

    private static final String PASSWORD_MISMATCH_MESSAGE = "현재 비밀번호가 일치하지 않습니다.";

    private final MemberMapper memberMapper;
    private final PasswordEncoder passwordEncoder;
    private final AuthCredentialStore authCredentialStore;

    @Override
    public MemberResponse getMember(String memberId) {
        Map<String, Object> member = memberMapper.findById(memberId);
        if (member == null) {
            throw BusinessException.notFound("회원을 찾을 수 없습니다.");
        }
        return MemberResponse.builder()
                .memberId(String.valueOf(member.get("member_id")))
                .email((String) member.get("email"))
                .name((String) member.get("name"))
                .phone((String) member.get("phone"))
                .profileImg((String) member.get("profile_img"))
                .provider((String) member.get("provider"))
                .zipCode((String) member.get("zip_code"))
                .address((String) member.get("address"))
                .addressDetail((String) member.get("address_detail"))
                .build();
    }

    @Override
    @Transactional
    public void updateMember(String memberId, MemberUpdateRequest request) {
        Map<String, Object> member = memberMapper.findById(memberId);
        if (member == null) {
            throw BusinessException.notFound("회원을 찾을 수 없습니다.");
        }

        Map<String, Object> update = new HashMap<>();
        update.put("memberId", memberId);
        update.put("phone", updatedPhone(memberId, request.getPhone(), member.get("phone")));
        update.put("profileImg", request.getProfileImg() != null
                ? request.getProfileImg().trim() : member.get("profile_img"));
        update.put("zipCode", request.getZipCode() != null ? request.getZipCode() : member.get("zip_code"));
        update.put("address", request.getAddress() != null ? request.getAddress() : member.get("address"));
        update.put("addressDetail", request.getAddressDetail() != null
                ? request.getAddressDetail().trim() : member.get("address_detail"));
        validateRequiredProfileFields(request);
        memberMapper.updateProfile(update);
    }

    @Override
    public void verifyPassword(String memberId, MemberPasswordVerifyRequest request) {
        Map<String, Object> member = findMember(memberId);
        requireLocalMember(member, "카카오 회원은 비밀번호 확인 대상이 아닙니다.");
        validateCurrentPassword(member, request.getCurrentPassword());
    }

    @Override
    @Transactional
    public void changePassword(String memberId, MemberPasswordChangeRequest request) {
        Map<String, Object> member = findMember(memberId);
        requireLocalMember(member, "카카오 회원은 비밀번호를 변경할 수 없습니다.");
        validateCurrentPassword(member, request.getCurrentPassword());

        if (request.getCurrentPassword().equals(request.getNewPassword())) {
            throw new BusinessException("새 비밀번호는 현재 비밀번호와 달라야 합니다.");
        }

        String encodedPassword = passwordEncoder.encode(request.getNewPassword());
        if (memberMapper.updatePassword(memberId, encodedPassword) != 1) {
            throw BusinessException.conflict("비밀번호를 변경할 수 없는 회원 상태입니다.");
        }
    }

    @Override
    @Transactional
    public void withdraw(String memberId, MemberWithdrawRequest request) {
        Map<String, Object> member = memberMapper.findById(memberId);
        if (member == null || !isActive(member.get("is_active"))) {
            throw BusinessException.conflict("이미 탈퇴한 회원입니다.");
        }

        String provider = providerOf(member);
        if ("LOCAL".equals(provider)) {
            String currentPassword = request != null ? request.getCurrentPassword() : null;
            validateCurrentPassword(member, currentPassword);
        } else if (!"KAKAO".equals(provider)) {
            throw new IllegalStateException("지원하지 않는 회원 인증 제공자입니다.");
        }

        // 활성 행만 갱신하여 동시에 들어온 탈퇴 요청 중 하나만 성공하도록 한다.
        if (memberMapper.deactivateActiveMember(memberId) != 1) {
            throw BusinessException.conflict("이미 탈퇴한 회원입니다.");
        }

        registerCredentialCleanup(memberId);
    }

    private Map<String, Object> findMember(String memberId) {
        Map<String, Object> member = memberMapper.findById(memberId);
        if (member == null) {
            throw BusinessException.notFound("회원을 찾을 수 없습니다.");
        }
        return member;
    }

    private String updatedPhone(String memberId, String requestedPhone, Object currentPhone) {
        if (requestedPhone == null) {
            return (String) currentPhone;
        }
        if (!StringUtils.hasText(requestedPhone)) {
            throw new BusinessException("전화번호는 비어 있을 수 없습니다.");
        }

        String normalizedPhone = PhoneNumberUtil.normalize(requestedPhone);
        if (!StringUtils.hasText(normalizedPhone)) {
            throw new BusinessException("전화번호는 비어 있을 수 없습니다.");
        }
        if (memberMapper.existsActiveByPhoneExcludingMember(normalizedPhone, memberId)) {
            throw BusinessException.conflict("이미 사용 중인 전화번호입니다.");
        }
        return normalizedPhone;
    }

    private void validateRequiredProfileFields(MemberUpdateRequest request) {
        if (request.getZipCode() != null && !StringUtils.hasText(request.getZipCode())) {
            throw new BusinessException("우편번호는 비어 있을 수 없습니다.");
        }
        if (request.getAddress() != null && !StringUtils.hasText(request.getAddress())) {
            throw new BusinessException("주소는 비어 있을 수 없습니다.");
        }
    }

    private void requireLocalMember(Map<String, Object> member, String kakaoMessage) {
        String provider = providerOf(member);
        if ("KAKAO".equals(provider)) {
            throw new BusinessException(kakaoMessage);
        }
        if (!"LOCAL".equals(provider)) {
            throw new IllegalStateException("지원하지 않는 회원 인증 제공자입니다.");
        }
    }

    private String providerOf(Map<String, Object> member) {
        Object provider = member.get("provider");
        return provider instanceof String ? (String) provider : null;
    }

    private void validateCurrentPassword(Map<String, Object> member, String currentPassword) {
        String storedPassword = (String) member.get("password");
        if (!StringUtils.hasText(currentPassword)
                || storedPassword == null
                || !passwordEncoder.matches(currentPassword, storedPassword)) {
            throw new BusinessException(PASSWORD_MISMATCH_MESSAGE);
        }
    }

    private void registerCredentialCleanup(String memberId) {
        // Redis는 DB 트랜잭션에 참여하지 않으므로 commit 이후에만 인증 세대를 교체하고 Refresh Token을 정리한다.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    authCredentialStore.deleteRefresh(memberId);
                } catch (RuntimeException e) {
                    // DB 탈퇴는 이미 확정됐으므로 성공 응답을 유지한다. 비활성 상태 검사가 기존 credential을 차단한다.
                    log.warn("회원탈퇴 후 인증 credential 정리에 실패했습니다. TTL 만료를 기다립니다.", e);
                }
            }
        });
    }

    private boolean isActive(Object value) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return value instanceof Number && ((Number) value).intValue() == 1;
    }
}
