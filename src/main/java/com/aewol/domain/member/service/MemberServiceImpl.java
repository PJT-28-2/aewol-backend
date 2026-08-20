package com.aewol.domain.member.service;

import com.aewol.common.exception.BusinessException;
import com.aewol.common.util.PhoneNumberUtil;
import com.aewol.domain.auth.service.AuthCredentialStore;
import com.aewol.domain.member.dto.MemberPasswordChangeRequest;
import com.aewol.domain.member.dto.MemberPasswordVerifyRequest;
import com.aewol.domain.member.dto.MemberResponse;
import com.aewol.domain.member.dto.MemberUpdateRequest;
import com.aewol.domain.member.dto.MemberWithdrawRequest;
import com.aewol.domain.member.dto.SimplePasswordRequest;
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

    // 프론트(AccountPasswordSetupView.vue)의 isWeakPin과 동일한 규칙 — 클라이언트 검증은
    // 우회 가능하므로(Postman 등으로 직접 호출) 송금·이체에 쓰이는 PIN인 만큼 서버에서도
    // 동일하게 막는다. 둘 중 하나만 고치면 프론트/서버 판정이 어긋나니 항상 같이 수정할 것.
    private static final java.util.regex.Pattern ALL_SAME_DIGIT = java.util.regex.Pattern.compile("^(\\d)\\1{5}$");
    private static final java.util.regex.Pattern TWO_DIGIT_REPEAT = java.util.regex.Pattern.compile("^(\\d{2})\\1{2}$");
    private static final java.util.regex.Pattern THREE_DIGIT_REPEAT = java.util.regex.Pattern.compile("^(\\d{3})\\1$");

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
                // simple_password 컬럼 값 자체는 절대 내려주지 않고, 설정 여부만 boolean으로
                // 알려준다 — 프론트가 로그인/앱 진입 시마다 이 값으로 accountStore의
                // hasSimplePassword를 서버 기준으로 동기화한다(2026-08-13).
                .hasSimplePassword(member.get("simple_password") != null)
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
        registerPasswordChangeCredentialCleanup(memberId);

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
        if (!normalizedPhone.matches("^010\\d{8}$")) {
            throw new BusinessException("올바른 휴대전화 번호를 입력해주세요.");
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

    @Override
    @Transactional
    public void setSimplePassword(String memberId, SimplePasswordRequest request) {
        Map<String, Object> member = findMember(memberId);
        String existingSimplePassword = (String) member.get("simple_password");

        // 이미 PIN이 설정된 회원의 재설정은 기존 PIN 확인을 거쳐야 한다 — 그렇지 않으면
        // Access Token만 탈취해도 PIN을 새로 설정해 송금 인증을 우회할 수 있다
        // (2026-08-12, 리뷰 반영). 최초 설정(기존 PIN이 없는 회원)은 계좌 연동 직후
        // 흐름이라 별도 확인 없이 허용한다.
        if (existingSimplePassword != null) {
            String currentPassword = request.getCurrentPassword();
            if (!StringUtils.hasText(currentPassword)
                    || !passwordEncoder.matches(currentPassword, existingSimplePassword)) {
                throw new BusinessException(SIMPLE_PASSWORD_MISMATCH_MESSAGE);
            }
        }

        String password = request.getPassword();
        // 프론트(AccountPasswordSetupView.vue)와 동일하게 원인별로 다른 안내 문구를 준다.
        // 연속 숫자 규칙을 먼저 검사해야 "123456"처럼 두 조건에 다 걸리는 값도 더 구체적인
        // "연속된 숫자예요" 메시지가 우선 나간다.
        if (hasSequentialRun(password, MIN_SEQUENTIAL_RUN)) {
            throw new BusinessException(SEQUENTIAL_PIN_MESSAGE);
        }
        if (isOtherWeakPattern(password)) {
            throw new BusinessException(WEAK_PIN_MESSAGE);
        }

        memberMapper.updateSimplePassword(memberId, passwordEncoder.encode(password));
    }

    private static final int MIN_SEQUENTIAL_RUN = 3;
    private static final String WEAK_PIN_MESSAGE = "유추하기 쉬운 비밀번호예요. 다른 숫자를 입력해주세요.";
    private static final String SEQUENTIAL_PIN_MESSAGE = "연속된 숫자예요. 다른 비밀번호를 입력해주세요.";
    private static final String SIMPLE_PASSWORD_MISMATCH_MESSAGE = "기존 간편 비밀번호가 일치하지 않습니다.";

    // 연속 숫자(hasSequentialRun)를 제외한 나머지 취약 패턴 — 전부 같은 숫자 / 두 자리·세 자리
    // 반복 / 두 자리씩 짝지은 오름차순·내림차순.
    private boolean isOtherWeakPattern(String value) {
        if (ALL_SAME_DIGIT.matcher(value).matches()
                || TWO_DIGIT_REPEAT.matcher(value).matches()
                || THREE_DIGIT_REPEAT.matcher(value).matches()) {
            return true;
        }
        return hasAscendingOrDescendingDigitPairs(value);
    }

    // PIN 전체가 아니라 중간 구간에 3자리 이상 연속(오름차순/내림차순)이 섞여 있어도 막는다
    // (예: 451236 → 뒤쪽 "123"에 걸림). 9→0, 0→9로 넘어가는 순환 구간도 포함한다.
    // 예전엔 "0123456789".contains(value)로 PIN 전체가 정확히 6자리 연속일 때만 잡았는데,
    // 이러면 678901 같은 순환 패턴이나 451236처럼 일부만 연속인 경우를 놓쳤다.
    // 프론트(AccountPasswordSetupView.vue)의 hasSequentialRun과 동일한 규칙 — 항상 같이 수정할 것.
    private boolean hasSequentialRun(String value, int minLength) {
        int ascRun = 1;
        int descRun = 1;
        for (int i = 1; i < value.length(); i++) {
            int prev = value.charAt(i - 1) - '0';
            int curr = value.charAt(i) - '0';

            ascRun = (prev + 1) % 10 == curr ? ascRun + 1 : 1;
            descRun = (prev - 1 + 10) % 10 == curr ? descRun + 1 : 1;

            if (ascRun >= minLength || descRun >= minLength) {
                return true;
            }
        }
        return false;
    }

    // 112233 / 998877처럼 두 자리씩 짝지었을 때 오름차순·내림차순으로 이어지는 패턴을 잡는다.
    // 프론트(AccountPasswordSetupView.vue)의 hasAscendingOrDescendingPairs와 동일한 규칙.
    private boolean hasAscendingOrDescendingDigitPairs(String value) {
        int[] pairDigits = new int[value.length() / 2];
        for (int i = 0; i < value.length(); i += 2) {
            if (value.charAt(i) != value.charAt(i + 1)) {
                return false;
            }
            pairDigits[i / 2] = value.charAt(i) - '0';
        }
        if (pairDigits.length != 3) {
            return false;
        }
        int step1 = pairDigits[1] - pairDigits[0];
        int step2 = pairDigits[2] - pairDigits[1];
        return step1 == step2 && Math.abs(step1) == 1;
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

    private void registerPasswordChangeCredentialCleanup(String memberId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    authCredentialStore.deleteRefresh(memberId);
                } catch (RuntimeException e) {
                    log.warn("비밀번호 변경 후 Refresh Token 삭제에 실패했습니다. TTL 만료를 기다립니다.", e);
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
