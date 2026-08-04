package com.aewol.domain.share.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.aewol.common.exception.BusinessException;
import com.aewol.domain.member.mapper.MemberMapper;
import com.aewol.domain.pet.mapper.PetMapper;
import com.aewol.domain.share.dto.ShareInviteRequest;
import com.aewol.domain.share.dto.ShareInviteResponse;
import com.aewol.domain.share.mapper.ShareMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ShareServiceImplTest {

    @Mock ShareMapper shareMapper;
    @Mock PetMapper petMapper;
    @Mock MemberMapper memberMapper;

    @Test
    @DisplayName("접근 가능한 반려동물 목록을 화면 형식으로 반환한다")
    void should_returnAccessiblePets_when_memberHasOwnedOrSharedPets() {
        ShareServiceImpl service = service();
        when(shareMapper.findAccessiblePets("member-1")).thenReturn(List.of(
                map("id", "pet-1", "name", "보리", "type", "dog", "species", "말티즈")));

        var result = service.getAccessiblePets("member-1");

        assertEquals(1, result.size());
        assertEquals("pet-1", result.get(0).getId());
        assertEquals("dog", result.get(0).getType());
    }

    @Test
    @DisplayName("대표 보호자가 이메일 초대를 만들면 만료일과 초대 코드를 저장한다")
    void should_createInvite_when_ownerInvitesByEmail() {
        ShareServiceImpl service = service();
        ShareInviteRequest request = new ShareInviteRequest();
        ReflectionTestUtils.setField(request, "petId", "pet-1");
        ReflectionTestUtils.setField(request, "recipient", "family@example.com");
        ReflectionTestUtils.setField(request, "role", "VIEWER");
        when(petMapper.findById("pet-1")).thenReturn(map("pet_id", "pet-1", "member_id", "owner-1"));
        when(shareMapper.findMainWalletByMemberId("owner-1"))
                .thenReturn(map("wallet_id", "wallet-1", "balance", BigDecimal.ZERO));
        when(memberMapper.findByEmail("family@example.com")).thenReturn(null);
        when(shareMapper.findActiveInvite("pet-1", "family@example.com", null)).thenReturn(null);

        ShareInviteResponse result = service.invite("owner-1", request);

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(shareMapper).insert(captor.capture());
        assertNotNull(result.getInviteCode());
        assertEquals("pet-1", captor.getValue().get("petId"));
        assertEquals("family@example.com", captor.getValue().get("recipientValue"));
        assertTrue(captor.getValue().get("expiresAt") instanceof LocalDateTime);
    }

    @Test
    @DisplayName("초대 링크를 수락하면 로그인 회원을 공동육아 구성원으로 연결한다")
    void should_acceptInvite_when_linkInviteIsValid() {
        ShareServiceImpl service = service();
        Map<String, Object> invite = map(
                "access_id", "access-1",
                "pet_id", "pet-1",
                "owner_id", "owner-1",
                "recipient_type", "LINK",
                "status", "PENDING",
                "expires_at", LocalDateTime.now().plusDays(1));
        when(shareMapper.findByInviteCode("code-1")).thenReturn(invite);
        when(shareMapper.findAcceptedAccess("pet-1", "member-2")).thenReturn(null);
        when(shareMapper.acceptInvite("access-1", "member-2")).thenReturn(1);

        service.acceptInvite("member-2", "code-1");

        verify(shareMapper).acceptInvite("access-1", "member-2");
    }

    @Test
    @DisplayName("소유자나 참여자가 아니면 공동육아 정보를 조회할 수 없다")
    void should_throwForbidden_when_memberCannotViewPet() {
        ShareServiceImpl service = service();
        when(petMapper.findById("pet-1")).thenReturn(map("pet_id", "pet-1", "member_id", "owner-1"));
        when(shareMapper.findAcceptedAccess("pet-1", "member-2")).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.getMembers("member-2", "pet-1"));

        assertEquals(403, exception.getStatus().value());
    }

    private ShareServiceImpl service() {
        return new ShareServiceImpl(shareMapper, petMapper, memberMapper);
    }

    private static Map<String, Object> map(Object... values) {
        Map<String, Object> result = new HashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            result.put(String.valueOf(values[index]), values[index + 1]);
        }
        return result;
    }
}
