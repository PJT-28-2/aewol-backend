package com.aewol.common.storage;

import org.springframework.http.MediaType;

/**
 * 저장 키의 확장자로 Content-Type을 판정한다.
 *
 * <p>파일을 내려주는 쪽({@link FileController})과 S3에 올리는 쪽({@link S3FileStorage})이
 * 같은 판정을 써야 한다. S3는 업로드 시점에 지정한 Content-Type을 그대로 응답에 실어
 * 주기 때문에, 여기서 잘못 지정하면 브라우저가 이미지를 표시하지 않고 다운로드해 버린다.
 */
final class FileMediaTypes {

    private FileMediaTypes() {
    }

    static MediaType of(String key) {
        String lower = key.toLowerCase();
        if (lower.endsWith(".png")) return MediaType.IMAGE_PNG;
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return MediaType.IMAGE_JPEG;
        if (lower.endsWith(".gif")) return MediaType.IMAGE_GIF;
        if (lower.endsWith(".webp")) return MediaType.parseMediaType("image/webp");
        if (lower.endsWith(".pdf")) return MediaType.APPLICATION_PDF;
        return MediaType.APPLICATION_OCTET_STREAM;
    }
}
