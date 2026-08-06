package com.aewol.domain.bank.mapper;

import org.apache.ibatis.annotations.Mapper;
import java.util.List;
import java.util.Map;

@Mapper
public interface BankMapper {
    List<Map<String, Object>> findAll();
}
