package com.aewol.common.storage;

import com.aewol.common.exception.BusinessException;
import org.springframework.http.HttpStatus;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 서버 로컬 디스크에 저장하는 기본 구현.
 *
 * <p>AWS 자격증명이 없는 팀원도 개발할 수 있도록 이쪽이 기본값이다.
 *
 * <p>prod에서는 {@link S3FileStorage}가 대신 등록된다. 프로파일을 나열하지 않고
 * "prod가 아닐 때"로 적은 것은, 프로파일이 늘어날 때 어느 쪽에도 걸리지 않아 빈이
 * 사라지거나 양쪽에 걸려 중복되는 사고를 막기 위해서다.
 */
@Slf4j
@Component
@Profile("!prod")
public class LocalFileStorage implements FileStorage {

    private final Path root;
    private final FileSignature signature;

    public LocalFileStorage(@Value("${file.upload-dir:./uploads}") String uploadDir,
                            FileSignature signature) {
        // 상대 경로는 JVM 작업 디렉터리에 따라 위치가 달라진다. 시작 시 절대 경로로 고정한다.
        this.root = Paths.get(uploadDir).toAbsolutePath().normalize();
        this.signature = signature;
        log.info("로컬 파일 저장소 경로: {}", root);
    }

    @Override
    public String store(byte[] content, String directory, String extension) {
        String key = directory + "/" + UUID.randomUUID() + "." + extension;
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, content);
            return key;
        } catch (IOException e) {
            log.error("[FILE_STORE_FAILED] 저장 실패 - key: {}, {}바이트", key, content.length, e);
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "파일을 저장하지 못했어요. 잠시 후 다시 시도해 주세요.");
        }
    }

    @Override
    public void delete(String key) {
        // 이전 파일 정리는 부가 작업이다. 실패해도 본 작업을 깨뜨려선 안 되므로
        // 경로 검증 실패(잘못된 키)까지 여기서 삼킨다.
        try {
            Files.deleteIfExists(resolve(normalize(key)));
        } catch (IOException | RuntimeException e) {
            log.warn("[FILE_DELETE_FAILED] 삭제 실패 - key: {}", key, e);
        }
    }

    @Override
    public InputStream read(String key) {
        Path target = resolve(normalize(key));
        if (!Files.isRegularFile(target)) {
            throw BusinessException.notFound("파일을 찾을 수 없습니다.");
        }
        try {
            return Files.newInputStream(target);
        } catch (IOException e) {
            log.error("[FILE_READ_FAILED] 읽기 실패 - key: {}", key, e);
            throw BusinessException.notFound("파일을 찾을 수 없습니다.");
        }
    }

    @Override
    public String signedUrl(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        String normalizedKey = normalize(key);
        long expiresAt = signature.expiresAt();
        return "/api/files/" + normalizedKey + "?expires=" + expiresAt
                + "&signature=" + signature.sign(normalizedKey, expiresAt);
    }

    /**
     * 이 리팩토링 이전에 저장된 값은 {@code /uploads/diary/a.png}처럼 URL 형태였다.
     * 그대로 두면 절대 경로로 해석돼 저장 루트를 벗어나므로 접두사를 떼어 키로 맞춘다.
     */
    private String normalize(String key) {
        if (key != null && key.startsWith("/uploads/")) {
            return key.substring("/uploads/".length());
        }
        return key;
    }

    /**
     * 키를 실제 경로로 바꾸면서 저장 루트를 벗어나지 않는지 확인한다.
     * {@code ../}가 섞인 키로 바깥 파일을 읽거나 지우지 못하게 막는다.
     */
    private Path resolve(String key) {
        Path target = root.resolve(key).normalize();
        if (!target.startsWith(root)) {
            throw BusinessException.forbidden("잘못된 파일 경로입니다.");
        }
        return target;
    }
}
