package com.aewol.domain.grouppurchase.service;

import com.aewol.common.exception.BusinessException;
import com.aewol.common.util.FileUtil;
import com.aewol.domain.grouppurchase.dto.GroupPurchaseImageUploadResponse;
import com.aewol.domain.grouppurchase.mapper.GroupPurchaseMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.*;

@Service
@RequiredArgsConstructor
public class GroupPurchaseServiceImpl implements GroupPurchaseService {

    private static final List<String> ALLOWED_IMAGE_EXTENSIONS = List.of("jpg", "jpeg", "png", "webp");

    private final GroupPurchaseMapper groupPurchaseMapper;
    private final FileUtil fileUtil;

    @Override
    public List<Map<String, Object>> list() {
        return groupPurchaseMapper.findAll();
    }

    @Override
    @Transactional
    public Map<String, Object> create(String memberId, Map<String, Object> request) {
        Map<String, Object> gp = new HashMap<>(request);
        gp.put("memberId", memberId);
        groupPurchaseMapper.insert(gp); // gp_id AUTO_INCREMENT
        return groupPurchaseMapper.findById(String.valueOf(gp.get("gpId")));
    }

    @Override
    public Map<String, Object> getDetail(String gpId) {
        Map<String, Object> gp = groupPurchaseMapper.findById(gpId);
        if (gp == null) throw BusinessException.notFound("공동구매를 찾을 수 없습니다.");
        return gp;
    }

    @Override
    @Transactional
    public void join(String memberId, String gpId, int quantity) {
        Map<String, Object> participant = new HashMap<>();
        participant.put("gpId", gpId);
        participant.put("memberId", memberId);
        participant.put("quantity", quantity);
        groupPurchaseMapper.insertParticipant(participant);
        groupPurchaseMapper.updateQuantity(gpId, quantity);
    }

    @Override
    public GroupPurchaseImageUploadResponse uploadImage(MultipartFile image) {
        if (image == null || image.isEmpty()) {
            throw new BusinessException("업로드할 이미지가 없습니다.");
        }

        String originalFilename = image.getOriginalFilename();
        String extension = originalFilename != null && originalFilename.contains(".")
                ? originalFilename.substring(originalFilename.lastIndexOf(".") + 1).toLowerCase()
                : "";
        if (!ALLOWED_IMAGE_EXTENSIONS.contains(extension)) {
            throw new BusinessException("이미지 파일(jpg, jpeg, png, webp)만 업로드할 수 있습니다.");
        }

        try {
            String imageUrl = fileUtil.upload(image, "group-purchase");
            return GroupPurchaseImageUploadResponse.builder()
                    .imageUrl(imageUrl)
                    .build();
        } catch (IOException e) {
            throw new BusinessException("이미지 업로드에 실패했습니다.");
        }
    }
}
