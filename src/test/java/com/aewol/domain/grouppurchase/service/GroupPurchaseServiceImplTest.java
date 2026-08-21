package com.aewol.domain.grouppurchase.service;

import com.aewol.common.storage.FileStorage;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.aewol.common.exception.BusinessException;
import com.aewol.domain.grouppurchase.dto.GroupPurchaseCancelResponse;
import com.aewol.domain.grouppurchase.dto.GroupPurchaseCreateRequest;
import com.aewol.domain.grouppurchase.dto.GroupPurchaseImageUploadResponse;
import com.aewol.domain.grouppurchase.dto.GroupPurchaseJoinRequest;
import com.aewol.domain.grouppurchase.dto.GroupPurchaseJoinResponse;
import com.aewol.domain.grouppurchase.dto.GroupPurchaseLeaveResponse;
import com.aewol.domain.grouppurchase.dto.GroupPurchaseListItemResponse;
import com.aewol.domain.grouppurchase.dto.GroupPurchaseListResponse;
import com.aewol.domain.grouppurchase.dto.GroupPurchaseMyItemResponse;
import com.aewol.domain.grouppurchase.dto.GroupPurchaseResponse;
import com.aewol.domain.grouppurchase.dto.GroupPurchaseStatusResponse;
import com.aewol.domain.grouppurchase.mapper.GroupPurchaseMapper;
import com.aewol.domain.member.service.SimplePasswordVerificationService;
import com.aewol.domain.transaction.mapper.TransactionMapper;
import com.aewol.domain.wallet.mapper.WalletMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

@ExtendWith(MockitoExtension.class)
class GroupPurchaseServiceImplTest {

    @Mock GroupPurchaseMapper groupPurchaseMapper;
    @Mock FileStorage fileStorage;
    @Mock WalletMapper walletMapper;
    @Mock TransactionMapper transactionMapper;
    @Mock SimplePasswordVerificationService simplePasswordVerificationService;

    private static final String PASSWORD = "123456";

    // 마감 시각을 절대 날짜로 박으면 그 날짜가 지나는 순간 computeStatus가 OPEN 대신 FAILED를
    // 돌려줘 테스트가 시한폭탄이 된다. 실제로 2026-08-15에 터졌다(픽스처 마감이 2026-08-15 00:00,
    // 기대값은 OPEN). 이 파일의 다른 테스트들이 이미 쓰는 방식대로 현재 시각 기준 상대값으로 잡는다.
    private static final LocalDateTime DEADLINE = LocalDate.now().plusDays(5).atStartOfDay();
    /** 배송예정일은 admin 입력값이 아니라 deadline + deliveryEstimateDays(5일)로 계산된 잠정치다. */
    private static final LocalDate DELIVERY_DATE = DEADLINE.toLocalDate().plusDays(5);
    private static final LocalDateTime CREATED_AT = DEADLINE.minusDays(9);

    @Test
    @DisplayName("허용된 확장자의 이미지를 업로드하면 저장된 이미지 URL을 반환한다")
    void should_returnImageUrl_when_uploadSucceeds() throws IOException {
        GroupPurchaseServiceImpl service = service();
        MultipartFile image = new MockMultipartFile("image", "product.png", "image/png", "content".getBytes());
        when(fileStorage.store(any(), eq("group-purchase"), eq("png")))
                .thenReturn("group-purchase/product.png");

        GroupPurchaseImageUploadResponse result = service.uploadImage(image);

        // 업로드 응답은 저장 키를 그대로 돌려준다. 클라이언트는 이 값을 화면에 쓰지 않고
        // 등록 요청의 image 필드로 되돌려 보낸다.
        assertEquals("group-purchase/product.png", result.getImageUrl());
        verify(fileStorage).store(any(), eq("group-purchase"), eq("png"));
    }

    @Test
    @DisplayName("빈 파일을 업로드하면 예외가 발생한다")
    void should_throwException_when_imageIsEmpty() {
        GroupPurchaseServiceImpl service = service();
        MultipartFile image = new MockMultipartFile("image", "product.png", "image/png", new byte[0]);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.uploadImage(image));

        assertEquals("업로드할 이미지가 없습니다.", exception.getMessage());
        verifyNoInteractions(fileStorage);
    }

    @Test
    @DisplayName("허용되지 않은 확장자를 업로드하면 예외가 발생한다")
    void should_throwException_when_extensionIsNotAllowed() {
        GroupPurchaseServiceImpl service = service();
        MultipartFile image = new MockMultipartFile("image", "product.gif", "image/gif", "content".getBytes());

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.uploadImage(image));

        assertEquals("이미지 파일(jpg, jpeg, png, webp)만 업로드할 수 있습니다.", exception.getMessage());
        verifyNoInteractions(fileStorage);
    }

    @Test
    @DisplayName("파일 저장에 실패하면 저장소가 던진 예외가 그대로 전달된다")
    void should_throwException_when_fileStorageFails() {
        GroupPurchaseServiceImpl service = service();
        MultipartFile image = new MockMultipartFile("image", "product.jpg", "image/jpeg", "content".getBytes());
        when(fileStorage.store(any(), eq("group-purchase"), eq("jpg")))
                .thenThrow(new BusinessException("파일을 저장하지 못했어요. 잠시 후 다시 시도해 주세요."));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.uploadImage(image));

        assertEquals("파일을 저장하지 못했어요. 잠시 후 다시 시도해 주세요.", exception.getMessage());
    }

    @Test
    @DisplayName("유효한 상품등록 요청이면 공동구매를 생성하고 저장된 정보를 반환한다")
    void should_createGroupPurchase_when_requestIsValid() {
        GroupPurchaseServiceImpl service = service();
        GroupPurchaseCreateRequest request = createRequest();
        doAnswer(invocation -> {
            Map<String, Object> gp = invocation.getArgument(0);
            gp.put("gpId", "1");
            return null;
        }).when(groupPurchaseMapper).insert(anyMap());
        when(groupPurchaseMapper.findById("1")).thenReturn(savedRow());

        GroupPurchaseResponse result = service.create("member-1", request);

        assertEquals("1", result.getGpId());
        assertEquals("member-1", result.getMemberId());
        assertEquals("사료 5kg", result.getProductName());
        assertEquals("사료", result.getCategory());
        assertEquals(new BigDecimal("30000"), result.getUnitPrice());
        assertEquals(new BigDecimal("25000"), result.getGroupPrice());
        assertEquals(10, result.getTargetQuantity());
        assertEquals(0, result.getCurrentQuantity());
        assertEquals("OPEN", result.getStatus());
        assertEquals(DELIVERY_DATE, result.getDeliveryDate());
        assertEquals(5, result.getDeliveryEstimateDays());
        assertEquals(DEADLINE, result.getDeadline());

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(groupPurchaseMapper).insert(captor.capture());
        assertEquals("member-1", captor.getValue().get("memberId"));
        assertEquals("사료 5kg", captor.getValue().get("productName"));
        assertEquals(10, captor.getValue().get("targetQuantity"));
        // deliveryDate는 admin 입력값이 아니라 deadline + deliveryEstimateDays(5일)로 계산된 잠정치여야 한다.
        assertEquals(DELIVERY_DATE, captor.getValue().get("deliveryDate"));
        assertEquals(5, captor.getValue().get("deliveryEstimateDays"));
    }

    @Test
    @DisplayName("배송 예상 소요일을 입력하지 않으면 배송예정일 잠정치를 계산하지 않고 null로 저장한다")
    void should_saveNullDeliveryDate_when_deliveryEstimateDaysIsNotProvided() {
        GroupPurchaseServiceImpl service = service();
        GroupPurchaseCreateRequest request = createRequest();
        ReflectionTestUtils.setField(request, "deliveryEstimateDays", null);
        doAnswer(invocation -> {
            Map<String, Object> gp = invocation.getArgument(0);
            gp.put("gpId", "1");
            return null;
        }).when(groupPurchaseMapper).insert(anyMap());
        when(groupPurchaseMapper.findById("1")).thenReturn(savedRow());

        service.create("member-1", request);

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(groupPurchaseMapper).insert(captor.capture());
        assertNull(captor.getValue().get("deliveryDate"));
        assertNull(captor.getValue().get("deliveryEstimateDays"));
    }

    @Test
    @DisplayName("참여 중인 회원이 조회하면 상세 정보와 함께 isParticipating이 true로 내려간다")
    void should_returnGroupPurchaseDetail_when_gpExists() {
        GroupPurchaseServiceImpl service = service();
        when(groupPurchaseMapper.findById("1")).thenReturn(savedRow());
        when(groupPurchaseMapper.findParticipant("1", "member-7"))
                .thenReturn(participantRow(10523L, "1", "member-7", 1));

        GroupPurchaseResponse result = service.getDetail("member-7", "1");

        assertEquals("1", result.getGpId());
        assertEquals("member-1", result.getMemberId());
        assertEquals("사료 5kg", result.getProductName());
        assertEquals("/uploads/group-purchase/product.png", result.getImage());
        assertEquals(new BigDecimal("30000"), result.getUnitPrice());
        assertEquals(new BigDecimal("25000"), result.getGroupPrice());
        assertEquals("택배배송", result.getDeliveryMethod());
        assertEquals(new BigDecimal("3000"), result.getDeliveryFee());
        assertEquals(DELIVERY_DATE, result.getDeliveryDate());
        assertEquals(10, result.getTargetQuantity());
        assertEquals(0, result.getCurrentQuantity());
        assertEquals(DEADLINE, result.getDeadline());
        assertTrue(result.getIsParticipating());
    }

    @Test
    @DisplayName("참여하지 않은 회원이 조회하면 isParticipating이 false로 내려간다")
    void should_returnIsParticipatingFalse_when_memberHasNotJoined_onGetDetail() {
        GroupPurchaseServiceImpl service = service();
        when(groupPurchaseMapper.findById("1")).thenReturn(savedRow());
        when(groupPurchaseMapper.findParticipant("1", "member-7")).thenReturn(null);

        GroupPurchaseResponse result = service.getDetail("member-7", "1");

        assertFalse(result.getIsParticipating());
    }

    @Test
    @DisplayName("비로그인 상태로 조회하면 isParticipating은 false이고 참여 여부를 조회하지 않는다")
    void should_returnIsParticipatingFalse_when_memberIdIsNull_onGetDetail() {
        GroupPurchaseServiceImpl service = service();
        when(groupPurchaseMapper.findById("1")).thenReturn(savedRow());

        GroupPurchaseResponse result = service.getDetail(null, "1");

        assertFalse(result.getIsParticipating());
        verify(groupPurchaseMapper, never()).findParticipant(any(), any());
    }

    @Test
    @DisplayName("존재하지 않는 gpId로 조회하면 예외가 발생한다")
    void should_throwException_when_gpNotFound_onGetDetail() {
        GroupPurchaseServiceImpl service = service();
        when(groupPurchaseMapper.findById("999")).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.getDetail("member-1", "999"));

        assertEquals(org.springframework.http.HttpStatus.NOT_FOUND, exception.getStatus());
        assertEquals("공동구매를 찾을 수 없습니다.", exception.getMessage());
    }

    @Test
    @DisplayName("target_quantity가 0 이하인 비정상 데이터는 존재하지 않는 것처럼 예외가 발생한다")
    void should_throwException_when_targetQuantityIsNotPositive_onGetDetail() {
        GroupPurchaseServiceImpl service = service();
        Map<String, Object> gpRow = savedRow();
        gpRow.put("target_quantity", 0);
        when(groupPurchaseMapper.findById("1")).thenReturn(gpRow);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.getDetail("member-1", "1"));

        assertEquals(org.springframework.http.HttpStatus.NOT_FOUND, exception.getStatus());
        assertEquals("공동구매를 찾을 수 없습니다.", exception.getMessage());
        verify(groupPurchaseMapper, never()).findParticipant(any(), any());
    }

    @Test
    @DisplayName("마감 후 목표 수량을 채웠으면 저장된 status 컬럼이 OPEN이어도 COMPLETED로 계산해서 응답한다")
    void should_returnComputedCompletedStatus_notRawOpenColumn_onGetDetail() {
        GroupPurchaseServiceImpl service = service();
        Map<String, Object> gpRow = savedRow();
        gpRow.put("deadline", LocalDateTime.now().minusDays(1));
        gpRow.put("current_quantity", 10);
        when(groupPurchaseMapper.findById("1")).thenReturn(gpRow);
        when(groupPurchaseMapper.findParticipant("1", "member-7")).thenReturn(null);

        GroupPurchaseResponse result = service.getDetail("member-7", "1");

        assertEquals("COMPLETED", result.getStatus());
    }

    @Test
    @DisplayName("작성자가 취소한 공동구매는 마감 전이어도 CANCELLED로 계산해서 응답한다")
    void should_returnComputedCancelledStatus_onGetDetail() {
        GroupPurchaseServiceImpl service = service();
        Map<String, Object> gpRow = savedRow();
        gpRow.put("status", "CANCELLED");
        gpRow.put("deadline", LocalDateTime.now().plusDays(5));
        when(groupPurchaseMapper.findById("1")).thenReturn(gpRow);
        when(groupPurchaseMapper.findParticipant("1", "member-7")).thenReturn(null);

        GroupPurchaseResponse result = service.getDetail("member-7", "1");

        assertEquals("CANCELLED", result.getStatus());
    }

    @Test
    @DisplayName("참여자가 조회하면 participantInfo가 채워진 상태 정보를 반환한다")
    void should_returnStatusWithParticipantInfo_when_requesterIsParticipant() {
        GroupPurchaseServiceImpl service = service();
        Map<String, Object> gpRow = savedRow();
        gpRow.put("deadline", LocalDateTime.now().plusDays(5));
        Map<String, Object> participantRow = participantRow(10523L, "1", "member-1", 2);
        participantRow.put("paid_amount", new BigDecimal("50000"));
        participantRow.put("payment_status", "PAID");
        participantRow.put("paid_at", LocalDateTime.of(2026, 8, 7, 10, 0));
        when(groupPurchaseMapper.findById("1")).thenReturn(gpRow);
        when(groupPurchaseMapper.findParticipant("1", "member-1")).thenReturn(participantRow);

        GroupPurchaseStatusResponse result = service.getStatus("member-1", "1");

        assertEquals("member-1", result.getMemberId());
        assertEquals("사료 5kg", result.getProductName());
        assertEquals("OPEN", result.getStatus());
        assertEquals(0, result.getCurrentQuantity());
        assertEquals(10, result.getTargetQuantity());
        assertEquals(new BigDecimal("30000"), result.getUnitPrice());
        assertEquals(new BigDecimal("25000"), result.getGroupPrice());
        assertEquals(DELIVERY_DATE, result.getDeliveryDate());
        assertEquals(5, result.getDeliveryEstimateDays());
        assertNotNull(result.getParticipantInfo());
        assertEquals(10523L, result.getParticipantInfo().getParticipantId());
        assertEquals(2, result.getParticipantInfo().getPurchaseQuantity());
        assertEquals(new BigDecimal("50000"), result.getParticipantInfo().getPaidAmount());
        assertEquals("PAID", result.getParticipantInfo().getPaymentStatus());
        assertEquals(LocalDateTime.of(2026, 8, 7, 10, 0), result.getParticipantInfo().getPaidAt());
        assertNotNull(result.getNoticeMessage());
    }

    @Test
    @DisplayName("참여하지 않은 회원(작성자 포함)이 조회하면 participantInfo 없이 게시글 정보만 반환한다")
    void should_returnNullParticipantInfo_when_requesterHasNotJoined() {
        GroupPurchaseServiceImpl service = service();
        Map<String, Object> gpRow = savedRow();
        gpRow.put("deadline", LocalDateTime.now().plusDays(5));
        when(groupPurchaseMapper.findById("1")).thenReturn(gpRow);
        when(groupPurchaseMapper.findParticipant("1", "member-1")).thenReturn(null);

        GroupPurchaseStatusResponse result = service.getStatus("member-1", "1");

        assertEquals("사료 5kg", result.getProductName());
        assertNull(result.getParticipantInfo());
    }

    @Test
    @DisplayName("마감 전이어도 목표 수량을 채웠으면 COMPLETED로 응답한다")
    void should_returnConfirmed_when_targetQuantityReachedBeforeDeadline() {
        GroupPurchaseServiceImpl service = service();
        Map<String, Object> gpRow = savedRow();
        gpRow.put("deadline", LocalDateTime.now().plusDays(5));
        gpRow.put("current_quantity", 10);
        when(groupPurchaseMapper.findById("1")).thenReturn(gpRow);
        when(groupPurchaseMapper.findParticipant("1", "member-1")).thenReturn(null);

        GroupPurchaseStatusResponse result = service.getStatus("member-1", "1");

        assertEquals("COMPLETED", result.getStatus());
        assertEquals("목표 인원이 모두 모여 공동구매가 확정되었습니다.", result.getNoticeMessage());
    }

    @Test
    @DisplayName("목표 수량이 0 이하인 비정상 데이터는 마감 전에 COMPLETED로 오판하지 않는다")
    void should_returnWaiting_when_targetQuantityIsZero_onGetStatus() {
        GroupPurchaseServiceImpl service = service();
        Map<String, Object> gpRow = savedRow();
        gpRow.put("deadline", LocalDateTime.now().plusDays(5));
        gpRow.put("target_quantity", 0);
        gpRow.put("current_quantity", 0);
        when(groupPurchaseMapper.findById("1")).thenReturn(gpRow);
        when(groupPurchaseMapper.findParticipant("1", "member-1")).thenReturn(null);

        GroupPurchaseStatusResponse result = service.getStatus("member-1", "1");

        assertEquals("OPEN", result.getStatus());
    }

    @Test
    @DisplayName("작성자가 취소한 공동구매는 마감 전이어도 CANCELLED로 응답하고, 작성자 취소 문구를 안내한다")
    void should_returnCancelled_when_groupPurchaseIsCancelled_onGetStatus() {
        GroupPurchaseServiceImpl service = service();
        Map<String, Object> gpRow = savedRow();
        gpRow.put("status", "CANCELLED");
        gpRow.put("deadline", LocalDateTime.now().plusDays(5));
        when(groupPurchaseMapper.findById("1")).thenReturn(gpRow);
        when(groupPurchaseMapper.findParticipant("1", "member-1")).thenReturn(null);

        GroupPurchaseStatusResponse result = service.getStatus("member-1", "1");

        assertEquals("CANCELLED", result.getStatus());
        assertEquals("작성자가 취소한 공동구매입니다.", result.getNoticeMessage());
    }

    @Test
    @DisplayName("마감 후 목표 수량을 못 채웠으면 FAILED로 응답하고, 목표 미달 문구를 안내한다")
    void should_returnFailed_when_deadlinePassedAndTargetNotReached_onGetStatus() {
        GroupPurchaseServiceImpl service = service();
        Map<String, Object> gpRow = savedRow();
        gpRow.put("deadline", LocalDateTime.now().minusDays(1));
        gpRow.put("current_quantity", 4);
        when(groupPurchaseMapper.findById("1")).thenReturn(gpRow);
        when(groupPurchaseMapper.findParticipant("1", "member-1")).thenReturn(null);

        GroupPurchaseStatusResponse result = service.getStatus("member-1", "1");

        assertEquals("FAILED", result.getStatus());
        assertEquals("목표 인원 미달로 공동구매가 취소되어 환불됩니다.", result.getNoticeMessage());
    }

    @Test
    @DisplayName("작성자 취소(CANCELLED)와 마감 미달(FAILED)은 서로 다른 status 값으로 구분된다")
    void should_returnDifferentStatus_forOwnerCancelledAndDeadlinePassedTargetNotReached_onGetStatus() {
        GroupPurchaseServiceImpl service = service();

        Map<String, Object> ownerCancelledRow = savedRow();
        ownerCancelledRow.put("status", "CANCELLED");
        ownerCancelledRow.put("deadline", LocalDateTime.now().plusDays(5));
        when(groupPurchaseMapper.findById("1")).thenReturn(ownerCancelledRow);
        when(groupPurchaseMapper.findParticipant("1", "member-1")).thenReturn(null);
        GroupPurchaseStatusResponse ownerCancelledResult = service.getStatus("member-1", "1");

        Map<String, Object> failedRow = savedRow();
        failedRow.put("deadline", LocalDateTime.now().minusDays(1));
        failedRow.put("current_quantity", 4);
        when(groupPurchaseMapper.findById("2")).thenReturn(failedRow);
        when(groupPurchaseMapper.findParticipant("2", "member-1")).thenReturn(null);
        GroupPurchaseStatusResponse failedResult = service.getStatus("member-1", "2");

        assertEquals("CANCELLED", ownerCancelledResult.getStatus());
        assertEquals("FAILED", failedResult.getStatus());
        org.junit.jupiter.api.Assertions.assertNotEquals(ownerCancelledResult.getStatus(), failedResult.getStatus());
    }

    @Test
    @DisplayName("존재하지 않는 gpId로 상태를 조회하면 예외가 발생한다")
    void should_throwException_when_gpNotFound_onGetStatus() {
        GroupPurchaseServiceImpl service = service();
        when(groupPurchaseMapper.findById("999")).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.getStatus("member-1", "999"));

        assertEquals(org.springframework.http.HttpStatus.NOT_FOUND, exception.getStatus());
        assertEquals("공동구매를 찾을 수 없습니다.", exception.getMessage());
    }

    @Test
    @DisplayName("마감 전이면 저장된 status와 무관하게 OPEN으로 응답한다")
    void should_returnOpen_when_deadlineNotPassed_onGetMyList() {
        GroupPurchaseServiceImpl service = service();
        LocalDateTime futureDeadline = LocalDateTime.now().plusDays(5);
        when(groupPurchaseMapper.findMyGroupPurchases("member-1", null))
                .thenReturn(List.of(listRow(1L, "OPEN", futureDeadline, 30000, 25000, 3, 10)));

        List<GroupPurchaseMyItemResponse> result = service.getMyList("member-1", null);

        assertEquals(1, result.size());
        GroupPurchaseMyItemResponse item = result.get(0);
        assertEquals(1L, item.getGpId());
        assertEquals(3L, item.getMemberId());
        assertEquals("사료 5kg", item.getProductName());
        assertEquals("OPEN", item.getStatus());
        assertEquals(3, item.getCurrentQuantity());
        assertEquals(10, item.getTargetQuantity());
        assertEquals(futureDeadline, item.getDeadline());
    }

    @Test
    @DisplayName("마감 후 목표 수량을 채웠으면 COMPLETED로 응답한다")
    void should_returnCompleted_when_deadlinePassedAndTargetReached_onGetMyList() {
        GroupPurchaseServiceImpl service = service();
        LocalDateTime pastDeadline = LocalDateTime.now().minusDays(1);
        when(groupPurchaseMapper.findMyGroupPurchases("member-1", null))
                .thenReturn(List.of(listRow(1L, "OPEN", pastDeadline, 30000, 25000, 10, 10)));

        List<GroupPurchaseMyItemResponse> result = service.getMyList("member-1", null);

        assertEquals("COMPLETED", result.get(0).getStatus());
    }

    @Test
    @DisplayName("마감 전이어도 목표 수량을 채웠으면 COMPLETED로 응답한다")
    void should_returnCompleted_when_targetQuantityReachedBeforeDeadline_onGetMyList() {
        GroupPurchaseServiceImpl service = service();
        LocalDateTime futureDeadline = LocalDateTime.now().plusDays(5);
        when(groupPurchaseMapper.findMyGroupPurchases("member-1", null))
                .thenReturn(List.of(listRow(1L, "OPEN", futureDeadline, 30000, 25000, 10, 10)));

        List<GroupPurchaseMyItemResponse> result = service.getMyList("member-1", null);

        assertEquals("COMPLETED", result.get(0).getStatus());
    }

    @Test
    @DisplayName("목표 수량이 0 이하인 비정상 데이터는 마감 전에 COMPLETED로 오판하지 않는다")
    void should_returnOpen_when_targetQuantityIsZero_onGetMyList() {
        GroupPurchaseServiceImpl service = service();
        LocalDateTime futureDeadline = LocalDateTime.now().plusDays(5);
        when(groupPurchaseMapper.findMyGroupPurchases("member-1", null))
                .thenReturn(List.of(listRow(1L, "OPEN", futureDeadline, 30000, 25000, 0, 0)));

        List<GroupPurchaseMyItemResponse> result = service.getMyList("member-1", null);

        assertEquals("OPEN", result.get(0).getStatus());
    }

    @Test
    @DisplayName("마감 후 목표 수량을 못 채웠으면 FAILED로 응답한다")
    void should_returnFailed_when_deadlinePassedAndTargetNotReached_onGetMyList() {
        GroupPurchaseServiceImpl service = service();
        LocalDateTime pastDeadline = LocalDateTime.now().minusDays(1);
        when(groupPurchaseMapper.findMyGroupPurchases("member-1", null))
                .thenReturn(List.of(listRow(1L, "OPEN", pastDeadline, 30000, 25000, 4, 10)));

        List<GroupPurchaseMyItemResponse> result = service.getMyList("member-1", null);

        assertEquals("FAILED", result.get(0).getStatus());
    }

    @Test
    @DisplayName("작성자가 취소했으면 마감 전이어도 CANCELLED로 응답한다")
    void should_returnCancelled_when_groupPurchaseIsCancelled_onGetMyList() {
        GroupPurchaseServiceImpl service = service();
        LocalDateTime futureDeadline = LocalDateTime.now().plusDays(5);
        when(groupPurchaseMapper.findMyGroupPurchases("member-1", null))
                .thenReturn(List.of(listRow(1L, "CANCELLED", futureDeadline, 30000, 25000, 2, 10)));

        List<GroupPurchaseMyItemResponse> result = service.getMyList("member-1", null);

        assertEquals("CANCELLED", result.get(0).getStatus());
    }

    @Test
    @DisplayName("작성자 취소(CANCELLED)와 마감 미달(FAILED)은 서로 다른 status 값으로 구분된다")
    void should_returnDifferentStatus_forOwnerCancelledAndDeadlinePassedTargetNotReached_onGetMyList() {
        GroupPurchaseServiceImpl service = service();
        LocalDateTime futureDeadline = LocalDateTime.now().plusDays(5);
        LocalDateTime pastDeadline = LocalDateTime.now().minusDays(1);
        when(groupPurchaseMapper.findMyGroupPurchases("member-1", null))
                .thenReturn(List.of(
                        listRow(1L, "CANCELLED", futureDeadline, 30000, 25000, 2, 10),
                        listRow(2L, "OPEN", pastDeadline, 30000, 25000, 4, 10)));

        List<GroupPurchaseMyItemResponse> result = service.getMyList("member-1", null);

        assertEquals("CANCELLED", result.get(0).getStatus());
        assertEquals("FAILED", result.get(1).getStatus());
        org.junit.jupiter.api.Assertions.assertNotEquals(result.get(0).getStatus(), result.get(1).getStatus());
    }

    @Test
    @DisplayName("status 필터를 매퍼에 그대로 전달한다")
    void should_passStatusFilter_toMapper_onGetMyList() {
        GroupPurchaseServiceImpl service = service();
        when(groupPurchaseMapper.findMyGroupPurchases("member-1", "CANCELLED")).thenReturn(List.of());

        service.getMyList("member-1", "CANCELLED");

        verify(groupPurchaseMapper).findMyGroupPurchases("member-1", "CANCELLED");
    }

    @Test
    @DisplayName("결과가 없으면 빈 목록을 반환한다")
    void should_returnEmptyList_when_noRowsMatch_onGetMyList() {
        GroupPurchaseServiceImpl service = service();
        when(groupPurchaseMapper.findMyGroupPurchases("member-1", null)).thenReturn(List.of());

        List<GroupPurchaseMyItemResponse> result = service.getMyList("member-1", null);

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("페이지 크기보다 많은 결과가 있으면 hasNext가 true이고 초과분은 잘라낸다")
    void should_trimAndSetHasNextTrue_when_moreRowsThanPageSizeExist() {
        GroupPurchaseServiceImpl service = service();
        LocalDateTime deadline = LocalDateTime.now().plusDays(5);
        when(groupPurchaseMapper.findList(isNull(), isNull(), isNull(), eq(3), eq(0), isNull()))
                .thenReturn(List.of(
                        listRow(1L, "OPEN", deadline, 30000, 25000),
                        listRow(2L, "OPEN", deadline, 30000, 25000),
                        listRow(3L, "OPEN", deadline, 30000, 25000)));

        GroupPurchaseListResponse result = service.list(null, null, null, null, 0, 2);

        assertEquals(2, result.getItems().size());
        assertTrue(result.isHasNext());
        GroupPurchaseListItemResponse first = result.getItems().get(0);
        assertEquals(1L, first.getId());
        assertEquals("OPEN", first.getStatus());
        assertEquals("D-5", first.getDDay());
        assertEquals("17% 할인", first.getBadgeText());
        assertFalse(first.getIsParticipating());
    }

    @Test
    @DisplayName("목록 항목의 image는 저장 키를 signedUrl로 감싼 값이 내려간다")
    void should_returnSignedImageUrl_onList() {
        GroupPurchaseServiceImpl service = service();
        LocalDateTime deadline = LocalDateTime.now().plusDays(5);
        when(groupPurchaseMapper.findList(isNull(), isNull(), isNull(), eq(11), eq(0), isNull()))
                .thenReturn(List.of(listRow(1L, "OPEN", deadline, 30000, 25000)));

        GroupPurchaseListResponse result = service.list(null, null, null, null, 0, 10);

        assertEquals("group-purchase/sample.png", result.getItems().get(0).getImage());
    }

    @Test
    @DisplayName("image가 없는 게시글은 목록 항목의 image가 null로 내려간다")
    void should_returnNullImage_when_rowHasNoImage() {
        GroupPurchaseServiceImpl service = service();
        LocalDateTime deadline = LocalDateTime.now().plusDays(5);
        Map<String, Object> row = listRow(1L, "OPEN", deadline, 30000, 25000);
        row.put("image", null);
        when(groupPurchaseMapper.findList(isNull(), isNull(), isNull(), eq(11), eq(0), isNull()))
                .thenReturn(List.of(row));

        GroupPurchaseListResponse result = service.list(null, null, null, null, 0, 10);

        assertNull(result.getItems().get(0).getImage());
    }

    @Test
    @DisplayName("로그인한 유저가 이미 참여한 게시글은 isParticipating이 true로 내려간다")
    void should_returnIsParticipatingTrue_when_memberAlreadyJoined() {
        GroupPurchaseServiceImpl service = service();
        LocalDateTime deadline = LocalDateTime.now().plusDays(5);
        when(groupPurchaseMapper.findList(isNull(), isNull(), isNull(), eq(11), eq(0), isNull()))
                .thenReturn(List.of(listRow(1L, "OPEN", deadline, 30000, 25000)));
        when(groupPurchaseMapper.findParticipatingGpIds(eq("member-1"), eq(List.of("1"))))
                .thenReturn(List.of(1L));

        GroupPurchaseListResponse result = service.list("member-1", null, null, null, 0, 10);

        assertTrue(result.getItems().get(0).getIsParticipating());
        verify(groupPurchaseMapper, never()).findParticipant(any(), any());
    }

    @Test
    @DisplayName("비로그인 상태로 조회하면 isParticipating은 항상 false이고 참여 여부를 조회하지 않는다")
    void should_returnIsParticipatingFalse_when_memberIdIsNull() {
        GroupPurchaseServiceImpl service = service();
        LocalDateTime deadline = LocalDateTime.now().plusDays(5);
        when(groupPurchaseMapper.findList(isNull(), isNull(), isNull(), eq(11), eq(0), isNull()))
                .thenReturn(List.of(listRow(1L, "OPEN", deadline, 30000, 25000)));

        GroupPurchaseListResponse result = service.list(null, null, null, null, 0, 10);

        assertFalse(result.getItems().get(0).getIsParticipating());
        verify(groupPurchaseMapper, never()).findParticipant(any(), any());
        verify(groupPurchaseMapper, never()).findParticipatingGpIds(any(), any());
    }

    @Test
    @DisplayName("목록 여러 건의 참여 여부는 findParticipatingGpIds 한 번으로 조회한다")
    void should_queryParticipatingIdsOnce_when_listingMultipleItems() {
        GroupPurchaseServiceImpl service = service();
        LocalDateTime deadline = LocalDateTime.now().plusDays(5);
        when(groupPurchaseMapper.findList(isNull(), isNull(), isNull(), eq(11), eq(0), isNull()))
                .thenReturn(List.of(
                        listRow(1L, "OPEN", deadline, 30000, 25000),
                        listRow(2L, "OPEN", deadline, 30000, 25000),
                        listRow(3L, "OPEN", deadline, 30000, 25000)));
        when(groupPurchaseMapper.findParticipatingGpIds(eq("member-1"), eq(List.of("1", "2", "3"))))
                .thenReturn(List.of(2L));

        GroupPurchaseListResponse result = service.list("member-1", null, null, null, 0, 10);

        assertFalse(result.getItems().get(0).getIsParticipating());
        assertTrue(result.getItems().get(1).getIsParticipating());
        assertFalse(result.getItems().get(2).getIsParticipating());
        verify(groupPurchaseMapper, times(1)).findParticipatingGpIds(eq("member-1"), eq(List.of("1", "2", "3")));
        verify(groupPurchaseMapper, never()).findParticipant(any(), any());
    }

    @Test
    @DisplayName("로그인 상태라도 목록이 비면 참여 여부 조회를 생략한다")
    void should_skipParticipatingQuery_when_loggedInListIsEmpty() {
        GroupPurchaseServiceImpl service = service();
        when(groupPurchaseMapper.findList(isNull(), isNull(), isNull(), eq(11), eq(0), isNull()))
                .thenReturn(List.of());

        GroupPurchaseListResponse result = service.list("member-1", null, null, null, 0, 10);

        assertTrue(result.getItems().isEmpty());
        verify(groupPurchaseMapper, never()).findParticipatingGpIds(any(), any());
        verify(groupPurchaseMapper, never()).findParticipant(any(), any());
    }

    @Test
    @DisplayName("결과가 없으면 빈 목록과 hasNext false를 반환한다")
    void should_returnEmptyListWithHasNextFalse_when_noRowsMatch() {
        GroupPurchaseServiceImpl service = service();
        when(groupPurchaseMapper.findList(any(), any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(List.of());

        GroupPurchaseListResponse result = service.list(null, null, null, null, 99, 10);

        assertTrue(result.getItems().isEmpty());
        assertFalse(result.isHasNext());
    }

    @Test
    @DisplayName("status 필터는 검증만 거쳐 매퍼에 그대로 전달된다")
    void should_passStatusFilter_toMapper_onList() {
        GroupPurchaseServiceImpl service = service();
        when(groupPurchaseMapper.findList(eq("COMPLETED"), isNull(), eq("사료"), eq(11), eq(0), isNull()))
                .thenReturn(List.of());

        service.list(null, "COMPLETED", null, "사료", 0, 10);

        verify(groupPurchaseMapper).findList("COMPLETED", null, "사료", 11, 0, null);
    }

    @Test
    @DisplayName("상태 필터가 없어도 예외 없이 전체 목록을 조회한다")
    void should_notThrow_when_statusFilterIsNull() {
        GroupPurchaseServiceImpl service = service();
        when(groupPurchaseMapper.findList(isNull(), isNull(), isNull(), anyInt(), anyInt(), isNull()))
                .thenReturn(List.of());

        assertDoesNotThrow(() -> service.list(null, null, null, null, 0, 10));
    }

    @Test
    @DisplayName("매핑되지 않은 상태 값이면 필터를 생략하지 않고 예외를 던진다")
    void should_throwException_when_statusFilterIsUnsupported() {
        GroupPurchaseServiceImpl service = service();

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.list(null, "invalid", null, null, 0, 10));

        assertEquals("지원하지 않는 상태 값입니다: invalid", exception.getMessage());
        verifyNoInteractions(groupPurchaseMapper);
    }

    @Test
    @DisplayName("CANCELLED는 목록 API에서 지원하지 않는 상태 값이다 — 마감(취소)된 게시글은 이 목록에 노출되지 않는다")
    void should_throwException_when_statusFilterIsCancelled_onList() {
        GroupPurchaseServiceImpl service = service();

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.list(null, "CANCELLED", null, null, 0, 10));

        assertEquals("지원하지 않는 상태 값입니다: CANCELLED", exception.getMessage());
        verifyNoInteractions(groupPurchaseMapper);
    }

    @Test
    @DisplayName("마감 전이면 저장된 status와 무관하게 OPEN으로 계산한다")
    void should_returnInProgress_when_deadlineNotYetPassed() {
        GroupPurchaseServiceImpl service = service();
        LocalDateTime futureDeadline = LocalDateTime.now().plusDays(3);
        when(groupPurchaseMapper.findList(isNull(), isNull(), isNull(), eq(11), eq(0), isNull()))
                .thenReturn(List.of(listRow(1L, "OPEN", futureDeadline, 30000, 25000, 5, 10)));

        GroupPurchaseListResponse result = service.list(null, null, null, null, 0, 10);

        assertEquals("OPEN", result.getItems().get(0).getStatus());
    }

    @Test
    @DisplayName("마감 후 목표 수량을 채웠으면 저장된 status가 OPEN이어도 COMPLETED로 계산한다")
    void should_returnClosedSuccess_when_deadlinePassedAndTargetReached() {
        GroupPurchaseServiceImpl service = service();
        LocalDateTime pastDeadline = LocalDateTime.now().minusDays(1);
        when(groupPurchaseMapper.findList(isNull(), isNull(), isNull(), eq(11), eq(0), isNull()))
                .thenReturn(List.of(listRow(1L, "OPEN", pastDeadline, 30000, 25000, 10, 10)));

        GroupPurchaseListResponse result = service.list(null, null, null, null, 0, 10);

        assertEquals("COMPLETED", result.getItems().get(0).getStatus());
    }

    @Test
    @DisplayName("마감 후 목표 수량을 못 채웠으면 저장된 status가 OPEN이어도 FAILED로 계산한다")
    void should_returnClosedFail_when_deadlinePassedAndTargetNotReached() {
        GroupPurchaseServiceImpl service = service();
        LocalDateTime pastDeadline = LocalDateTime.now().minusDays(1);
        when(groupPurchaseMapper.findList(isNull(), isNull(), isNull(), eq(11), eq(0), isNull()))
                .thenReturn(List.of(listRow(1L, "OPEN", pastDeadline, 30000, 25000, 4, 10)));

        GroupPurchaseListResponse result = service.list(null, null, null, null, 0, 10);

        assertEquals("FAILED", result.getItems().get(0).getStatus());
    }

    @Test
    @DisplayName("마감 전이어도 목표 수량을 채웠으면 마이페이지/상세와 동일하게 COMPLETED로 계산한다")
    void should_returnClosedSuccess_when_targetReachedBeforeDeadline() {
        GroupPurchaseServiceImpl service = service();
        LocalDateTime futureDeadline = LocalDateTime.now().plusDays(3);
        when(groupPurchaseMapper.findList(isNull(), isNull(), isNull(), eq(11), eq(0), isNull()))
                .thenReturn(List.of(listRow(1L, "OPEN", futureDeadline, 30000, 25000, 10, 10)));

        GroupPurchaseListResponse result = service.list(null, null, null, null, 0, 10);

        assertEquals("COMPLETED", result.getItems().get(0).getStatus());
    }

    @Test
    @DisplayName("목표 수량이 0 이하인 비정상 데이터는 마감 전에 COMPLETED로 오판하지 않는다")
    void should_returnInProgress_when_targetQuantityIsZero_onList() {
        GroupPurchaseServiceImpl service = service();
        LocalDateTime futureDeadline = LocalDateTime.now().plusDays(3);
        when(groupPurchaseMapper.findList(isNull(), isNull(), isNull(), eq(11), eq(0), isNull()))
                .thenReturn(List.of(listRow(1L, "OPEN", futureDeadline, 30000, 25000, 0, 0)));

        GroupPurchaseListResponse result = service.list(null, null, null, null, 0, 10);

        assertEquals("OPEN", result.getItems().get(0).getStatus());
    }

    @Test
    @DisplayName("목록 응답에 unitPrice/groupPrice 원본 값이 그대로 내려간다")
    void should_exposeUnitPriceAndGroupPrice_onListItem() {
        GroupPurchaseServiceImpl service = service();
        LocalDateTime deadline = LocalDateTime.now().plusDays(5);
        when(groupPurchaseMapper.findList(isNull(), isNull(), isNull(), eq(11), eq(0), isNull()))
                .thenReturn(List.of(listRow(1L, "OPEN", deadline, 30000, 25000)));

        GroupPurchaseListResponse result = service.list(null, null, null, null, 0, 10);

        GroupPurchaseListItemResponse item = result.getItems().get(0);
        assertEquals(0, new BigDecimal("30000").compareTo(item.getUnitPrice()));
        assertEquals(0, new BigDecimal("25000").compareTo(item.getGroupPrice()));
    }

    @Test
    @DisplayName("참여 수량이 0 이하이면 계산 전에 예외가 발생한다")
    void should_throwException_when_quantityIsNotPositive() {
        GroupPurchaseServiceImpl service = service();

        BusinessException zeroException = assertThrows(BusinessException.class,
                () -> service.join("member-1", "1", 0, joinRequest()));
        assertEquals("참여 수량은 1 이상이어야 합니다.", zeroException.getMessage());

        BusinessException negativeException = assertThrows(BusinessException.class,
                () -> service.join("member-1", "1", -3, joinRequest()));
        assertEquals("참여 수량은 1 이상이어야 합니다.", negativeException.getMessage());

        verifyNoInteractions(groupPurchaseMapper);
        verifyNoInteractions(walletMapper);
        verifyNoInteractions(transactionMapper);
    }

    @Test
    @DisplayName("공동구매 참여에 성공하면 지갑에서 차감하고 거래내역을 생성한 뒤 참여 정보를 반환한다")
    void should_returnJoinResponse_when_joinSucceeds() {
        GroupPurchaseServiceImpl service = service();
        Map<String, Object> gpRow = savedRow();
        Map<String, Object> updatedGpRow = savedRow();
        updatedGpRow.put("current_quantity", 2);
        Map<String, Object> savedParticipantRow = participantRow(10523L, "1", "member-1", 2);
        savedParticipantRow.put("paid_amount", new BigDecimal("50000"));
        savedParticipantRow.put("payment_status", "PAID");
        savedParticipantRow.put("paid_at", LocalDateTime.of(2026, 8, 7, 10, 0));
        savedParticipantRow.put("txn_id", 777L);

        when(groupPurchaseMapper.findById("1")).thenReturn(gpRow, updatedGpRow);
        when(groupPurchaseMapper.findParticipant("1", "member-1")).thenReturn(null, savedParticipantRow);
        when(simplePasswordVerificationService.verify("member-1", PASSWORD)).thenReturn(true);

        Map<String, Object> wallet = new HashMap<>();
        wallet.put("wallet_id", "wallet-1");
        wallet.put("member_id", "member-1");
        wallet.put("balance", new BigDecimal("100000"));
        when(walletMapper.findByMemberId("member-1")).thenReturn(wallet);
        when(walletMapper.deductBalance("wallet-1", new BigDecimal("50000"))).thenReturn(1);
        doAnswer(invocation -> {
            Map<String, Object> txn = invocation.getArgument(0);
            txn.put("txnId", 777L);
            return null;
        }).when(transactionMapper).insert(anyMap());
        when(groupPurchaseMapper.updateQuantity("1", 2)).thenReturn(1);

        GroupPurchaseJoinResponse result = service.join("member-1", "1", 2, joinRequest());

        assertEquals("1", result.getGpId());
        assertEquals(10523L, result.getParticipantId());
        assertEquals(2, result.getQuantity());
        assertEquals(2, result.getCurrentQuantity());
        assertEquals(10, result.getTargetQuantity());
        assertEquals("김애월", result.getRecipientName());
        assertEquals("010-1234-5678", result.getRecipientPhone());
        assertEquals("16856", result.getZipCode());
        assertEquals("서울특별시 광진구 화양동", result.getAddress());
        assertEquals("세종대점 컴포즈 302호", result.getAddressDetail());
        assertEquals("PAID", result.getPaymentStatus());
        assertEquals(new BigDecimal("50000"), result.getPaidAmount());
        assertEquals(LocalDateTime.of(2026, 8, 7, 10, 0), result.getPaidAt());
        assertEquals(LocalDateTime.of(2026, 8, 7, 10, 0), result.getJoinedAt());

        verify(walletMapper).deductBalance("wallet-1", new BigDecimal("50000"));

        ArgumentCaptor<Map<String, Object>> txnCaptor = ArgumentCaptor.forClass(Map.class);
        verify(transactionMapper).insert(txnCaptor.capture());
        assertEquals("wallet-1", txnCaptor.getValue().get("walletId"));
        assertEquals("PAYMENT", txnCaptor.getValue().get("txnType"));
        assertEquals(new BigDecimal("50000"), txnCaptor.getValue().get("price"));
        assertEquals("FOOD", txnCaptor.getValue().get("category"));
        String txnMemo = (String) txnCaptor.getValue().get("memo");
        assertTrue(txnMemo.contains("공동구매 참여"));
        assertFalse(txnMemo.contains("gpId"));

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(groupPurchaseMapper).insertParticipant(captor.capture());
        assertEquals("김애월", captor.getValue().get("recipientName"));
        assertEquals("010-1234-5678", captor.getValue().get("recipientPhone"));
        assertEquals(new BigDecimal("50000"), captor.getValue().get("paidAmount"));
        assertEquals("PAID", captor.getValue().get("paymentStatus"));
        assertEquals(777L, captor.getValue().get("txnId"));
        verify(groupPurchaseMapper).updateQuantity("1", 2);
        // 목표(10) 미달성(2) 상태라 배송예정일 확정 로직이 돌면 안 된다.
        verify(groupPurchaseMapper, never()).updateDeliveryDate(any(), any());
    }

    @Test
    @DisplayName("이번 참여로 목표 수량을 처음 달성하면 배송예정일을 확정일 + 예상소요일로 갱신한다")
    void should_confirmDeliveryDate_when_joinReachesTargetQuantity() {
        GroupPurchaseServiceImpl service = service();
        Map<String, Object> gpRow = savedRow();
        gpRow.put("group_price", null); // 결제 로직은 이 테스트의 관심사가 아니므로 무결제 케이스로 단순화한다.
        Map<String, Object> updatedGpRow = savedRow();
        updatedGpRow.put("group_price", null);
        updatedGpRow.put("current_quantity", 10);
        updatedGpRow.put("target_quantity", 10);
        updatedGpRow.put("delivery_estimate_days", 5);
        Map<String, Object> savedParticipantRow = participantRow(10523L, "1", "member-1", 8);

        when(groupPurchaseMapper.findById("1")).thenReturn(gpRow, updatedGpRow);
        when(groupPurchaseMapper.findParticipant("1", "member-1")).thenReturn(null, savedParticipantRow);
        when(groupPurchaseMapper.updateQuantity("1", 8)).thenReturn(1);
        when(simplePasswordVerificationService.verify("member-1", PASSWORD)).thenReturn(true);

        service.join("member-1", "1", 8, joinRequest());

        ArgumentCaptor<LocalDate> dateCaptor = ArgumentCaptor.forClass(LocalDate.class);
        verify(groupPurchaseMapper).updateDeliveryDate(eq("1"), dateCaptor.capture());
        assertEquals(LocalDate.now().plusDays(5), dateCaptor.getValue());
        verifyNoInteractions(walletMapper);
    }

    @Test
    @DisplayName("목표 수량을 달성해도 배송 예상 소요일이 없으면 배송예정일을 갱신하지 않는다")
    void should_notConfirmDeliveryDate_when_estimateDaysIsMissing_evenIfTargetReached() {
        GroupPurchaseServiceImpl service = service();
        Map<String, Object> gpRow = savedRow();
        gpRow.put("group_price", null);
        Map<String, Object> updatedGpRow = savedRow();
        updatedGpRow.put("group_price", null);
        updatedGpRow.put("current_quantity", 10);
        updatedGpRow.put("target_quantity", 10);
        updatedGpRow.put("delivery_estimate_days", null);
        Map<String, Object> savedParticipantRow = participantRow(10523L, "1", "member-1", 8);

        when(groupPurchaseMapper.findById("1")).thenReturn(gpRow, updatedGpRow);
        when(groupPurchaseMapper.findParticipant("1", "member-1")).thenReturn(null, savedParticipantRow);
        when(groupPurchaseMapper.updateQuantity("1", 8)).thenReturn(1);
        when(simplePasswordVerificationService.verify("member-1", PASSWORD)).thenReturn(true);

        service.join("member-1", "1", 8, joinRequest());

        verify(groupPurchaseMapper, never()).updateDeliveryDate(any(), any());
    }

    @Test
    @DisplayName("공동구매가가 설정돼 있지 않으면 paidAmount는 null로 응답한다")
    void should_returnNullPaidAmount_when_groupPriceIsNull() {
        GroupPurchaseServiceImpl service = service();
        Map<String, Object> gpRow = savedRow();
        gpRow.put("group_price", null);
        Map<String, Object> updatedGpRow = savedRow();
        updatedGpRow.put("group_price", null);
        updatedGpRow.put("current_quantity", 1);
        Map<String, Object> savedParticipantRow = participantRow(999L, "1", "member-1", 1);
        savedParticipantRow.put("paid_amount", null);

        when(groupPurchaseMapper.findById("1")).thenReturn(gpRow, updatedGpRow);
        when(groupPurchaseMapper.findParticipant("1", "member-1")).thenReturn(null, savedParticipantRow);
        when(groupPurchaseMapper.updateQuantity("1", 1)).thenReturn(1);
        when(simplePasswordVerificationService.verify("member-1", PASSWORD)).thenReturn(true);

        GroupPurchaseJoinResponse result = service.join("member-1", "1", 1, joinRequest());

        assertNull(result.getPaidAmount());

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(groupPurchaseMapper).insertParticipant(captor.capture());
        assertNull(captor.getValue().get("paidAmount"));
        verifyNoInteractions(walletMapper);
        verifyNoInteractions(transactionMapper);
    }

    @Test
    @DisplayName("조건부 수량 예약이 실패(목표 초과 또는 마감)하면 409로 거절한다")
    void should_throwException_when_quantityReservationAffectsNoRows() {
        GroupPurchaseServiceImpl service = service();
        when(groupPurchaseMapper.findById("1")).thenReturn(savedRow());
        when(groupPurchaseMapper.findParticipant("1", "member-1")).thenReturn(null);

        Map<String, Object> wallet = new HashMap<>();
        wallet.put("wallet_id", "wallet-1");
        wallet.put("balance", new BigDecimal("1000000"));
        when(walletMapper.findByMemberId("member-1")).thenReturn(wallet);
        when(walletMapper.deductBalance("wallet-1", new BigDecimal("125000"))).thenReturn(1);
        doAnswer(invocation -> {
            Map<String, Object> txn = invocation.getArgument(0);
            txn.put("txnId", 1L);
            return null;
        }).when(transactionMapper).insert(anyMap());
        // 목표 수량 초과 또는 마감으로 조건부 UPDATE의 WHERE 절을 만족하는 행이 없는 상황(영향 행 수 0)을 재현한다.
        when(groupPurchaseMapper.updateQuantity("1", 5)).thenReturn(0);
        when(simplePasswordVerificationService.verify("member-1", PASSWORD)).thenReturn(true);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.join("member-1", "1", 5, joinRequest()));

        assertEquals("목표 수량을 초과했거나 마감된 공동구매입니다.", exception.getMessage());
    }

    @Test
    @DisplayName("취소된 공동구매는 마감 전이어도 참여를 시도하면 409로 거절한다")
    void should_throwConflict_when_groupPurchaseIsCancelled() {
        GroupPurchaseServiceImpl service = service();
        Map<String, Object> cancelledGp = savedRow();
        cancelledGp.put("status", "CANCELLED");
        cancelledGp.put("deadline", LocalDateTime.now().plusDays(3));
        when(groupPurchaseMapper.findById("1")).thenReturn(cancelledGp);
        when(groupPurchaseMapper.findParticipant("1", "member-1")).thenReturn(null);

        Map<String, Object> wallet = new HashMap<>();
        wallet.put("wallet_id", "wallet-1");
        wallet.put("balance", new BigDecimal("1000000"));
        when(walletMapper.findByMemberId("member-1")).thenReturn(wallet);
        when(walletMapper.deductBalance("wallet-1", new BigDecimal("25000"))).thenReturn(1);
        doAnswer(invocation -> {
            Map<String, Object> txn = invocation.getArgument(0);
            txn.put("txnId", 1L);
            return null;
        }).when(transactionMapper).insert(anyMap());
        // status = 'CANCELLED'는 updateQuantity의 WHERE 절에서 제외되므로,
        // 마감 전·목표 미달 상태여도 조건부 UPDATE의 영향 행 수는 0이어야 한다.
        when(groupPurchaseMapper.updateQuantity("1", 1)).thenReturn(0);
        when(simplePasswordVerificationService.verify("member-1", PASSWORD)).thenReturn(true);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.join("member-1", "1", 1, joinRequest()));

        assertEquals(org.springframework.http.HttpStatus.CONFLICT, exception.getStatus());
        assertEquals("목표 수량을 초과했거나 마감된 공동구매입니다.", exception.getMessage());
    }

    @Test
    @DisplayName("지갑 잔액이 부족하면 참여 없이 예외가 발생한다")
    void should_throwException_when_walletBalanceInsufficient() {
        GroupPurchaseServiceImpl service = service();
        when(groupPurchaseMapper.findById("1")).thenReturn(savedRow());
        when(groupPurchaseMapper.findParticipant("1", "member-1")).thenReturn(null);

        Map<String, Object> wallet = new HashMap<>();
        wallet.put("wallet_id", "wallet-1");
        wallet.put("balance", new BigDecimal("10000"));
        when(walletMapper.findByMemberId("member-1")).thenReturn(wallet);
        when(simplePasswordVerificationService.verify("member-1", PASSWORD)).thenReturn(true);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.join("member-1", "1", 2, joinRequest()));

        assertEquals("잔액이 부족합니다.", exception.getMessage());
        verify(walletMapper, never()).deductBalance(any(), any());
        verifyNoInteractions(transactionMapper);
        verify(groupPurchaseMapper, never()).insertParticipant(any());
        verify(groupPurchaseMapper, never()).updateQuantity(any(), anyInt());
    }

    @Test
    @DisplayName("사전 잔액 확인은 통과했지만 동시 결제로 원자적 차감이 실패하면(영향 행 0) 잔액 부족으로 처리한다")
    void should_throwException_when_deductBalanceAffectsNoRows() {
        GroupPurchaseServiceImpl service = service();
        when(groupPurchaseMapper.findById("1")).thenReturn(savedRow());
        when(groupPurchaseMapper.findParticipant("1", "member-1")).thenReturn(null);

        Map<String, Object> wallet = new HashMap<>();
        wallet.put("wallet_id", "wallet-1");
        // 앱에서 읽은 잔액(사전 체크)은 충분하지만, 동시에 다른 결제가 먼저 반영되어
        // 실제 원자적 UPDATE 시점엔 잔액이 부족한 상황(lost update 방지)을 재현한다.
        wallet.put("balance", new BigDecimal("100000"));
        when(walletMapper.findByMemberId("member-1")).thenReturn(wallet);
        when(walletMapper.deductBalance("wallet-1", new BigDecimal("50000"))).thenReturn(0);
        when(simplePasswordVerificationService.verify("member-1", PASSWORD)).thenReturn(true);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.join("member-1", "1", 2, joinRequest()));

        assertEquals("잔액이 부족합니다.", exception.getMessage());
        verifyNoInteractions(transactionMapper);
        verify(groupPurchaseMapper, never()).insertParticipant(any());
        verify(groupPurchaseMapper, never()).updateQuantity(any(), anyInt());
    }

    @Test
    @DisplayName("존재하지 않는 공동구매에 참여를 시도하면 예외가 발생한다")
    void should_throwException_when_gpNotFound() {
        GroupPurchaseServiceImpl service = service();
        when(groupPurchaseMapper.findById("999")).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.join("member-1", "999", 1, joinRequest()));

        assertEquals("공동구매를 찾을 수 없습니다.", exception.getMessage());
        verify(groupPurchaseMapper, never()).insertParticipant(any());
    }

    @Test
    @DisplayName("이미 참여한 공동구매에 재참여를 시도하면 예외가 발생한다")
    void should_throwException_when_alreadyParticipating() {
        GroupPurchaseServiceImpl service = service();
        when(groupPurchaseMapper.findById("1")).thenReturn(savedRow());
        when(groupPurchaseMapper.findParticipant("1", "member-1"))
                .thenReturn(participantRow(10523L, "1", "member-1", 2));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.join("member-1", "1", 1, joinRequest()));

        assertEquals("이미 참여한 공동구매입니다.", exception.getMessage());
        verify(groupPurchaseMapper, never()).insertParticipant(any());
        verify(groupPurchaseMapper, never()).updateQuantity(any(), anyInt());
    }

    @Test
    @DisplayName("동시 요청으로 조회 시점엔 미참여였지만 DB 유니크 제약을 위반하면 409로 변환한다")
    void should_convertToConflict_when_duplicateKeyViolatedOnConcurrentJoin() {
        GroupPurchaseServiceImpl service = service();
        when(groupPurchaseMapper.findById("1")).thenReturn(savedRow());
        // findParticipant는 레이스 컨디션으로 조회 시점엔 null(미참여)을 반환하지만,
        // 동시에 들어온 다른 요청이 먼저 커밋되어 실제 insert는 유니크 제약을 위반한다.
        when(groupPurchaseMapper.findParticipant("1", "member-1")).thenReturn(null);

        Map<String, Object> wallet = new HashMap<>();
        wallet.put("wallet_id", "wallet-1");
        wallet.put("balance", new BigDecimal("100000"));
        when(walletMapper.findByMemberId("member-1")).thenReturn(wallet);
        when(walletMapper.deductBalance("wallet-1", new BigDecimal("50000"))).thenReturn(1);
        doAnswer(invocation -> {
            Map<String, Object> txn = invocation.getArgument(0);
            txn.put("txnId", 1L);
            return null;
        }).when(transactionMapper).insert(anyMap());

        doThrow(new DuplicateKeyException("Duplicate entry for key 'uq_gpp_gp_member'"))
                .when(groupPurchaseMapper).insertParticipant(anyMap());
        when(simplePasswordVerificationService.verify("member-1", PASSWORD)).thenReturn(true);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.join("member-1", "1", 2, joinRequest()));

        assertEquals("이미 참여한 공동구매입니다.", exception.getMessage());
        verify(groupPurchaseMapper, never()).updateQuantity(any(), anyInt());
    }

    @Test
    @DisplayName("간편 비밀번호가 일치하지 않으면 결제 없이 참여를 거절한다")
    void should_throwException_when_passwordMismatch_onJoin() {
        GroupPurchaseServiceImpl service = service();
        when(groupPurchaseMapper.findById("1")).thenReturn(savedRow());
        when(groupPurchaseMapper.findParticipant("1", "member-1")).thenReturn(null);
        when(simplePasswordVerificationService.verify("member-1", "000000")).thenReturn(false);

        GroupPurchaseJoinRequest request = joinRequest();
        ReflectionTestUtils.setField(request, "password", "000000");

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.join("member-1", "1", 2, request));

        assertEquals("간편 비밀번호가 일치하지 않습니다.", exception.getMessage());
        verify(groupPurchaseMapper, never()).insertParticipant(any());
        verify(groupPurchaseMapper, never()).updateQuantity(any(), anyInt());
        verifyNoInteractions(walletMapper);
    }

    @Test
    @DisplayName("결제 완료(PAID) 참여를 취소하면 지갑을 환급하고 환불 거래내역을 남긴 뒤 취소 정보를 반환한다")
    void should_returnLeaveResponse_when_paidParticipantLeaves() {
        GroupPurchaseServiceImpl service = service();
        Map<String, Object> gpRow = savedRow();
        gpRow.put("deadline", LocalDateTime.now().plusDays(5));
        Map<String, Object> updatedGpRow = savedRow();
        updatedGpRow.put("deadline", LocalDateTime.now().plusDays(5));
        updatedGpRow.put("current_quantity", 0);
        Map<String, Object> participantRow = participantRow(10523L, "1", "member-1", 2);
        participantRow.put("paid_amount", new BigDecimal("50000"));
        participantRow.put("payment_status", "PAID");
        when(groupPurchaseMapper.findById("1")).thenReturn(gpRow, updatedGpRow);
        when(groupPurchaseMapper.findParticipant("1", "member-1")).thenReturn(participantRow);
        when(groupPurchaseMapper.cancelParticipant(eq("1"), eq("member-1"), any())).thenReturn(1);
        when(groupPurchaseMapper.decreaseQuantity("1", 2)).thenReturn(1);

        Map<String, Object> walletBefore = new HashMap<>();
        walletBefore.put("wallet_id", "wallet-1");
        walletBefore.put("balance", new BigDecimal("100000"));
        Map<String, Object> walletAfter = new HashMap<>();
        walletAfter.put("wallet_id", "wallet-1");
        walletAfter.put("balance", new BigDecimal("150000"));
        when(walletMapper.findByMemberId("member-1")).thenReturn(walletBefore, walletAfter);
        when(walletMapper.addBalance("wallet-1", new BigDecimal("50000"))).thenReturn(1);
        when(simplePasswordVerificationService.verify("member-1", PASSWORD)).thenReturn(true);

        GroupPurchaseLeaveResponse result = service.leave("member-1", "1", PASSWORD);

        assertEquals("1", result.getGpId());
        assertEquals(10523L, result.getParticipantId());
        assertEquals(0, result.getCurrentQuantity());
        assertEquals(10, result.getTargetQuantity());
        assertEquals(new BigDecimal("50000"), result.getRefundedAmount());
        assertEquals(new BigDecimal("150000"), result.getRefundedWalletBalance());
        assertEquals("CANCELLED", result.getPaymentStatus());
        assertNotNull(result.getCanceledAt());

        verify(walletMapper).addBalance("wallet-1", new BigDecimal("50000"));
        ArgumentCaptor<Map<String, Object>> txnCaptor = ArgumentCaptor.forClass(Map.class);
        verify(transactionMapper).insert(txnCaptor.capture());
        assertEquals("REFUND", txnCaptor.getValue().get("txnType"));
        assertEquals(new BigDecimal("50000"), txnCaptor.getValue().get("price"));
        String txnMemo = (String) txnCaptor.getValue().get("memo");
        assertTrue(txnMemo.contains("참여 취소 환불"));
        assertFalse(txnMemo.contains("gpId"));
    }

    @Test
    @DisplayName("결제 전(PENDING) 참여를 취소하면 환불 없이 취소 처리만 한다")
    void should_returnLeaveResponse_when_pendingParticipantLeaves() {
        GroupPurchaseServiceImpl service = service();
        Map<String, Object> gpRow = savedRow();
        gpRow.put("deadline", LocalDateTime.now().plusDays(5));
        Map<String, Object> updatedGpRow = savedRow();
        updatedGpRow.put("deadline", LocalDateTime.now().plusDays(5));
        updatedGpRow.put("current_quantity", 0);
        Map<String, Object> participantRow = participantRow(10524L, "1", "member-1", 1);
        when(groupPurchaseMapper.findById("1")).thenReturn(gpRow, updatedGpRow);
        when(groupPurchaseMapper.findParticipant("1", "member-1")).thenReturn(participantRow);
        when(groupPurchaseMapper.cancelParticipant(eq("1"), eq("member-1"), any())).thenReturn(1);
        when(groupPurchaseMapper.decreaseQuantity("1", 1)).thenReturn(1);
        when(simplePasswordVerificationService.verify("member-1", PASSWORD)).thenReturn(true);

        GroupPurchaseLeaveResponse result = service.leave("member-1", "1", PASSWORD);

        assertNull(result.getRefundedWalletBalance());
        verifyNoInteractions(walletMapper);
        verifyNoInteractions(transactionMapper);
    }

    @Test
    @DisplayName("존재하지 않는 공동구매를 취소하려 하면 예외가 발생한다")
    void should_throwException_when_gpNotFound_onLeave() {
        GroupPurchaseServiceImpl service = service();
        when(groupPurchaseMapper.findById("999")).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.leave("member-1", "999", PASSWORD));

        assertEquals("공동구매를 찾을 수 없습니다.", exception.getMessage());
        verify(groupPurchaseMapper, never()).cancelParticipant(any(), any(), any());
        verifyNoInteractions(simplePasswordVerificationService);
    }

    @Test
    @DisplayName("참여한 적 없는 회원이 취소를 시도하면 예외가 발생한다")
    void should_throwException_when_notParticipant_onLeave() {
        GroupPurchaseServiceImpl service = service();
        when(groupPurchaseMapper.findById("1")).thenReturn(savedRow());
        when(groupPurchaseMapper.findParticipant("1", "member-1")).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.leave("member-1", "1", PASSWORD));

        assertEquals("참여 내역을 찾을 수 없습니다.", exception.getMessage());
        verify(groupPurchaseMapper, never()).cancelParticipant(any(), any(), any());
        verifyNoInteractions(simplePasswordVerificationService);
    }

    @Test
    @DisplayName("간편 비밀번호가 일치하지 않으면 참여를 취소하지 않는다")
    void should_throwException_when_passwordMismatch_onLeave() {
        GroupPurchaseServiceImpl service = service();
        when(groupPurchaseMapper.findById("1")).thenReturn(savedRow());
        when(groupPurchaseMapper.findParticipant("1", "member-1"))
                .thenReturn(participantRow(10523L, "1", "member-1", 2));
        when(simplePasswordVerificationService.verify("member-1", "000000")).thenReturn(false);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.leave("member-1", "1", "000000"));

        assertEquals("간편 비밀번호가 일치하지 않습니다.", exception.getMessage());
        verify(groupPurchaseMapper, never()).cancelParticipant(any(), any(), any());
        verifyNoInteractions(walletMapper);
    }

    @Test
    @DisplayName("동시 중복 취소 요청으로 cancelParticipant의 영향 행이 0이면 이미 취소된 참여로 거절한다")
    void should_throwConflict_when_cancelParticipantAffectsNoRows() {
        GroupPurchaseServiceImpl service = service();
        when(groupPurchaseMapper.findById("1")).thenReturn(savedRow());
        when(groupPurchaseMapper.findParticipant("1", "member-1"))
                .thenReturn(participantRow(10523L, "1", "member-1", 2));
        when(groupPurchaseMapper.cancelParticipant(eq("1"), eq("member-1"), any())).thenReturn(0);
        when(simplePasswordVerificationService.verify("member-1", PASSWORD)).thenReturn(true);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.leave("member-1", "1", PASSWORD));

        assertEquals(org.springframework.http.HttpStatus.CONFLICT, exception.getStatus());
        assertEquals("이미 취소된 참여입니다.", exception.getMessage());
        verify(groupPurchaseMapper, never()).decreaseQuantity(any(), anyInt());
        verifyNoInteractions(walletMapper);
    }

    @Test
    @DisplayName("목표 수량 달성 또는 마감 후에는 decreaseQuantity의 영향 행이 0이라 관리자 문의 안내로 거절한다")
    void should_throwConflict_when_decreaseQuantityAffectsNoRows_onLeave() {
        GroupPurchaseServiceImpl service = service();
        when(groupPurchaseMapper.findById("1")).thenReturn(savedRow());
        when(groupPurchaseMapper.findParticipant("1", "member-1"))
                .thenReturn(participantRow(10523L, "1", "member-1", 2));
        when(groupPurchaseMapper.cancelParticipant(eq("1"), eq("member-1"), any())).thenReturn(1);
        // 목표 수량 달성(confirmed) 또는 마감 후 상태라 decreaseQuantity의 조건부 WHERE를 만족하는 행이 없다.
        when(groupPurchaseMapper.decreaseQuantity("1", 2)).thenReturn(0);
        when(simplePasswordVerificationService.verify("member-1", PASSWORD)).thenReturn(true);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.leave("member-1", "1", PASSWORD));

        assertEquals(org.springframework.http.HttpStatus.CONFLICT, exception.getStatus());
        assertEquals("목표 수량 달성 또는 마감 후에는 참여를 취소할 수 없습니다. 관리자에게 문의해주세요.", exception.getMessage());
        verifyNoInteractions(walletMapper);
        verifyNoInteractions(transactionMapper);
    }

    @Test
    @DisplayName("결제 완료(PAID) 참여자 전원을 환불하며 게시글을 취소한다")
    void should_cancelAndRefundAllPaidParticipants_when_groupPurchaseIsOpen() {
        GroupPurchaseServiceImpl service = service();
        Map<String, Object> gpRow = savedRow();
        gpRow.put("deadline", LocalDateTime.now().plusDays(5));
        when(groupPurchaseMapper.findById("1")).thenReturn(gpRow);
        when(groupPurchaseMapper.cancelGroupPurchase("1")).thenReturn(1);

        Map<String, Object> participant1 = participantRow(10523L, "1", "member-1", 2);
        participant1.put("paid_amount", new BigDecimal("50000"));
        participant1.put("payment_status", "PAID");
        Map<String, Object> participant2 = participantRow(10524L, "1", "member-2", 1);
        participant2.put("paid_amount", new BigDecimal("25000"));
        participant2.put("payment_status", "PAID");
        when(groupPurchaseMapper.findActiveParticipants("1")).thenReturn(List.of(participant1, participant2));
        when(groupPurchaseMapper.cancelParticipant(eq("1"), eq("member-1"), any())).thenReturn(1);
        when(groupPurchaseMapper.cancelParticipant(eq("1"), eq("member-2"), any())).thenReturn(1);

        Map<String, Object> wallet1 = new HashMap<>();
        wallet1.put("wallet_id", "wallet-1");
        Map<String, Object> wallet2 = new HashMap<>();
        wallet2.put("wallet_id", "wallet-2");
        when(walletMapper.findByMemberId("member-1")).thenReturn(wallet1);
        when(walletMapper.findByMemberId("member-2")).thenReturn(wallet2);
        when(walletMapper.addBalance("wallet-1", new BigDecimal("50000"))).thenReturn(1);
        when(walletMapper.addBalance("wallet-2", new BigDecimal("25000"))).thenReturn(1);
        when(simplePasswordVerificationService.verify("admin-1", PASSWORD)).thenReturn(true);

        GroupPurchaseCancelResponse result = service.cancel("admin-1", "1", PASSWORD);

        assertEquals("1", result.getGpId());
        assertEquals("CANCELLED", result.getStatus());
        assertNotNull(result.getCanceledAt());
        assertEquals(2, result.getRefundedParticipants().size());
        assertTrue(result.getRefundedParticipants().stream()
                .allMatch(p -> "CANCELLED".equals(p.getPaymentStatus())));

        verify(walletMapper).addBalance("wallet-1", new BigDecimal("50000"));
        verify(walletMapper).addBalance("wallet-2", new BigDecimal("25000"));
        ArgumentCaptor<Map<String, Object>> txnCaptor = ArgumentCaptor.forClass(Map.class);
        verify(transactionMapper, times(2)).insert(txnCaptor.capture());
        assertTrue(txnCaptor.getAllValues().stream().allMatch(txn -> "REFUND".equals(txn.get("txnType"))));
        assertTrue(txnCaptor.getAllValues().stream()
                .allMatch(txn -> ((String) txn.get("memo")).contains("작성자 취소로 인한 환불")));
        assertTrue(txnCaptor.getAllValues().stream()
                .noneMatch(txn -> ((String) txn.get("memo")).contains("gpId")));
    }

    @Test
    @DisplayName("결제 전(PENDING) 참여는 환불 없이 취소만 하고 환불 목록에는 포함하지 않는다")
    void should_cancelPendingParticipantWithoutRefund_onCancel() {
        GroupPurchaseServiceImpl service = service();
        Map<String, Object> gpRow = savedRow();
        gpRow.put("deadline", LocalDateTime.now().plusDays(5));
        when(groupPurchaseMapper.findById("1")).thenReturn(gpRow);
        when(groupPurchaseMapper.cancelGroupPurchase("1")).thenReturn(1);

        Map<String, Object> paidParticipant = participantRow(10523L, "1", "member-1", 2);
        paidParticipant.put("paid_amount", new BigDecimal("50000"));
        paidParticipant.put("payment_status", "PAID");
        Map<String, Object> pendingParticipant = participantRow(10524L, "1", "member-2", 1);
        pendingParticipant.put("payment_status", "PENDING");
        when(groupPurchaseMapper.findActiveParticipants("1")).thenReturn(List.of(paidParticipant, pendingParticipant));
        when(groupPurchaseMapper.cancelParticipant(eq("1"), eq("member-1"), any())).thenReturn(1);
        when(groupPurchaseMapper.cancelParticipant(eq("1"), eq("member-2"), any())).thenReturn(1);

        Map<String, Object> wallet1 = new HashMap<>();
        wallet1.put("wallet_id", "wallet-1");
        when(walletMapper.findByMemberId("member-1")).thenReturn(wallet1);
        when(walletMapper.addBalance("wallet-1", new BigDecimal("50000"))).thenReturn(1);
        when(simplePasswordVerificationService.verify("admin-1", PASSWORD)).thenReturn(true);

        GroupPurchaseCancelResponse result = service.cancel("admin-1", "1", PASSWORD);

        assertEquals(1, result.getRefundedParticipants().size());
        assertEquals("member-1", result.getRefundedParticipants().get(0).getMemberId());
        // PENDING 참여(member-2)도 cancelParticipant로 이력은 CANCELLED가 되지만 환불 대상이 아니므로 응답 목록엔 없다.
        verify(groupPurchaseMapper).cancelParticipant(eq("1"), eq("member-2"), any());
        verify(walletMapper, never()).findByMemberId("member-2");
        verify(transactionMapper, times(1)).insert(any());
    }

    @Test
    @DisplayName("동시에 leave()로 이미 취소된 참여자는 환불 대상에서 자연히 제외된다")
    void should_skipParticipant_when_alreadyCancelledConcurrently_onCancel() {
        GroupPurchaseServiceImpl service = service();
        Map<String, Object> gpRow = savedRow();
        gpRow.put("deadline", LocalDateTime.now().plusDays(5));
        when(groupPurchaseMapper.findById("1")).thenReturn(gpRow);
        when(groupPurchaseMapper.cancelGroupPurchase("1")).thenReturn(1);

        Map<String, Object> participant1 = participantRow(10523L, "1", "member-1", 2);
        participant1.put("paid_amount", new BigDecimal("50000"));
        participant1.put("payment_status", "PAID");
        when(groupPurchaseMapper.findActiveParticipants("1")).thenReturn(List.of(participant1));
        // member-1이 이 배치 조회 이후, 환불 처리 직전에 leave()로 먼저 취소를 완료한 상황을 재현한다.
        when(groupPurchaseMapper.cancelParticipant(eq("1"), eq("member-1"), any())).thenReturn(0);
        when(simplePasswordVerificationService.verify("admin-1", PASSWORD)).thenReturn(true);

        GroupPurchaseCancelResponse result = service.cancel("admin-1", "1", PASSWORD);

        assertTrue(result.getRefundedParticipants().isEmpty());
        verifyNoInteractions(walletMapper);
        verifyNoInteractions(transactionMapper);
    }

    @Test
    @DisplayName("존재하지 않는 공동구매를 취소하려 하면 예외가 발생한다")
    void should_throwException_when_gpNotFound_onCancel() {
        GroupPurchaseServiceImpl service = service();
        when(groupPurchaseMapper.findById("999")).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.cancel("admin-1", "999", PASSWORD));

        assertEquals("공동구매를 찾을 수 없습니다.", exception.getMessage());
        verify(groupPurchaseMapper, never()).cancelGroupPurchase(any());
        verifyNoInteractions(simplePasswordVerificationService);
    }

    @Test
    @DisplayName("간편 비밀번호가 일치하지 않으면 공동구매를 취소하지 않는다")
    void should_throwException_when_passwordMismatch_onCancel() {
        GroupPurchaseServiceImpl service = service();
        when(groupPurchaseMapper.findById("1")).thenReturn(savedRow());
        when(simplePasswordVerificationService.verify("admin-1", "000000")).thenReturn(false);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.cancel("admin-1", "1", "000000"));

        assertEquals("간편 비밀번호가 일치하지 않습니다.", exception.getMessage());
        verify(groupPurchaseMapper, never()).cancelGroupPurchase(any());
        verifyNoInteractions(walletMapper);
    }

    @Test
    @DisplayName("목표 수량 달성/마감 후/이미 취소된 공동구매는 cancelGroupPurchase의 영향 행이 0이라 거절한다")
    void should_throwConflict_when_cancelGroupPurchaseAffectsNoRows() {
        GroupPurchaseServiceImpl service = service();
        when(groupPurchaseMapper.findById("1")).thenReturn(savedRow());
        when(groupPurchaseMapper.cancelGroupPurchase("1")).thenReturn(0);
        when(simplePasswordVerificationService.verify("admin-1", PASSWORD)).thenReturn(true);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.cancel("admin-1", "1", PASSWORD));

        assertEquals(org.springframework.http.HttpStatus.CONFLICT, exception.getStatus());
        assertEquals("목표 수량 달성 또는 마감 후에는 취소할 수 없습니다.", exception.getMessage());
        verify(groupPurchaseMapper, never()).findActiveParticipants(any());
        verifyNoInteractions(walletMapper);
    }

    @Test
    @DisplayName("결제 참여자가 없으면 환불 없이 빈 목록으로 취소만 처리한다")
    void should_cancelWithoutRefund_when_noPaidParticipants() {
        GroupPurchaseServiceImpl service = service();
        when(groupPurchaseMapper.findById("1")).thenReturn(savedRow());
        when(groupPurchaseMapper.cancelGroupPurchase("1")).thenReturn(1);
        when(groupPurchaseMapper.findActiveParticipants("1")).thenReturn(List.of());
        when(simplePasswordVerificationService.verify("admin-1", PASSWORD)).thenReturn(true);

        GroupPurchaseCancelResponse result = service.cancel("admin-1", "1", PASSWORD);

        assertTrue(result.getRefundedParticipants().isEmpty());
        verifyNoInteractions(walletMapper);
        verifyNoInteractions(transactionMapper);
    }

    private GroupPurchaseJoinRequest joinRequest() {
        GroupPurchaseJoinRequest request = new GroupPurchaseJoinRequest();
        ReflectionTestUtils.setField(request, "password", PASSWORD);
        ReflectionTestUtils.setField(request, "recipientName", "김애월");
        ReflectionTestUtils.setField(request, "recipientPhone", "010-1234-5678");
        ReflectionTestUtils.setField(request, "zipCode", "16856");
        ReflectionTestUtils.setField(request, "address", "서울특별시 광진구 화양동");
        ReflectionTestUtils.setField(request, "addressDetail", "세종대점 컴포즈 302호");
        return request;
    }

    private Map<String, Object> participantRow(Long participantId, String gpId, String memberId, int purchaseQuantity) {
        Map<String, Object> row = new HashMap<>();
        row.put("participant_id", participantId);
        row.put("gp_id", gpId);
        row.put("member_id", memberId);
        row.put("purchase_quantity", purchaseQuantity);
        row.put("recipient_name", "김애월");
        row.put("recipient_phone", "010-1234-5678");
        row.put("zip_code", "16856");
        row.put("address", "서울특별시 광진구 화양동");
        row.put("address_detail", "세종대점 컴포즈 302호");
        row.put("payment_status", "PENDING");
        row.put("paid_amount", null);
        row.put("paid_at", null);
        row.put("created_at", LocalDateTime.of(2026, 8, 7, 10, 0));
        return row;
    }

    private Map<String, Object> listRow(Long gpId, String status, LocalDateTime deadline,
                                          int unitPrice, int groupPrice) {
        return listRow(gpId, status, deadline, unitPrice, groupPrice, 0, 10);
    }

    private Map<String, Object> listRow(Long gpId, String status, LocalDateTime deadline,
                                          int unitPrice, int groupPrice,
                                          int currentQuantity, int targetQuantity) {
        Map<String, Object> row = new HashMap<>();
        row.put("gp_id", gpId);
        row.put("member_id", 3L);
        row.put("product_name", "사료 5kg");
        row.put("category", "사료");
        row.put("image", "group-purchase/sample.png");
        row.put("status", status);
        row.put("current_quantity", currentQuantity);
        row.put("target_quantity", targetQuantity);
        row.put("unit_price", new BigDecimal(unitPrice));
        row.put("group_price", new BigDecimal(groupPrice));
        row.put("deadline", deadline);
        row.put("created_at", LocalDateTime.now());
        return row;
    }

    private GroupPurchaseCreateRequest createRequest() {
        GroupPurchaseCreateRequest request = new GroupPurchaseCreateRequest();
        ReflectionTestUtils.setField(request, "productName", "사료 5kg");
        ReflectionTestUtils.setField(request, "category", "사료");
        ReflectionTestUtils.setField(request, "image", "/uploads/group-purchase/product.png");
        ReflectionTestUtils.setField(request, "unitPrice", new BigDecimal("30000"));
        ReflectionTestUtils.setField(request, "groupPrice", new BigDecimal("25000"));
        ReflectionTestUtils.setField(request, "deliveryMethod", "택배배송");
        ReflectionTestUtils.setField(request, "deliveryFee", new BigDecimal("3000"));
        ReflectionTestUtils.setField(request, "deliveryEstimateDays", 5);
        ReflectionTestUtils.setField(request, "description", "5kg 사료 공동구매");
        ReflectionTestUtils.setField(request, "targetQuantity", 10);
        ReflectionTestUtils.setField(request, "deadline", DEADLINE);
        return request;
    }

    private Map<String, Object> savedRow() {
        Map<String, Object> row = new HashMap<>();
        row.put("gp_id", "1");
        row.put("member_id", "member-1");
        row.put("product_name", "사료 5kg");
        row.put("category", "사료");
        row.put("image", "/uploads/group-purchase/product.png");
        row.put("unit_price", new BigDecimal("30000"));
        row.put("group_price", new BigDecimal("25000"));
        row.put("delivery_method", "택배배송");
        row.put("delivery_fee", new BigDecimal("3000"));
        row.put("delivery_date", DELIVERY_DATE);
        row.put("delivery_estimate_days", 5);
        row.put("description", "5kg 사료 공동구매");
        row.put("target_quantity", 10);
        row.put("current_quantity", 0);
        row.put("status", "OPEN");
        row.put("deadline", DEADLINE);
        row.put("created_at", CREATED_AT);
        return row;
    }

    private GroupPurchaseServiceImpl service() {
        // 조회 응답은 저장 키를 signedUrl로 감싸 내려준다. 여기서는 키를 그대로
        // 돌려주어 각 테스트가 저장값 자체를 검증하도록 둔다.
        lenient().when(fileStorage.signedUrl(anyString())).thenAnswer(i -> i.getArgument(0));
        return new GroupPurchaseServiceImpl(groupPurchaseMapper, fileStorage, walletMapper, transactionMapper,
                simplePasswordVerificationService);
    }
}
