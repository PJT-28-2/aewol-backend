package com.aewol.domain.grouppurchase.service;

import com.aewol.domain.grouppurchase.dto.GroupPurchaseCreateRequest;
import com.aewol.domain.grouppurchase.dto.GroupPurchaseImageUploadResponse;
import com.aewol.domain.grouppurchase.dto.GroupPurchaseJoinRequest;
import com.aewol.domain.grouppurchase.dto.GroupPurchaseJoinResponse;
import com.aewol.domain.grouppurchase.dto.GroupPurchaseLeaveResponse;
import com.aewol.domain.grouppurchase.dto.GroupPurchaseListResponse;
import com.aewol.domain.grouppurchase.dto.GroupPurchaseMyItemResponse;
import com.aewol.domain.grouppurchase.dto.GroupPurchaseResponse;
import com.aewol.domain.grouppurchase.dto.GroupPurchaseStatusResponse;
import java.util.List;
import org.springframework.web.multipart.MultipartFile;

public interface GroupPurchaseService {
    GroupPurchaseListResponse list(String memberId, String status, String keyword, String category, int page, int size);
    GroupPurchaseResponse create(String memberId, GroupPurchaseCreateRequest request);
    GroupPurchaseResponse getDetail(String memberId, String gpId);
    GroupPurchaseStatusResponse getStatus(String memberId, String gpId);
    List<GroupPurchaseMyItemResponse> getMyList(String memberId, String status);
    GroupPurchaseJoinResponse join(String memberId, String gpId, int quantity, GroupPurchaseJoinRequest request);
    GroupPurchaseLeaveResponse leave(String memberId, String gpId);
    int processExpiredRefunds();
    GroupPurchaseImageUploadResponse uploadImage(MultipartFile image);
}
