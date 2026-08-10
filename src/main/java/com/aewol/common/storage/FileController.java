package com.aewol.common.storage;

import com.aewol.common.exception.BusinessException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.InputStream;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.HandlerMapping;

import javax.servlet.http.HttpServletRequest;
import java.time.Duration;

/**
 * 서명된 URL로 요청된 파일을 내려준다.
 *
 * <p>{@code <img>} 태그는 Authorization 헤더를 붙일 수 없어 JWT로 보호할 수 없다.
 * 그래서 이 경로는 인증 대신 URL에 실린 서명과 만료 시각으로 접근을 판단한다.
 * 서명은 서버만 만들 수 있으므로 링크를 아는 사람만 열 수 있다.
 */
@Tag(name = "File", description = "파일 조회 API")
@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
public class FileController {

    private final FileStorage fileStorage;
    private final FileSignature fileSignature;

    @Operation(summary = "서명된 파일 조회", description = "서비스가 발급한 서명 URL로만 접근할 수 있다.")
    @GetMapping("/**")
    public ResponseEntity<InputStreamResource> read(
            HttpServletRequest request,
            // 필수로 걸면 파라미터가 빠졌을 때 500이 난다. 서명 없는 요청도 결국
            // 거절 대상이므로 optional로 받아 아래에서 함께 403으로 처리한다.
            @RequestParam(value = "expires", required = false) Long expires,
            @RequestParam(value = "signature", required = false) String signature) {

        String key = extractKey(request);
        if (expires == null || !fileSignature.isValid(key, expires, signature)) {
            throw BusinessException.forbidden("만료되었거나 잘못된 링크입니다.");
        }

        InputStream content = fileStorage.read(key);
        return ResponseEntity.ok()
                .contentType(mediaTypeOf(key))
                // 만료 시각이 URL에 박혀 있으므로 그 사이에는 캐시해도 안전하다.
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(10)).cachePrivate())
                .body(new InputStreamResource(content));
    }

    /** /api/files/diary/abc.png → diary/abc.png */
    private String extractKey(HttpServletRequest request) {
        String path = (String) request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE);
        String prefix = "/api/files/";
        if (path == null || !path.startsWith(prefix)) {
            throw BusinessException.notFound("파일을 찾을 수 없습니다.");
        }
        return path.substring(prefix.length());
    }

    private MediaType mediaTypeOf(String key) {
        String lower = key.toLowerCase();
        if (lower.endsWith(".png")) return MediaType.IMAGE_PNG;
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return MediaType.IMAGE_JPEG;
        if (lower.endsWith(".gif")) return MediaType.IMAGE_GIF;
        if (lower.endsWith(".webp")) return MediaType.parseMediaType("image/webp");
        if (lower.endsWith(".pdf")) return MediaType.APPLICATION_PDF;
        return MediaType.APPLICATION_OCTET_STREAM;
    }
}
