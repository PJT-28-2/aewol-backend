package com.aewol.domain.member.service;

import com.aewol.domain.member.dto.MemberResponse;
import com.aewol.domain.member.dto.MemberPasswordChangeRequest;
import com.aewol.domain.member.dto.MemberPasswordVerifyRequest;
import com.aewol.domain.member.dto.MemberUpdateRequest;
import com.aewol.domain.member.dto.MemberWithdrawRequest;

public interface MemberService {
    MemberResponse getMember(String memberId);
    void updateMember(String memberId, MemberUpdateRequest request);
    void verifyPassword(String memberId, MemberPasswordVerifyRequest request);
    void changePassword(String memberId, MemberPasswordChangeRequest request);
    void withdraw(String memberId, MemberWithdrawRequest request);
}
