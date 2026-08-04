package com.aewol.domain.share.service;

import com.aewol.domain.share.dto.*;
import java.util.List;

public interface ShareService {
    List<SharePetResponse> getAccessiblePets(String memberId);
    ShareInviteResponse invite(String memberId, ShareInviteRequest request);
    ShareInviteResponse createLinkInvite(String memberId, ShareLinkInviteRequest request);
    ShareInviteDetailResponse getInvite(String inviteCode);
    void acceptInvite(String memberId, String inviteCode);
    void respondInvite(String memberId, String accessId, String status);
    List<ShareMemberResponse> getMembers(String memberId, String petId);
    List<ShareContributionResponse> getContributions(String memberId, String petId);
    List<ShareActivityResponse> getLogs(String memberId, String petId);
    void updateRole(String ownerId, String memberId, ShareRoleUpdateRequest request);
    void removeMember(String ownerId, String petId, String memberId);
}
