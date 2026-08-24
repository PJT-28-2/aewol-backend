package com.aewol.domain.share.service;

import com.aewol.common.exception.BusinessException;
import com.aewol.domain.member.mapper.MemberMapper;
import com.aewol.domain.notification.service.InboxNotifier;
import com.aewol.domain.pet.mapper.PetMapper;
import com.aewol.domain.share.dto.*;
import com.aewol.domain.share.mapper.ShareMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ShareServiceImpl implements ShareService {

    /**
     * 링크 초대 기본 유효시간.
     *
     * <p>받는 사람을 지정하지 않는 초대라, 링크가 살아 있는 동안은 그것을 본 누구나
     * 들어올 수 있다. 짧게 잡아 두고 필요하면 다시 만들게 하는 편이 안전하다.
     */
    private static final int DEFAULT_LINK_TTL_MINUTES = 10;

    /** 받는 사람을 지정한 초대는 그 계정만 수락할 수 있어 길게 열어둬도 된다. */
    private static final int RECIPIENT_INVITE_TTL_MINUTES = 7 * 24 * 60;

    private static final Set<String> ROLES = Set.of("VIEWER", "MANAGER", "ADMIN");
    private static final Set<String> RESPONSES = Set.of("REJECTED", "REVOKED");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final Pattern PHONE_PATTERN = Pattern.compile("^01[016789]\\d{7,8}$");

    private final ShareMapper shareMapper;
    private final PetMapper petMapper;
    private final MemberMapper memberMapper;
    private final InboxNotifier inboxNotifier;

    @Override
    public List<SharePetResponse> getAccessiblePets(String memberId) {
        return shareMapper.findAccessiblePets(requireMemberId(memberId)).stream()
                .map(row -> SharePetResponse.builder()
                        .id(text(row, "id"))
                        .name(text(row, "name"))
                        .type(normalizePetType(text(row, "type")))
                        .species(text(row, "species"))
                        .build())
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public ShareInviteResponse invite(String memberId, ShareInviteRequest request) {
        String recipient = request.getRecipient().trim();
        String normalized;
        String recipientType;
        Map<String, Object> targetMember;

        if (EMAIL_PATTERN.matcher(recipient).matches()) {
            recipientType = "EMAIL";
            normalized = recipient.toLowerCase(Locale.ROOT);
            targetMember = memberMapper.findActiveByEmail(normalized);
        } else {
            normalized = recipient.replaceAll("\\D", "");
            if (!PHONE_PATTERN.matcher(normalized).matches()) {
                throw new BusinessException("올바른 이메일 또는 휴대전화 번호를 입력해 주세요.");
            }
            recipientType = "PHONE";
            targetMember = shareMapper.findMemberByPhone(normalized);
        }

        String targetMemberId = targetMember == null ? null : text(targetMember, "member_id", "memberId");
        return createInvite(memberId, request.getPetId(), targetMemberId, recipientType, normalized,
                request.getRole(), RECIPIENT_INVITE_TTL_MINUTES);
    }

    @Override
    @Transactional
    public ShareInviteResponse createLinkInvite(String memberId, ShareLinkInviteRequest request) {
        Integer requested = request.getExpiresInMinutes();
        return createInvite(memberId, request.getPetId(), null, "LINK", null, request.getRole(),
                requested == null ? DEFAULT_LINK_TTL_MINUTES : requested);
    }

    @Override
    @Transactional
    public ShareInviteDetailResponse getInvite(String inviteCode) {
        Map<String, Object> invite = getValidInvite(inviteCode);
        return ShareInviteDetailResponse.builder()
                .accessId(text(invite, "access_id", "accessId"))
                .petId(text(invite, "pet_id", "petId"))
                .petName(text(invite, "pet_name", "petName"))
                .inviterName(text(invite, "inviter_name", "inviterName"))
                .role(text(invite, "role"))
                .status(text(invite, "status"))
                .expiresAt(dateTimeText(value(invite, "expires_at", "expiresAt")))
                .build();
    }

    @Override
    @Transactional
    public void acceptInvite(String memberId, String inviteCode) {
        memberId = requireMemberId(memberId);
        Map<String, Object> invite = getValidInvite(inviteCode);
        String petId = text(invite, "pet_id", "petId");
        String ownerId = text(invite, "owner_id", "ownerId");
        if (memberId.equals(ownerId)) {
            throw BusinessException.conflict("본인이 소유한 반려동물의 초대는 수락할 수 없습니다.");
        }
        validateRecipientMatch(memberId, invite);
        if (shareMapper.findAcceptedAccess(petId, memberId) != null) {
            throw BusinessException.conflict("이미 공동육아에 참여하고 있습니다.");
        }
        if (shareMapper.acceptInvite(text(invite, "access_id", "accessId"), memberId) != 1) {
            throw BusinessException.conflict("이미 처리된 초대입니다.");
        }
        String petName = text(invite, "pet_name", "petName");
        inboxNotifier.notifyAfterCommit(
                ownerId,
                InboxNotifier.Channel.FAMILY,
                "FAMILY_SHARE",
                "공동육아에 새 가족이 왔어요",
                (petName == null || petName.isBlank() ? "반려동물" : petName)
                        + " 공동육아 초대가 수락됐어요.",
                "/share");
    }

    @Override
    @Transactional
    public void respondInvite(String memberId, String accessId, String status) {
        memberId = requireMemberId(memberId);
        status = status == null ? "" : status.toUpperCase(Locale.ROOT);
        if ("ACCEPTED".equals(status)) {
            Map<String, Object> invite = shareMapper.findByAccessId(accessId);
            if (invite == null) throw BusinessException.notFound("초대를 찾을 수 없습니다.");
            acceptInvite(memberId, text(invite, "invite_code", "inviteCode"));
            return;
        }
        if (!RESPONSES.contains(status)) {
            throw new BusinessException("지원하지 않는 초대 응답입니다.");
        }
        if (shareMapper.updateStatus(accessId, memberId, status) != 1) {
            throw BusinessException.notFound("처리할 초대를 찾을 수 없습니다.");
        }
    }

    @Override
    public List<ShareMemberResponse> getMembers(String memberId, String petId) {
        assertCanView(requireMemberId(memberId), petId);
        return shareMapper.findMembersByPetId(petId).stream()
                .map(row -> ShareMemberResponse.builder()
                        .id(text(row, "id"))
                        .name(text(row, "name"))
                        .role(text(row, "role"))
                        .status(text(row, "status"))
                        .build())
                .collect(Collectors.toList());
    }

    @Override
    public List<ShareContributionResponse> getContributions(String memberId, String petId) {
        assertCanView(requireMemberId(memberId), petId);
        List<Map<String, Object>> rows = shareMapper.findMonthlyContributions(petId);
        BigDecimal total = rows.stream()
                .map(row -> decimal(row, "amount"))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<ShareContributionResponse> result = new ArrayList<>();
        int assigned = 0;
        for (int index = 0; index < rows.size(); index++) {
            Map<String, Object> row = rows.get(index);
            int percentage;
            if (total.signum() == 0) {
                percentage = 0;
            } else if (index == rows.size() - 1) {
                percentage = Math.max(100 - assigned, 0);
            } else {
                percentage = decimal(row, "amount")
                        .multiply(BigDecimal.valueOf(100))
                        .divide(total, 0, RoundingMode.HALF_UP)
                        .intValue();
                assigned += percentage;
            }
            result.add(ShareContributionResponse.builder()
                    .id(text(row, "id"))
                    .name(text(row, "name"))
                    .amount(decimal(row, "amount"))
                    .percentage(percentage)
                    .build());
        }
        return result;
    }

    @Override
    public List<ShareActivityResponse> getLogs(String memberId, String petId) {
        assertCanView(requireMemberId(memberId), petId);
        return shareMapper.findLogsByPetId(petId).stream()
                .map(row -> ShareActivityResponse.builder()
                        .id(text(row, "id"))
                        .title(text(row, "title"))
                        .description(text(row, "description"))
                        .createdAt(dateTimeText(value(row, "createdAt", "created_at")))
                        .build())
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public void updateRole(String ownerId, String memberId, ShareRoleUpdateRequest request) {
        assertOwner(requireMemberId(ownerId), request.getPetId());
        String role = normalizeRole(request.getRole());
        if (shareMapper.updateRole(request.getPetId(), memberId, role) != 1) {
            throw BusinessException.notFound("공동육아 구성원을 찾을 수 없습니다.");
        }
    }

    @Override
    @Transactional
    public void removeMember(String ownerId, String petId, String memberId) {
        assertOwner(requireMemberId(ownerId), petId);
        if (shareMapper.revoke(petId, memberId) != 1) {
            throw BusinessException.notFound("공동육아 구성원을 찾을 수 없습니다.");
        }
    }

    private ShareInviteResponse createInvite(String inviterId, String petId, String targetMemberId,
                                               String recipientType, String recipientValue, String role,
                                               int ttlMinutes) {
        inviterId = requireMemberId(inviterId);
        Map<String, Object> pet = assertOwner(inviterId, petId);
        if (inviterId.equals(targetMemberId)) {
            throw BusinessException.conflict("본인에게 초대를 보낼 수 없습니다.");
        }
        if (!"LINK".equals(recipientType)
                && shareMapper.findActiveInvite(petId, recipientValue, targetMemberId) != null) {
            throw BusinessException.conflict("이미 초대했거나 참여 중인 사용자입니다.");
        }
        Map<String, Object> wallet = shareMapper.findMainWalletByMemberId(text(pet, "member_id", "memberId"));
        if (wallet == null) throw BusinessException.notFound("반려동물 소유자의 지갑을 찾을 수 없습니다.");

        String inviteCode = UUID.randomUUID().toString();
        Map<String, Object> access = new HashMap<>();
        access.put("walletId", text(wallet, "wallet_id", "walletId"));
        access.put("petId", petId);
        access.put("memberId", targetMemberId);
        access.put("invitedBy", inviterId);
        access.put("inviteCode", inviteCode);
        access.put("recipientType", recipientType);
        access.put("recipientValue", recipientValue);
        access.put("role", normalizeRole(role));
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(ttlMinutes);
        access.put("expiresAt", expiresAt);
        shareMapper.insert(access);
        // access_id는 AUTO_INCREMENT — useGeneratedKeys가 파라미터 맵에 채워준다
        return ShareInviteResponse.builder()
                .accessId(text(access, "accessId"))
                .inviteCode(inviteCode)
                .expiresAt(dateTimeText(expiresAt))
                .build();
    }

    private Map<String, Object> getValidInvite(String inviteCode) {
        if (inviteCode == null || inviteCode.isBlank()) {
            throw new BusinessException("초대 코드가 필요합니다.");
        }
        Map<String, Object> invite = shareMapper.findByInviteCode(inviteCode.trim());
        if (invite == null) throw BusinessException.notFound("초대를 찾을 수 없습니다.");
        if (!"PENDING".equals(text(invite, "status"))) {
            throw BusinessException.conflict("이미 처리되었거나 사용할 수 없는 초대입니다.");
        }
        LocalDateTime expiresAt = localDateTime(value(invite, "expires_at", "expiresAt"));
        if (expiresAt != null && expiresAt.isBefore(LocalDateTime.now())) {
            shareMapper.markExpired(text(invite, "access_id", "accessId"));
            throw BusinessException.conflict("초대 링크가 만료되었습니다.");
        }
        return invite;
    }

    private void validateRecipientMatch(String memberId, Map<String, Object> invite) {
        String recipientType = text(invite, "recipient_type", "recipientType");
        if ("LINK".equals(recipientType)) return;
        Map<String, Object> member = memberMapper.findById(memberId);
        if (member == null) throw BusinessException.notFound("회원 정보를 찾을 수 없습니다.");
        String recipientValue = text(invite, "recipient_value", "recipientValue");
        String actual = "EMAIL".equals(recipientType)
                ? Optional.ofNullable(text(member, "email")).orElse("").toLowerCase(Locale.ROOT)
                : Optional.ofNullable(text(member, "phone")).orElse("").replaceAll("\\D", "");
        if (!recipientValue.equals(actual)) {
            throw BusinessException.forbidden("초대받은 계정으로 로그인해 주세요.");
        }
    }

    private Map<String, Object> assertOwner(String memberId, String petId) {
        Map<String, Object> pet = petMapper.findById(petId);
        if (pet == null) throw BusinessException.notFound("반려동물을 찾을 수 없습니다.");
        if (!memberId.equals(text(pet, "member_id", "memberId"))) {
            throw BusinessException.forbidden("대표 보호자만 이 작업을 할 수 있습니다.");
        }
        return pet;
    }

    private void assertCanView(String memberId, String petId) {
        Map<String, Object> pet = petMapper.findById(petId);
        if (pet == null) throw BusinessException.notFound("반려동물을 찾을 수 없습니다.");
        if (!memberId.equals(text(pet, "member_id", "memberId"))
                && shareMapper.findAcceptedAccess(petId, memberId) == null) {
            throw BusinessException.forbidden("공동육아 정보를 조회할 권한이 없습니다.");
        }
    }

    private String normalizeRole(String role) {
        String normalized = role == null ? "VIEWER" : role.toUpperCase(Locale.ROOT);
        if (!ROLES.contains(normalized)) throw new BusinessException("지원하지 않는 권한입니다.");
        return normalized;
    }

    private String requireMemberId(String memberId) {
        if (memberId == null || memberId.isBlank()) throw BusinessException.unauthorized("로그인이 필요합니다.");
        return memberId;
    }

    private String normalizePetType(String type) {
        if (type == null) return "dog";
        return type.contains("cat") ? "cat" : "dog";
    }

    private static Object value(Map<String, Object> map, String... keys) {
        for (String key : keys) if (map.containsKey(key)) return map.get(key);
        return null;
    }

    private static String text(Map<String, Object> map, String... keys) {
        Object value = value(map, keys);
        return value == null ? null : String.valueOf(value);
    }

    private static BigDecimal decimal(Map<String, Object> map, String key) {
        Object value = value(map, key);
        if (value == null) return BigDecimal.ZERO;
        return value instanceof BigDecimal ? (BigDecimal) value : new BigDecimal(String.valueOf(value));
    }

    private static LocalDateTime localDateTime(Object value) {
        if (value instanceof LocalDateTime) return (LocalDateTime) value;
        if (value instanceof Timestamp) return ((Timestamp) value).toLocalDateTime();
        return null;
    }

    private static String dateTimeText(Object value) {
        LocalDateTime dateTime = localDateTime(value);
        return dateTime == null ? null : dateTime.toString();
    }
}
