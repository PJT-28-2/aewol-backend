package com.aewol.domain.grouppurchase.service;

import com.aewol.common.exception.BusinessException;
import com.aewol.domain.grouppurchase.mapper.GroupPurchaseMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
@RequiredArgsConstructor
public class GroupPurchaseServiceImpl implements GroupPurchaseService {

    private final GroupPurchaseMapper groupPurchaseMapper;

    @Override
    public List<Map<String, Object>> list() {
        return groupPurchaseMapper.findAll();
    }

    @Override
    @Transactional
    public Map<String, Object> create(String memberId, Map<String, Object> request) {
        Map<String, Object> gp = new HashMap<>(request);
        gp.put("memberId", memberId);
        groupPurchaseMapper.insert(gp); // gp_id AUTO_INCREMENT
        return groupPurchaseMapper.findById(String.valueOf(gp.get("gpId")));
    }

    @Override
    public Map<String, Object> getDetail(String gpId) {
        Map<String, Object> gp = groupPurchaseMapper.findById(gpId);
        if (gp == null) throw BusinessException.notFound("공동구매를 찾을 수 없습니다.");
        return gp;
    }

    @Override
    @Transactional
    public void join(String memberId, String gpId, int quantity) {
        Map<String, Object> participant = new HashMap<>();
        participant.put("gpId", gpId);
        participant.put("memberId", memberId);
        participant.put("quantity", quantity);
        groupPurchaseMapper.insertParticipant(participant);
        groupPurchaseMapper.updateQuantity(gpId, quantity);
    }
}
