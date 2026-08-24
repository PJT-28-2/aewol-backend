package com.aewol.domain.share.service;

import com.aewol.domain.share.dto.CareDiaryReportRequest;
import com.aewol.domain.share.dto.CareDiaryReportResponse;
import com.aewol.domain.share.dto.AdminDiaryReportDetailResponse;
import com.aewol.domain.share.dto.AdminDiaryReportListResponse;
import com.aewol.domain.share.dto.AdminDiaryReportResolutionRequest;
import com.aewol.domain.share.dto.CareDiaryResponse;
import com.aewol.domain.share.dto.CareDiaryUpdateRequest;
import com.aewol.domain.share.dto.CareDiaryVisibilityRequest;
import java.util.List;
import org.springframework.web.multipart.MultipartFile;

public interface CareDiaryService {

    CareDiaryResponse create(String memberId, String petId, String diaryDate,
                             String content, MultipartFile image);

    List<CareDiaryResponse> getMonthly(String memberId, String petId, String yearMonth);

    CareDiaryResponse getDetail(String memberId, String diaryId);

    CareDiaryResponse update(String memberId, String diaryId, CareDiaryUpdateRequest request);

    CareDiaryResponse changeVisibility(String memberId, String diaryId,
                                       CareDiaryVisibilityRequest request);

    CareDiaryReportResponse report(String memberId, String diaryId, CareDiaryReportRequest request);

    AdminDiaryReportListResponse getAdminReports(String status, int page, int size);

    AdminDiaryReportDetailResponse getAdminReport(String reportId);

    AdminDiaryReportDetailResponse resolveAdminReport(String adminId, String reportId,
                                                       AdminDiaryReportResolutionRequest request);

    void delete(String memberId, String diaryId);
}
