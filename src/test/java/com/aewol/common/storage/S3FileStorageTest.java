package com.aewol.common.storage;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.net.URI;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

class S3FileStorageTest {

    private static final String BUCKET = "aewol-test-bucket";

    private S3Client s3;
    private S3Presigner presigner;
    private S3FileStorage storage;

    @BeforeEach
    void setUp() throws Exception {
        s3 = mock(S3Client.class);
        presigner = mock(S3Presigner.class);

        PresignedGetObjectRequest presigned = mock(PresignedGetObjectRequest.class);
        when(presigned.url()).thenReturn(URI.create("https://s3.example.com/signed").toURL());
        when(presigner.presignGetObject(any(GetObjectPresignRequest.class))).thenReturn(presigned);

        FileSignature signature =
                new FileSignature("test-secret-key-for-file-signature-256bit", 3600, 600);
        storage = new S3FileStorage(s3, presigner, BUCKET, "public", "https://cdn.test", signature);
    }

    @Test
    @DisplayName("저장하면 논리 폴더로 시작하는 키를 만들고 확장자에 맞는 Content-Type을 붙인다")
    void should_storeWithContentType() {
        String key = storage.store("hello".getBytes(), "diary", "png");

        assertTrue(key.startsWith("diary/"), "키는 논리 폴더로 시작해야 한다: " + key);
        assertTrue(key.endsWith(".png"));

        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3).putObject(captor.capture(), any(RequestBody.class));
        assertEquals(BUCKET, captor.getValue().bucket());
        assertEquals(key, captor.getValue().key());
        // Content-Type을 빠뜨리면 브라우저가 이미지를 표시하지 않고 내려받아 버린다.
        assertEquals("image/png", captor.getValue().contentType());
    }

    @Test
    @DisplayName("같은 만료 구간 안에서는 presigned URL을 다시 만들지 않고 재사용한다")
    void should_reuseSignedUrlWithinSameWindow() {
        String first = storage.signedUrl("diary/a.png");
        String second = storage.signedUrl("diary/a.png");

        assertEquals(first, second, "같은 구간에서는 URL이 같아야 브라우저 캐시가 동작한다");
        // 매번 새로 서명하면 URL이 달라져 캐시가 무력화된다.
        verify(presigner, times(1)).presignGetObject(any(GetObjectPresignRequest.class));
    }

    @Test
    @DisplayName("키가 다르면 각각 서명한다")
    void should_signEachKeySeparately() {
        storage.signedUrl("diary/a.png");
        storage.signedUrl("diary/b.png");

        verify(presigner, times(2)).presignGetObject(any(GetObjectPresignRequest.class));
    }

    @Test
    @DisplayName("로컬 저장소 시절의 /uploads/ 형식 값도 같은 키로 정규화해 서명한다")
    void should_normalizeLegacyUploadsPrefix() {
        String fromKey = storage.signedUrl("diary/a.png");
        String fromLegacyUrl = storage.signedUrl("/uploads/diary/a.png");

        assertEquals(fromKey, fromLegacyUrl);
        // 정규화가 되면 두 호출이 같은 캐시 항목을 쓰므로 서명은 한 번만 일어난다.
        verify(presigner, times(1)).presignGetObject(any(GetObjectPresignRequest.class));

        ArgumentCaptor<GetObjectPresignRequest> captor =
                ArgumentCaptor.forClass(GetObjectPresignRequest.class);
        verify(presigner).presignGetObject(captor.capture());
        assertEquals("diary/a.png", captor.getValue().getObjectRequest().key());
    }

    @Test
    @DisplayName("키가 비어 있으면 서명하지 않고 null을 돌려준다")
    void should_returnNullForBlankKey() {
        assertNull(storage.signedUrl(null));
        assertNull(storage.signedUrl(""));
        assertNull(storage.signedUrl("   "));

        verifyNoInteractions(presigner);
    }

    @Test
    @DisplayName("공개 CDN 주소가 있으면 공개 서빙이 켜져 있다")
    void should_enablePublicServing_when_baseUrlPresent() {
        assertTrue(storage.isPublicServingEnabled());
    }

    @Test
    @DisplayName("공개 CDN 주소가 없으면 사본을 예약하지 않는다")
    void should_skipPublish_when_baseUrlMissing() {
        FileSignature signature =
                new FileSignature("test-secret-key-for-file-signature-256bit", 3600, 600);
        S3FileStorage disabled = new S3FileStorage(s3, presigner, BUCKET, "public", "", signature);

        assertFalse(disabled.isPublicServingEnabled());
        assertNull(disabled.createPublicKey("diary/a.png"));
        assertFalse(disabled.publish("diary/a.png", "public/a.png"));
        verifyNoInteractions(s3);
    }
}
