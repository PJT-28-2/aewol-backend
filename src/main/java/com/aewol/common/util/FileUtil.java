package com.aewol.common.util;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

@Component
public class FileUtil {

    @Value("${file.upload-dir:./uploads}")
    private String uploadDir;

    public String upload(MultipartFile file, String subDir) throws IOException {
        Path dir = Paths.get(uploadDir, subDir);
        Files.createDirectories(dir);

        String originalFilename = file.getOriginalFilename();
        String extension = originalFilename != null && originalFilename.contains(".")
                ? originalFilename.substring(originalFilename.lastIndexOf("."))
                : "";
        String filename = UUID.randomUUID() + extension;

        Path filepath = dir.resolve(filename);
        Files.write(filepath, file.getBytes());

        return "/uploads/" + subDir + "/" + filename;
    }

    public String upload(MultipartFile file, String subDir, String extension) throws IOException {
        Path dir = Paths.get(uploadDir, subDir);
        Files.createDirectories(dir);

        String filename = UUID.randomUUID() + "." + extension;
        Path filepath = dir.resolve(filename);
        Files.write(filepath, file.getBytes());

        return "/uploads/" + subDir + "/" + filename;
    }

    /**
     * 업로드가 아니라 서버가 만들어 낸 바이트를 저장한다.
     * (AI 생성 이미지처럼 {@link MultipartFile}이 없는 경우)
     */
    public String uploadBytes(byte[] content, String subDir, String extension) throws IOException {
        Path dir = Paths.get(uploadDir, subDir);
        Files.createDirectories(dir);

        String filename = UUID.randomUUID() + "." + extension;
        Files.write(dir.resolve(filename), content);

        return "/uploads/" + subDir + "/" + filename;
    }

    public void delete(String filePath) throws IOException {
        Path path = Paths.get(uploadDir, filePath.replace("/uploads/", ""));
        Files.deleteIfExists(path);
    }
}
