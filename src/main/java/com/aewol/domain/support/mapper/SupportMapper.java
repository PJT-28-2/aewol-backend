package com.aewol.domain.support.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;
import java.util.Map;

@Mapper
public interface SupportMapper {
    List<Map<String, Object>> findByRegion(@Param("region") String region);
    List<Map<String, Object>> findAll();
}
