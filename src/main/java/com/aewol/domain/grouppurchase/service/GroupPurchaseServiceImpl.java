package com.aewol.domain.grouppurchase.service;

import com.aewol.common.exception.BusinessException;
import com.aewol.common.util.FileUtil;
import com.aewol.domain.grouppurchase.dto.GroupPurchaseCreateRequest;
import com.aewol.domain.grouppurchase.dto.GroupPurchaseImageUploadResponse;
import com.aewol.domain.grouppurchase.dto.GroupPurchaseListItemResponse;
import com.aewol.domain.grouppurchase.dto.GroupPurchaseListResponse;
import com.aewol.domain.grouppurchase.dto.GroupPurchaseResponse;
import com.aewol.domain.grouppurchase.mapper.GroupPurchaseMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class GroupPurchaseServiceImpl implements GroupPurchaseService {

    private static final List<String> ALLOWED_IMAGE_EXTENSIONS = List.of("jpg", "jpeg", "png", "webp");

    private static final Map<String, String> KOREAN_TO_STATUS = Map.of(
            "진행중", "OPEN",
            "마감(성공)", "COMPLETED",
            "마감(미달)", "CLOSED"
    );

    private final GroupPurchaseMapper groupPurchaseMapper;
    private final FileUtil fileUtil;

    private static String toDbStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        String dbStatus = KOREAN_TO_STATUS.get(status);
        if (dbStatus == null) {
            throw new BusinessException("지원하지 않는 상태 값입니다: " + status);
        }
        return dbStatus;
    }

    @Override
    public GroupPurchaseListResponse list(String status, String keyword, String category, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = size <= 0 ? 10 : size;
        String dbStatus = toDbStatus(status);

        List<Map<String, Object>> rows = groupPurchaseMapper.findList(
                dbStatus, keyword, category, safeSize + 1, safePage * safeSize);

        boolean hasNext = rows.size() > safeSize;
        List<Map<String, Object>> pageRows = hasNext ? rows.subList(0, safeSize) : rows;

        List<GroupPurchaseListItemResponse> items = pageRows.stream()
                .map(this::toListItemResponse)
                .collect(Collectors.toList());

        return GroupPurchaseListResponse.builder()
                .items(items)
                .hasNext(hasNext)
                .build();
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

    private GroupPurchaseListItemResponse toListItemResponse(Map<String, Object> gp) {
        LocalDateTime deadline = toLocalDateTime(gp.get("deadline"));
        Integer currentQuantity = toInt(gp.get("current_quantity"));
        Integer targetQuantity = toInt(gp.get("target_quantity"));
        return GroupPurchaseListItemResponse.builder()
                .id(toLong(gp.get("gp_id")))
                .memberId(toLong(gp.get("member_id")))
                .productName((String) gp.get("product_name"))
                .category((String) gp.get("category"))
                .status(computeDisplayStatus(deadline, currentQuantity, targetQuantity))
                .currentQuantity(currentQuantity)
                .targetQuantity(targetQuantity)
                .dDay(toDDay(deadline))
                .badgeText(toBadgeText(toDecimal(gp.get("unit_price")), toDecimal(gp.get("group_price"))))
                // TODO: 로그인 유저의 실제 참여 여부(group_purchase_participant 조회)는 별도 이슈에서 처리 — 지금은 스텁
                .isParticipating(false)
                .createdAt(toLocalDateTime(gp.get("created_at")))
                .build();
    }

    /** 저장된 status 컬럼이 아니라 마감 시각·목표 수량 달성 여부로 화면 표시 상태를 계산한다. SQL 필터(findList)와 동일한 기준을 사용해야 한다. */
    private static String computeDisplayStatus(LocalDateTime deadline, Integer currentQuantity, Integer targetQuantity) {
        if (deadline == null || !deadline.isBefore(LocalDateTime.now())) {
            return "진행중";
        }
        int current = currentQuantity == null ? 0 : currentQuantity;
        int target = targetQuantity == null ? 0 : targetQuantity;
        return current >= target ? "마감(성공)" : "마감(미달)";
    }

    private static String toDDay(LocalDateTime deadline) {
        if (deadline == null) return null;
        long days = ChronoUnit.DAYS.between(LocalDate.now(), deadline.toLocalDate());
        if (days > 0) return "D-" + days;
        if (days == 0) return "D-DAY";
        return "마감";
    }

    private static String toBadgeText(BigDecimal unitPrice, BigDecimal groupPrice) {
        if (unitPrice == null || groupPrice == null
                || unitPrice.signum() <= 0 || groupPrice.compareTo(unitPrice) >= 0) {
            return null;
        }
        int rate = unitPrice.subtract(groupPrice)
                .multiply(BigDecimal.valueOf(100))
                .divide(unitPrice, 0, RoundingMode.HALF_UP)
                .intValue();
        return rate + "% 할인";
    }

    private static Long toLong(Object value) {
        if (value == null) return null;
        return value instanceof Number ? ((Number) value).longValue() : Long.parseLong(String.valueOf(value));
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
