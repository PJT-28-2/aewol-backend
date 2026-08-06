package com.aewol.domain.grouppurchase.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.aewol.common.exception.BusinessException;
import com.aewol.common.util.FileUtil;
import com.aewol.domain.grouppurchase.dto.GroupPurchaseImageUploadResponse;
import com.aewol.domain.grouppurchase.mapper.GroupPurchaseMapper;
import java.io.IOException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
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

    private GroupPurchaseServiceImpl service() {
        return new GroupPurchaseServiceImpl(groupPurchaseMapper, fileUtil);
    }
}
