package com.aewol.domain.grouppurchase.service;

import com.aewol.common.exception.BusinessException;
import com.aewol.common.util.FileUtil;
import com.aewol.domain.grouppurchase.dto.GroupPurchaseCreateRequest;
import com.aewol.domain.grouppurchase.dto.GroupPurchaseImageUploadResponse;
import com.aewol.domain.grouppurchase.dto.GroupPurchaseResponse;
import com.aewol.domain.grouppurchase.mapper.GroupPurchaseMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
    public GroupPurchaseResponse create(String memberId, GroupPurchaseCreateRequest request) {
        Map<String, Object> gp = new HashMap<>();
        gp.put("memberId", memberId);
        gp.put("productName", request.getProductName());
        gp.put("category", request.getCategory());
        gp.put("image", request.getImage());
        gp.put("unitPrice", request.getUnitPrice());
        gp.put("groupPrice", request.getGroupPrice());
        gp.put("deliveryMethod", request.getDeliveryMethod());
        gp.put("deliveryFee", request.getDeliveryFee());
        gp.put("deliveryDate", request.getDeliveryDate());
        gp.put("description", request.getDescription());
        gp.put("targetQuantity", request.getTargetQuantity());
        gp.put("deadline", request.getDeadline());
        groupPurchaseMapper.insert(gp); // gp_id AUTO_INCREMENT
        return toResponse(groupPurchaseMapper.findById(String.valueOf(gp.get("gpId"))));
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

    private GroupPurchaseResponse toResponse(Map<String, Object> gp) {
        return GroupPurchaseResponse.builder()
                .gpId(String.valueOf(gp.get("gp_id")))
                .memberId(String.valueOf(gp.get("member_id")))
                .productName((String) gp.get("product_name"))
                .category((String) gp.get("category"))
                .image((String) gp.get("image"))
                .unitPrice(toDecimal(gp.get("unit_price")))
                .groupPrice(toDecimal(gp.get("group_price")))
                .deliveryMethod((String) gp.get("delivery_method"))
                .deliveryFee(toDecimal(gp.get("delivery_fee")))
                .deliveryDate(toLocalDate(gp.get("delivery_date")))
                .description((String) gp.get("description"))
                .targetQuantity(toInt(gp.get("target_quantity")))
                .currentQuantity(toInt(gp.get("current_quantity")))
                .status((String) gp.get("status"))
                .deadline(toLocalDateTime(gp.get("deadline")))
                .createdAt(toLocalDateTime(gp.get("created_at")))
                .build();
    }

    private static BigDecimal toDecimal(Object value) {
        if (value == null) return null;
        return value instanceof BigDecimal ? (BigDecimal) value : new BigDecimal(String.valueOf(value));
    }

    private static Integer toInt(Object value) {
        if (value == null) return null;
        return value instanceof Number ? ((Number) value).intValue() : Integer.parseInt(String.valueOf(value));
    }

    private static LocalDate toLocalDate(Object value) {
        if (value == null) return null;
        if (value instanceof LocalDate) return (LocalDate) value;
        if (value instanceof java.sql.Date) return ((java.sql.Date) value).toLocalDate();
        return LocalDate.parse(String.valueOf(value));
    }

    private static LocalDateTime toLocalDateTime(Object value) {
        if (value == null) return null;
        if (value instanceof LocalDateTime) return (LocalDateTime) value;
        if (value instanceof Timestamp) return ((Timestamp) value).toLocalDateTime();
        return LocalDateTime.parse(String.valueOf(value));
    }
}
