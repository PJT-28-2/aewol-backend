package com.aewol.domain.share.service;

import com.aewol.common.exception.BusinessException;
import com.aewol.domain.share.mapper.ShareMapper;
import com.aewol.domain.wallet.mapper.WalletMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
@RequiredArgsConstructor
public class ShareServiceImpl implements ShareService {

    private final ShareMapper shareMapper;
    private final WalletMapper walletMapper;

    @Override
    @Transactional
    public void invite(String memberId, Map<String, Object> request) {
        Map<String, Object> wallet = walletMapper.findByMemberId(memberId);
        if (wallet == null) throw BusinessException.notFound("지갑을 찾을 수 없습니다.");

        Map<String, Object> access = new HashMap<>();
        access.put("accessId", UUID.randomUUID().toString());
        access.put("walletId", wallet.get("wallet_id"));
        access.put("memberId", request.get("targetMemberId"));
        access.put("invitedBy", memberId);
        access.put("role", request.getOrDefault("role", "VIEWER"));
        access.put("status", "PENDING");
        shareMapper.insert(access);
    }

    @Override
    @Transactional
    public void respondInvite(String accessId, String status) {
        shareMapper.updateStatus(accessId, status);
    }

    @Override
    public List<Map<String, Object>> getSharedMembers(String memberId) {
        Map<String, Object> wallet = walletMapper.findByMemberId(memberId);
        if (wallet == null) throw BusinessException.notFound("지갑을 찾을 수 없습니다.");
        return shareMapper.findByWalletId((String) wallet.get("wallet_id"));
    }
}
