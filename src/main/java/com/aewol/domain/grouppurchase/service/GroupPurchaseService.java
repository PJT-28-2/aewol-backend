package com.aewol.domain.grouppurchase.service;

import com.aewol.domain.grouppurchase.dto.GroupPurchaseCancelResponse;
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
    GroupPurchaseListResponse list(String memberId, String status, String keyword, String category, String cursor, int size);
    GroupPurchaseListResponse list(String memberId, String status, String keyword, String category, String cursor, int size, String sort);
    // 배포 전환 기간 호환 전용(GroupPurchaseServiceImpl 참고) — cursor 없이 구 프론트의
    // page만 오는 요청을 위한 것. 프론트 배포가 끝나면 제거 대상이다.
    GroupPurchaseListResponse list(String memberId, String status, String keyword, String category, String cursor, Integer legacyPage, int size);
    GroupPurchaseResponse create(String memberId, GroupPurchaseCreateRequest request);
    GroupPurchaseResponse getDetail(String memberId, String gpId);
    GroupPurchaseStatusResponse getStatus(String memberId, String gpId);
    List<GroupPurchaseMyItemResponse> getMyList(String memberId, String status);
    GroupPurchaseJoinResponse join(String memberId, String gpId, int quantity, GroupPurchaseJoinRequest request);
    GroupPurchaseLeaveResponse leave(String memberId, String gpId, String password);
    GroupPurchaseCancelResponse cancel(String memberId, String gpId, String password);
    GroupPurchaseImageUploadResponse uploadImage(MultipartFile image);
}
