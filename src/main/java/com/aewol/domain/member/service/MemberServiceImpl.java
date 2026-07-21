package com.aewol.domain.member.service;

import com.aewol.common.exception.BusinessException;
import com.aewol.domain.member.dto.MemberResponse;
import com.aewol.domain.member.dto.MemberUpdateRequest;
import com.aewol.domain.member.mapper.MemberMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class MemberServiceImpl implements MemberService {

    private final MemberMapper memberMapper;

    @Override
    public MemberResponse getMember(String memberId) {
        Map<String, Object> member = memberMapper.findById(memberId);
        if (member == null) {
            throw BusinessException.notFound("회원을 찾을 수 없습니다.");
        }
        return MemberResponse.builder()
                .memberId((String) member.get("member_id"))
                .email((String) member.get("email"))
                .nickname((String) member.get("nickname"))
                .name((String) member.get("name"))
                .phone((String) member.get("phone"))
                .profileImg((String) member.get("profile_img"))
                .provider((String) member.get("provider"))
                .region((String) member.get("region"))
                .incomeLevel((String) member.get("income_level"))
                .build();
    }

    @Override
    @Transactional
    public void updateMember(String memberId, MemberUpdateRequest request) {
        Map<String, Object> member = memberMapper.findById(memberId);
        if (member == null) {
            throw BusinessException.notFound("회원을 찾을 수 없습니다.");
        }

        Map<String, Object> update = new HashMap<>();
        update.put("memberId", memberId);
        update.put("nickname", request.getNickname() != null ? request.getNickname() : member.get("nickname"));
        update.put("phone", request.getPhone() != null ? request.getPhone() : member.get("phone"));
        update.put("profileImg", request.getProfileImg() != null ? request.getProfileImg() : member.get("profile_img"));
        update.put("region", request.getRegion() != null ? request.getRegion() : member.get("region"));
        update.put("incomeLevel", request.getIncomeLevel() != null ? request.getIncomeLevel() : member.get("income_level"));
        memberMapper.update(update);
    }
}
