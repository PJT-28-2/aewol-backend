package com.aewol.domain.inquiry.service;

import com.aewol.domain.inquiry.dto.InquiryCreateResponse;
import com.aewol.domain.inquiry.dto.InquiryDetailResponse;
import com.aewol.domain.inquiry.dto.InquiryListResponse;
import java.util.List;
import org.springframework.web.multipart.MultipartFile;

public interface InquiryService {
    InquiryCreateResponse createInquiry(String memberId, String category, String title, String content,
                                         String replyEmail, List<MultipartFile> attachments);
    InquiryListResponse getInquiries(String memberId, String status, int page, int size);
    InquiryDetailResponse getInquiry(String memberId, String inquiryId);
    InquiryListResponse getAdminInquiries(String status, int page, int size);
    InquiryDetailResponse getAdminInquiry(String inquiryId);
    InquiryDetailResponse answerInquiry(String inquiryId, String answer);
}
