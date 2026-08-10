package com.aewol.common.storage;

import static org.junit.jupiter.api.Assertions.*;

import com.aewol.common.exception.BusinessException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalFileStorageTest {

    @TempDir
    Path root;

    private LocalFileStorage storage;
    private FileSignature signature;

    @BeforeEach
    void setUp() {
        signature = new FileSignature("test-secret-key-for-file-signature-256bit", 3600);
        storage = new LocalFileStorage(root.toString(), signature);
    }

    @Test
    @DisplayName("저장하면 디렉터리 하위에 키를 만들고 그 키로 다시 읽을 수 있다")
    void should_storeAndRead() throws IOException {
        String key = storage.store("hello".getBytes(), "diary", "png");

        assertTrue(key.startsWith("diary/"), "키는 논리 폴더로 시작해야 한다: " + key);
        assertTrue(key.endsWith(".png"));
        try (InputStream in = storage.read(key)) {
            assertArrayEquals("hello".getBytes(), in.readAllBytes());
        }
    }

    @Test
    @DisplayName("같은 폴더에 저장해도 키가 겹치지 않는다")
    void should_generateUniqueKeys() {
        String first = storage.store("a".getBytes(), "diary", "png");
        String second = storage.store("b".getBytes(), "diary", "png");

        assertNotEquals(first, second);
    }

    @Test
    @DisplayName("삭제하면 더 이상 읽을 수 없다")
    void should_delete() {
        String key = storage.store("hello".getBytes(), "diary", "png");

        storage.delete(key);

        assertThrows(BusinessException.class, () -> storage.read(key));
    }

    @Test
    @DisplayName("없는 파일을 지워도 예외를 던지지 않는다")
    void should_ignoreMissingFile_when_deleting() {
        assertDoesNotThrow(() -> storage.delete("diary/not-there.png"));
    }

    @Test
    @DisplayName("상위 경로를 가리키는 키로 저장소 밖 파일에 접근할 수 없다")
    void should_rejectPathTraversal() throws IOException {
        Path outside = root.getParent().resolve("secret.txt");
        Files.write(outside, "secret".getBytes());

        assertThrows(BusinessException.class, () -> storage.read("../secret.txt"));
        // 삭제는 부가 작업이라 예외를 던지지 않지만, 바깥 파일에 손대서도 안 된다.
        storage.delete("../secret.txt");
        assertTrue(Files.exists(outside), "저장소 밖 파일이 지워지면 안 된다");
    }

    @Test
    @DisplayName("리팩토링 이전의 /uploads/ 형식 값도 읽고 지울 수 있다")
    void should_acceptLegacyUploadsPrefix() throws IOException {
        String key = storage.store("hello".getBytes(), "diary", "png");
        String legacy = "/uploads/" + key;

        try (InputStream in = storage.read(legacy)) {
            assertArrayEquals("hello".getBytes(), in.readAllBytes());
        }
        storage.delete(legacy);
        assertThrows(BusinessException.class, () -> storage.read(key));
    }

    @Test
    @DisplayName("삭제는 부가 작업이라 잘못된 키가 와도 예외를 던지지 않는다")
    void should_neverThrow_when_deletingInvalidKey() {
        // 이전 파일 정리가 실패해도 본 작업(생성·저장)이 깨지면 안 된다.
        assertDoesNotThrow(() -> storage.delete("../../secret.txt"));
        assertDoesNotThrow(() -> storage.delete(""));
    }

    @Test
    @DisplayName("서명 URL에는 키와 만료 시각, 서명이 들어간다")
    void should_buildSignedUrl() {
        String key = storage.store("hello".getBytes(), "diary", "png");

        String url = storage.signedUrl(key);

        assertTrue(url.startsWith("/api/files/" + key), url);
        assertTrue(url.contains("expires="), url);
        assertTrue(url.contains("signature="), url);
    }

    @Test
    @DisplayName("키가 없으면 서명 URL도 없다")
    void should_returnNull_when_keyIsMissing() {
        assertNull(storage.signedUrl(null));
        assertNull(storage.signedUrl(""));
    }
}
