package com.aewol.common.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aewol.common.exception.BusinessException;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.HandlerMapping;

@ExtendWith(MockitoExtension.class)
class FileControllerTest {

    private static final Pattern MAX_AGE = Pattern.compile("max-age=(\\d+)");

    @Mock FileStorage fileStorage;
    @Mock FileSignature fileSignature;

    private FileController controller;

    @BeforeEach
    void setUp() {
        controller = new FileController(fileStorage, fileSignature);
    }

    @Test
    @DisplayName("유효한 서명 URL이면 파일을 반환하고 캐시는 서명 만료를 넘지 않는다")
    void should_returnFile_withCacheBoundedBySignatureExpiry() {
        String key = "diary/a.png";
        long expires = Instant.now().getEpochSecond() + 30;
        MockHttpServletRequest request = requestFor(key);
        when(fileSignature.isValid(key, expires, "valid-signature")).thenReturn(true);
        when(fileStorage.read(key)).thenReturn(new ByteArrayInputStream(new byte[] {1, 2, 3}));

        ResponseEntity<?> response = controller.read(request, expires, "valid-signature");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(MediaType.IMAGE_PNG, response.getHeaders().getContentType());
        Matcher matcher = MAX_AGE.matcher(response.getHeaders().getCacheControl());
        assertTrue(matcher.find(), response.getHeaders().getCacheControl());
        assertTrue(Long.parseLong(matcher.group(1)) <= 30,
                "캐시가 서명 만료보다 길면 안 된다: " + response.getHeaders().getCacheControl());
        verify(fileStorage).read(key);
    }

    @Test
    @DisplayName("서명이 없거나 유효하지 않으면 파일을 읽기 전에 403으로 거절한다")
    void should_rejectBeforeRead_whenSignatureIsInvalid() {
        String key = "diary/a.png";
        long expires = Instant.now().getEpochSecond() + 30;
        MockHttpServletRequest request = requestFor(key);
        when(fileSignature.isValid(key, expires, null)).thenReturn(false);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> controller.read(request, expires, null));

        assertEquals(HttpStatus.FORBIDDEN, exception.getStatus());
        verify(fileStorage, never()).read(key);
    }

    @Test
    @DisplayName("WEBP 파일은 image/webp 타입으로 반환한다")
    void should_returnWebpMediaType() {
        String key = "pet-character/a.webp";
        long expires = Instant.now().getEpochSecond() + 30;
        MockHttpServletRequest request = requestFor(key);
        when(fileSignature.isValid(key, expires, "signature")).thenReturn(true);
        when(fileStorage.read(key)).thenReturn(new ByteArrayInputStream(new byte[] {1}));

        ResponseEntity<?> response = controller.read(request, expires, "signature");

        assertEquals(MediaType.parseMediaType("image/webp"), response.getHeaders().getContentType());
    }

    private MockHttpServletRequest requestFor(String key) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/files/" + key);
        request.setAttribute(
                HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE,
                "/api/files/" + key
        );
        return request;
    }
}
