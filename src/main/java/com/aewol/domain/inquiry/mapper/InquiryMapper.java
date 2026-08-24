package com.aewol.domain.inquiry.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface InquiryMapper {
    void insert(Map<String, Object> inquiry); // inquiry_id AUTO_INCREMENT로 채워짐
    void updateInquiryNumber(@Param("inquiryId") String inquiryId, @Param("inquiryNumber") String inquiryNumber);
    void insertAttachment(Map<String, Object> attachment);
    List<Map<String, Object>> findAttachmentsByInquiryId(@Param("inquiryId") String inquiryId);
    List<Map<String, Object>> findByMemberId(@Param("memberId") String memberId, @Param("status") String status,
                                              @Param("limit") int limit, @Param("offset") int offset);
    Map<String, Object> findByIdAndMemberId(@Param("inquiryId") String inquiryId, @Param("memberId") String memberId);
    List<Map<String, Object>> findAll(@Param("status") String status,
                                      @Param("limit") int limit,
                                      @Param("offset") int offset);
    Map<String, Object> findById(@Param("inquiryId") String inquiryId);
    int updateAnswer(@Param("inquiryId") String inquiryId, @Param("answer") String answer);
}
