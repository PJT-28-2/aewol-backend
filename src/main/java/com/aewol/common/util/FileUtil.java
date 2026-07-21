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

    public void delete(String filePath) throws IOException {
        Path path = Paths.get(uploadDir, filePath.replace("/uploads/", ""));
        Files.deleteIfExists(path);
    }
}
