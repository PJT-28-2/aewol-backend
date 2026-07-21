package com.aewol.domain.support.service;

import com.aewol.domain.member.mapper.MemberMapper;
import com.aewol.domain.support.mapper.SupportMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class SupportServiceImpl implements SupportService {

    private final SupportMapper supportMapper;
    private final MemberMapper memberMapper;

    @Override
    public List<Map<String, Object>> getPrograms(String region) {
        if (region != null && !region.isEmpty()) {
            return supportMapper.findByRegion(region);
        }
        return supportMapper.findAll();
    }

    @Override
    public List<Map<String, Object>> getMatchedPrograms(String memberId) {
        Map<String, Object> member = memberMapper.findById(memberId);
        if (member == null || member.get("region") == null) {
            return supportMapper.findAll();
        }
        return supportMapper.findByRegion((String) member.get("region"));
    }
}
