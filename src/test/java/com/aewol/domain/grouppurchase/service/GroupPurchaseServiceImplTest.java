package com.aewol.domain.grouppurchase.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.aewol.common.exception.BusinessException;
import com.aewol.common.util.FileUtil;
import com.aewol.domain.grouppurchase.dto.GroupPurchaseCreateRequest;
import com.aewol.domain.grouppurchase.dto.GroupPurchaseImageUploadResponse;
import com.aewol.domain.grouppurchase.dto.GroupPurchaseJoinRequest;
import com.aewol.domain.grouppurchase.dto.GroupPurchaseJoinResponse;
import com.aewol.domain.grouppurchase.dto.GroupPurchaseListItemResponse;
import com.aewol.domain.grouppurchase.dto.GroupPurchaseListResponse;
import com.aewol.domain.grouppurchase.dto.GroupPurchaseResponse;
import com.aewol.domain.grouppurchase.mapper.GroupPurchaseMapper;
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
    @Mock FileUtil fileUtil;
    @Mock WalletMapper walletMapper;
    @Mock TransactionMapper transactionMapper;

    @Test
    @DisplayName("허용된 확장자의 이미지를 업로드하면 저장된 이미지 URL을 반환한다")
    void should_returnImageUrl_when_uploadSucceeds() throws IOException {
        GroupPurchaseServiceImpl service = service();
        MultipartFile image = new MockMultipartFile("image", "product.png", "image/png", "content".getBytes());
        when(fileUtil.upload(image, "group-purchase")).thenReturn("/uploads/group-purchase/product.png");

        GroupPurchaseImageUploadResponse result = service.uploadImage(image);

        assertEquals("/uploads/group-purchase/product.png", result.getImageUrl());
        verify(fileUtil).upload(image, "group-purchase");
    }

    @Test
    @DisplayName("빈 파일을 업로드하면 예외가 발생한다")
    void should_throwException_when_imageIsEmpty() {
        GroupPurchaseServiceImpl service = service();
        MultipartFile image = new MockMultipartFile("image", "product.png", "image/png", new byte[0]);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.uploadImage(image));

        assertEquals("업로드할 이미지가 없습니다.", exception.getMessage());
        verifyNoInteractions(fileUtil);
    }

    @Test
    @DisplayName("허용되지 않은 확장자를 업로드하면 예외가 발생한다")
    void should_throwException_when_extensionIsNotAllowed() {
        GroupPurchaseServiceImpl service = service();
        MultipartFile image = new MockMultipartFile("image", "product.gif", "image/gif", "content".getBytes());

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.uploadImage(image));

        assertEquals("이미지 파일(jpg, jpeg, png, webp)만 업로드할 수 있습니다.", exception.getMessage());
        verifyNoInteractions(fileUtil);
    }

    @Test
    @DisplayName("파일 저장 중 IO 오류가 발생하면 예외가 발생한다")
    void should_throwException_when_fileStorageFails() throws IOException {
        GroupPurchaseServiceImpl service = service();
        MultipartFile image = new MockMultipartFile("image", "product.jpg", "image/jpeg", "content".getBytes());
        when(fileUtil.upload(image, "group-purchase")).thenThrow(new IOException("disk full"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.uploadImage(image));

        assertEquals("이미지 업로드에 실패했습니다.", exception.getMessage());
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
        assertEquals(LocalDate.of(2026, 8, 20), result.getDeliveryDate());
        assertEquals(LocalDateTime.of(2026, 8, 15, 0, 0), result.getDeadline());

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(groupPurchaseMapper).insert(captor.capture());
        assertEquals("member-1", captor.getValue().get("memberId"));
        assertEquals("사료 5kg", captor.getValue().get("productName"));
        assertEquals(10, captor.getValue().get("targetQuantity"));
    }

    @Test
    @DisplayName("페이지 크기보다 많은 결과가 있으면 hasNext가 true이고 초과분은 잘라낸다")
    void should_trimAndSetHasNextTrue_when_moreRowsThanPageSizeExist() {
        GroupPurchaseServiceImpl service = service();
        LocalDateTime deadline = LocalDateTime.now().plusDays(5);
        when(groupPurchaseMapper.findList(isNull(), isNull(), isNull(), eq(3), eq(0)))
                .thenReturn(List.of(
                        listRow(1L, "OPEN", deadline, 30000, 25000),
                        listRow(2L, "OPEN", deadline, 30000, 25000),
                        listRow(3L, "OPEN", deadline, 30000, 25000)));

        GroupPurchaseListResponse result = service.list(null, null, null, null, 0, 2);

        assertEquals(2, result.getItems().size());
        assertTrue(result.isHasNext());
        GroupPurchaseListItemResponse first = result.getItems().get(0);
        assertEquals(1L, first.getId());
        assertEquals("진행중", first.getStatus());
        assertEquals("D-5", first.getDDay());
        assertEquals("17% 할인", first.getBadgeText());
        assertFalse(first.getIsParticipating());
    }

    @Test
    @DisplayName("로그인한 유저가 이미 참여한 게시글은 isParticipating이 true로 내려간다")
    void should_returnIsParticipatingTrue_when_memberAlreadyJoined() {
        GroupPurchaseServiceImpl service = service();
        LocalDateTime deadline = LocalDateTime.now().plusDays(5);
        when(groupPurchaseMapper.findList(isNull(), isNull(), isNull(), eq(11), eq(0)))
                .thenReturn(List.of(listRow(1L, "OPEN", deadline, 30000, 25000)));
        when(groupPurchaseMapper.findParticipant("1", "member-1"))
                .thenReturn(participantRow(10523L, "1", "member-1", 1));

        GroupPurchaseListResponse result = service.list("member-1", null, null, null, 0, 10);

        assertTrue(result.getItems().get(0).getIsParticipating());
    }

    @Test
    @DisplayName("비로그인 상태로 조회하면 isParticipating은 항상 false이고 참여 여부를 조회하지 않는다")
    void should_returnIsParticipatingFalse_when_memberIdIsNull() {
        GroupPurchaseServiceImpl service = service();
        LocalDateTime deadline = LocalDateTime.now().plusDays(5);
        when(groupPurchaseMapper.findList(isNull(), isNull(), isNull(), eq(11), eq(0)))
                .thenReturn(List.of(listRow(1L, "OPEN", deadline, 30000, 25000)));

        GroupPurchaseListResponse result = service.list(null, null, null, null, 0, 10);

        assertFalse(result.getItems().get(0).getIsParticipating());
        verify(groupPurchaseMapper, never()).findParticipant(any(), any());
    }

    @Test
    @DisplayName("결과가 없으면 빈 목록과 hasNext false를 반환한다")
    void should_returnEmptyListWithHasNextFalse_when_noRowsMatch() {
        GroupPurchaseServiceImpl service = service();
        when(groupPurchaseMapper.findList(any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of());

        GroupPurchaseListResponse result = service.list(null, null, null, null, 99, 10);

        assertTrue(result.getItems().isEmpty());
        assertFalse(result.isHasNext());
    }

    @Test
    @DisplayName("한글 상태 필터는 DB 상태 코드로 변환되어 매퍼에 전달된다")
    void should_translateKoreanStatusFilter_toDbStatusCode() {
        GroupPurchaseServiceImpl service = service();
        when(groupPurchaseMapper.findList(eq("COMPLETED"), isNull(), eq("사료"), eq(11), eq(0)))
                .thenReturn(List.of());

        service.list(null, "마감(성공)", null, "사료", 0, 10);

        verify(groupPurchaseMapper).findList("COMPLETED", null, "사료", 11, 0);
    }

    @Test
    @DisplayName("상태 필터가 없어도 예외 없이 전체 목록을 조회한다")
    void should_notThrow_when_statusFilterIsNull() {
        GroupPurchaseServiceImpl service = service();
        when(groupPurchaseMapper.findList(isNull(), isNull(), isNull(), anyInt(), anyInt()))
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
    @DisplayName("마감 전이면 저장된 status와 무관하게 진행중으로 계산한다")
    void should_returnInProgress_when_deadlineNotYetPassed() {
        GroupPurchaseServiceImpl service = service();
        LocalDateTime futureDeadline = LocalDateTime.now().plusDays(3);
        when(groupPurchaseMapper.findList(isNull(), isNull(), isNull(), eq(11), eq(0)))
                .thenReturn(List.of(listRow(1L, "OPEN", futureDeadline, 30000, 25000, 5, 10)));

        GroupPurchaseListResponse result = service.list(null, null, null, null, 0, 10);

        assertEquals("진행중", result.getItems().get(0).getStatus());
    }

    @Test
    @DisplayName("마감 후 목표 수량을 채웠으면 저장된 status가 OPEN이어도 마감(성공)으로 계산한다")
    void should_returnClosedSuccess_when_deadlinePassedAndTargetReached() {
        GroupPurchaseServiceImpl service = service();
        LocalDateTime pastDeadline = LocalDateTime.now().minusDays(1);
        when(groupPurchaseMapper.findList(isNull(), isNull(), isNull(), eq(11), eq(0)))
                .thenReturn(List.of(listRow(1L, "OPEN", pastDeadline, 30000, 25000, 10, 10)));

        GroupPurchaseListResponse result = service.list(null, null, null, null, 0, 10);

        assertEquals("마감(성공)", result.getItems().get(0).getStatus());
    }

    @Test
    @DisplayName("마감 후 목표 수량을 못 채웠으면 저장된 status가 OPEN이어도 마감(미달)으로 계산한다")
    void should_returnClosedFail_when_deadlinePassedAndTargetNotReached() {
        GroupPurchaseServiceImpl service = service();
        LocalDateTime pastDeadline = LocalDateTime.now().minusDays(1);
        when(groupPurchaseMapper.findList(isNull(), isNull(), isNull(), eq(11), eq(0)))
                .thenReturn(List.of(listRow(1L, "OPEN", pastDeadline, 30000, 25000, 4, 10)));

        GroupPurchaseListResponse result = service.list(null, null, null, null, 0, 10);

        assertEquals("마감(미달)", result.getItems().get(0).getStatus());
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

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(groupPurchaseMapper).insertParticipant(captor.capture());
        assertEquals("김애월", captor.getValue().get("recipientName"));
        assertEquals("010-1234-5678", captor.getValue().get("recipientPhone"));
        assertEquals(new BigDecimal("50000"), captor.getValue().get("paidAmount"));
        assertEquals("PAID", captor.getValue().get("paymentStatus"));
        assertEquals(777L, captor.getValue().get("txnId"));
        verify(groupPurchaseMapper).updateQuantity("1", 2);
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

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.join("member-1", "1", 2, joinRequest()));

        assertEquals("이미 참여한 공동구매입니다.", exception.getMessage());
        verify(groupPurchaseMapper, never()).updateQuantity(any(), anyInt());
    }

    private GroupPurchaseJoinRequest joinRequest() {
        GroupPurchaseJoinRequest request = new GroupPurchaseJoinRequest();
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
        ReflectionTestUtils.setField(request, "deliveryDate", LocalDate.of(2026, 8, 20));
        ReflectionTestUtils.setField(request, "description", "5kg 사료 공동구매");
        ReflectionTestUtils.setField(request, "targetQuantity", 10);
        ReflectionTestUtils.setField(request, "deadline", LocalDateTime.of(2026, 8, 15, 0, 0));
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
        row.put("delivery_date", LocalDate.of(2026, 8, 20));
        row.put("description", "5kg 사료 공동구매");
        row.put("target_quantity", 10);
        row.put("current_quantity", 0);
        row.put("status", "OPEN");
        row.put("deadline", LocalDateTime.of(2026, 8, 15, 0, 0));
        row.put("created_at", LocalDateTime.of(2026, 8, 6, 12, 0));
        return row;
    }

    private GroupPurchaseServiceImpl service() {
        return new GroupPurchaseServiceImpl(groupPurchaseMapper, fileUtil, walletMapper, transactionMapper);
    }
}
