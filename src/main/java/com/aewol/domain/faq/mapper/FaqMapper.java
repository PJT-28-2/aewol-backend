package com.aewol.domain.faq.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface FaqMapper {
    List<Map<String, Object>> findAll(@Param("category") String category, @Param("keyword") String keyword);
    Map<String, Object> findById(@Param("faqId") Long faqId);
}
