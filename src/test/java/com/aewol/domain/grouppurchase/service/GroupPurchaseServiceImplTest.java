package com.aewol.domain.grouppurchase.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.aewol.common.exception.BusinessException;
import com.aewol.common.util.FileUtil;
import com.aewol.domain.grouppurchase.dto.GroupPurchaseCreateRequest;
import com.aewol.domain.grouppurchase.dto.GroupPurchaseImageUploadResponse;
import com.aewol.domain.grouppurchase.dto.GroupPurchaseListItemResponse;
import com.aewol.domain.grouppurchase.dto.GroupPurchaseListResponse;
import com.aewol.domain.grouppurchase.dto.GroupPurchaseResponse;
import com.aewol.domain.grouppurchase.mapper.GroupPurchaseMapper;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

@ExtendWith(MockitoExtension.class)
class GroupPurchaseServiceImplTest {

    @Mock GroupPurchaseMapper groupPurchaseMapper;
    @Mock FileUtil fileUtil;

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

        GroupPurchaseListResponse result = service.list(null, null, null, 0, 2);

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
    @DisplayName("결과가 없으면 빈 목록과 hasNext false를 반환한다")
    void should_returnEmptyListWithHasNextFalse_when_noRowsMatch() {
        GroupPurchaseServiceImpl service = service();
        when(groupPurchaseMapper.findList(any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of());

        GroupPurchaseListResponse result = service.list(null, null, null, 99, 10);

        assertTrue(result.getItems().isEmpty());
        assertFalse(result.isHasNext());
    }

    @Test
    @DisplayName("한글 상태 필터는 DB 상태 코드로 변환되어 매퍼에 전달된다")
    void should_translateKoreanStatusFilter_toDbStatusCode() {
        GroupPurchaseServiceImpl service = service();
        when(groupPurchaseMapper.findList(eq("COMPLETED"), isNull(), eq("사료"), eq(11), eq(0)))
                .thenReturn(List.of());

        service.list("마감(성공)", null, "사료", 0, 10);

        verify(groupPurchaseMapper).findList("COMPLETED", null, "사료", 11, 0);
    }

    @Test
    @DisplayName("상태 필터가 없어도 예외 없이 전체 목록을 조회한다")
    void should_notThrow_when_statusFilterIsNull() {
        GroupPurchaseServiceImpl service = service();
        when(groupPurchaseMapper.findList(isNull(), isNull(), isNull(), anyInt(), anyInt()))
                .thenReturn(List.of());

        assertDoesNotThrow(() -> service.list(null, null, null, 0, 10));
    }

    private Map<String, Object> listRow(Long gpId, String status, LocalDateTime deadline,
                                          int unitPrice, int groupPrice) {
        Map<String, Object> row = new HashMap<>();
        row.put("gp_id", gpId);
        row.put("member_id", 3L);
        row.put("product_name", "사료 5kg");
        row.put("category", "사료");
        row.put("status", status);
        row.put("current_quantity", 0);
        row.put("target_quantity", 10);
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
        return new GroupPurchaseServiceImpl(groupPurchaseMapper, fileUtil);
    }
}
