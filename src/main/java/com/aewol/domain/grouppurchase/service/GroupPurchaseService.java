package com.aewol.domain.grouppurchase.service;

import com.aewol.domain.grouppurchase.dto.GroupPurchaseCreateRequest;
import com.aewol.domain.grouppurchase.dto.GroupPurchaseImageUploadResponse;
import com.aewol.domain.grouppurchase.dto.GroupPurchaseJoinRequest;
import com.aewol.domain.grouppurchase.dto.GroupPurchaseJoinResponse;
import com.aewol.domain.grouppurchase.dto.GroupPurchaseListResponse;
import com.aewol.domain.grouppurchase.dto.GroupPurchaseResponse;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

public interface GroupPurchaseService {
    GroupPurchaseListResponse list(String memberId, String status, String keyword, String category, int page, int size);
    GroupPurchaseResponse create(String memberId, GroupPurchaseCreateRequest request);
    Map<String, Object> getDetail(String gpId);
    GroupPurchaseJoinResponse join(String memberId, String gpId, int quantity, GroupPurchaseJoinRequest request);
    GroupPurchaseImageUploadResponse uploadImage(MultipartFile image);
}
