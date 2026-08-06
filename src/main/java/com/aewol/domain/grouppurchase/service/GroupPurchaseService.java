package com.aewol.domain.grouppurchase.service;

import com.aewol.domain.grouppurchase.dto.GroupPurchaseCreateRequest;
import com.aewol.domain.grouppurchase.dto.GroupPurchaseImageUploadResponse;
import com.aewol.domain.grouppurchase.dto.GroupPurchaseResponse;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

public interface GroupPurchaseService {
    List<Map<String, Object>> list();
    GroupPurchaseResponse create(String memberId, GroupPurchaseCreateRequest request);
    Map<String, Object> getDetail(String gpId);
    void join(String memberId, String gpId, int quantity);
    GroupPurchaseImageUploadResponse uploadImage(MultipartFile image);
}
