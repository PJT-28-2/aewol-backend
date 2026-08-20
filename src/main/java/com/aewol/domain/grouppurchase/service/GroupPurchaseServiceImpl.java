package com.aewol.domain.grouppurchase.service;

import com.aewol.common.exception.BusinessException;
import com.aewol.common.storage.FileStorage;
import com.aewol.domain.grouppurchase.dto.GroupPurchaseCancelParticipantResponse;
import com.aewol.domain.grouppurchase.dto.GroupPurchaseCancelResponse;
import com.aewol.domain.grouppurchase.dto.GroupPurchaseCategory;
import com.aewol.domain.grouppurchase.dto.GroupPurchaseCreateRequest;
import com.aewol.domain.grouppurchase.dto.GroupPurchaseImageUploadResponse;
import com.aewol.domain.grouppurchase.dto.GroupPurchaseJoinRequest;
import com.aewol.domain.grouppurchase.dto.GroupPurchaseJoinResponse;
import com.aewol.domain.grouppurchase.dto.GroupPurchaseLeaveResponse;
import com.aewol.domain.grouppurchase.dto.GroupPurchaseListItemResponse;
import com.aewol.domain.grouppurchase.dto.GroupPurchaseListResponse;
import com.aewol.domain.grouppurchase.dto.GroupPurchaseMyItemResponse;
import com.aewol.domain.grouppurchase.dto.GroupPurchaseResponse;
import com.aewol.domain.grouppurchase.dto.GroupPurchaseStatusParticipantResponse;
import com.aewol.domain.grouppurchase.dto.GroupPurchaseStatusResponse;
import com.aewol.domain.grouppurchase.mapper.GroupPurchaseMapper;
import com.aewol.domain.member.service.SimplePasswordVerificationService;
import com.aewol.domain.transaction.mapper.TransactionMapper;
import com.aewol.domain.wallet.mapper.WalletMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class GroupPurchaseServiceImpl implements GroupPurchaseService {

    private static final List<String> ALLOWED_IMAGE_EXTENSIONS = List.of("jpg", "jpeg", "png", "webp");

    // 목록(findList)은 마감(취소)된 게시글을 SQL에서 무조건 제외하므로(status != 'CANCELLED'),
    // CANCELLED는 이 API의 유효한 필터 값이 아니다.
    private static final Set<String> LIST_STATUS_VALUES =
            Set.of(GroupPurchaseStatus.OPEN, GroupPurchaseStatus.COMPLETED, GroupPurchaseStatus.FAILED);

    private final GroupPurchaseMapper groupPurchaseMapper;
    private final FileStorage fileStorage;
    private final WalletMapper walletMapper;
    private final TransactionMapper transactionMapper;
    private final SimplePasswordVerificationService simplePasswordVerificationService;

    /**
     * 목록 API의 status 쿼리 파라미터를 검증한다. 다른 3개 조회 API(getDetail/getStatus/getMyList)와
     * 이미 동일한 OPEN/COMPLETED/FAILED/CANCELLED 값을 쓰므로(computeStatus 참고) 별도 한글 번역이
     * 필요 없고, findList가 지원하지 않는 값만 걸러내면 된다.
     */
    private static String validateListStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        if (!LIST_STATUS_VALUES.contains(status)) {
            throw new BusinessException("지원하지 않는 상태 값입니다: " + status);
        }
        return status;
    }

    @Override
    public GroupPurchaseListResponse list(String memberId, String status, String keyword, String category, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = size <= 0 ? 10 : size;
        String dbStatus = validateListStatus(status);

        List<Map<String, Object>> rows = groupPurchaseMapper.findList(
                dbStatus, keyword, category, safeSize + 1, safePage * safeSize);

        boolean hasNext = rows.size() > safeSize;
        List<Map<String, Object>> pageRows = hasNext ? rows.subList(0, safeSize) : rows;
        Set<String> participatingGpIds = findParticipatingGpIds(memberId, pageRows);

        List<GroupPurchaseListItemResponse> items = pageRows.stream()
                .map(gp -> toListItemResponse(gp, participatingGpIds.contains(String.valueOf(gp.get("gp_id")))))
                .collect(Collectors.toList());

        return GroupPurchaseListResponse.builder()
                .items(items)
                .hasNext(hasNext)
                .build();
    }

    /**
     * 배송예정일은 관리자가 직접 입력하지 않고 "목표 달성일 + 예상 소요일"로 계산한다.
     * 등록 시점엔 아직 목표를 달성하지 않았으므로, 마감일에 확정된다고 가정한 잠정치(deadline + X)를 넣어두고,
     * join()에서 목표 수량을 처음 달성하는 순간 confirmedDate + X로 갱신한다.
     */
    @Override
    @Transactional
    public GroupPurchaseResponse create(String memberId, GroupPurchaseCreateRequest request) {
        Integer estimateDays = request.getDeliveryEstimateDays();
        LocalDate provisionalDeliveryDate = estimateDays == null
                ? null
                : request.getDeadline().toLocalDate().plusDays(estimateDays);

        Map<String, Object> gp = new HashMap<>();
        gp.put("memberId", memberId);
        gp.put("productName", request.getProductName());
        gp.put("category", request.getCategory());
        gp.put("image", request.getImage());
        gp.put("unitPrice", request.getUnitPrice());
        gp.put("groupPrice", request.getGroupPrice());
        gp.put("deliveryMethod", request.getDeliveryMethod());
        gp.put("deliveryFee", request.getDeliveryFee());
        gp.put("deliveryDate", provisionalDeliveryDate);
        gp.put("deliveryEstimateDays", estimateDays);
        gp.put("description", request.getDescription());
        gp.put("targetQuantity", request.getTargetQuantity());
        gp.put("deadline", request.getDeadline());
        groupPurchaseMapper.insert(gp); // gp_id AUTO_INCREMENT
        return toResponse(groupPurchaseMapper.findById(String.valueOf(gp.get("gpId"))), false);
    }

    @Override
    public GroupPurchaseResponse getDetail(String memberId, String gpId) {
        Map<String, Object> gp = groupPurchaseMapper.findById(gpId);
        // target_quantity <= 0(정상 생성 경로로는 나올 수 없는 레거시/비정상 데이터)은 findList와 동일하게
        // 노출하지 않는다 — 참여 여부와 무관하게 상세/결제 미리보기 화면에 잘못된 상품으로 보이는 것을 막는다.
        if (gp == null || toInt(gp.get("target_quantity")) == null || toInt(gp.get("target_quantity")) <= 0) {
            throw BusinessException.notFound("공동구매를 찾을 수 없습니다.");
        }
        boolean isParticipating = memberId != null && groupPurchaseMapper.findParticipant(gpId, memberId) != null;
        return toResponse(gp, isParticipating);
    }

    @Override
    public GroupPurchaseStatusResponse getStatus(String memberId, String gpId) {
        Map<String, Object> gp = groupPurchaseMapper.findById(gpId);
        if (gp == null) {
            throw BusinessException.notFound("공동구매를 찾을 수 없습니다.");
        }

        LocalDateTime deadline = toLocalDateTime(gp.get("deadline"));
        Integer currentQuantity = toInt(gp.get("current_quantity"));
        Integer targetQuantity = toInt(gp.get("target_quantity"));

        // 작성자는 참여자로 join하지 않으므로 findParticipant가 null일 수 있고, 이 경우 participantInfo 없이 게시글 정보만 내려준다.
        Map<String, Object> participant = groupPurchaseMapper.findParticipant(gpId, memberId);
        GroupPurchaseStatusParticipantResponse participantInfo = participant == null ? null
                : GroupPurchaseStatusParticipantResponse.builder()
                        .participantId(toLong(participant.get("participant_id")))
                        .purchaseQuantity(toInt(participant.get("purchase_quantity")))
                        .paidAmount(toDecimal(participant.get("paid_amount")))
                        .paymentStatus((String) participant.get("payment_status"))
                        .paidAt(toLocalDateTime(participant.get("paid_at")))
                        .build();

        boolean ownerCancelled = GroupPurchaseStatus.CANCELLED.equals(gp.get("status"));
        String status = computeStatus(ownerCancelled, deadline, currentQuantity, targetQuantity);

        return GroupPurchaseStatusResponse.builder()
                .memberId(String.valueOf(gp.get("member_id")))
                .productName((String) gp.get("product_name"))
                .status(status)
                .currentQuantity(currentQuantity)
                .targetQuantity(targetQuantity)
                .deadline(deadline)
                .unitPrice(toDecimal(gp.get("unit_price")))
                .groupPrice(toDecimal(gp.get("group_price")))
                .deliveryDate(toLocalDate(gp.get("delivery_date")))
                .deliveryEstimateDays(toInt(gp.get("delivery_estimate_days")))
                .participantInfo(participantInfo)
                .noticeMessage(toNoticeMessage(status))
                .build();
    }

    @Override
    public List<GroupPurchaseMyItemResponse> getMyList(String memberId, String status) {
        return groupPurchaseMapper.findMyGroupPurchases(memberId, status).stream()
                .map(this::toMyItemResponse)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public GroupPurchaseJoinResponse join(String memberId, String gpId, int quantity, GroupPurchaseJoinRequest request) {
        if (quantity <= 0) {
            throw new BusinessException("참여 수량은 1 이상이어야 합니다.");
        }

        Map<String, Object> gp = groupPurchaseMapper.findById(gpId);
        if (gp == null) {
            throw BusinessException.notFound("공동구매를 찾을 수 없습니다.");
        }
        if (groupPurchaseMapper.findParticipant(gpId, memberId) != null) {
            throw BusinessException.conflict("이미 참여한 공동구매입니다.");
        }
        // leave()/cancel()과 동일하게, 화면의 간편 비밀번호 사전 확인 결과를 신뢰하지 않고
        // 실제 지갑 차감(결제) 직전에 다시 검증한다.
        if (!simplePasswordVerificationService.verify(memberId, request.getPassword())) {
            throw new BusinessException("간편 비밀번호가 일치하지 않습니다.");
        }

        BigDecimal groupPrice = toDecimal(gp.get("group_price"));
        BigDecimal paidAmount = groupPrice == null ? null : groupPrice.multiply(BigDecimal.valueOf(quantity));

        String paymentStatus = GroupPurchaseParticipantStatus.PENDING;
        LocalDateTime paidAt = null;
        Long txnId = null;
        if (paidAmount != null) {
            txnId = chargeWallet(memberId, gpId, gp, paidAmount);
            paymentStatus = GroupPurchaseParticipantStatus.PAID;
            paidAt = LocalDateTime.now();
        }

        Map<String, Object> participant = new HashMap<>();
        participant.put("gpId", gpId);
        participant.put("memberId", memberId);
        participant.put("quantity", quantity);
        participant.put("recipientName", request.getRecipientName());
        participant.put("recipientPhone", request.getRecipientPhone());
        participant.put("zipCode", request.getZipCode());
        participant.put("address", request.getAddress());
        participant.put("addressDetail", request.getAddressDetail());
        participant.put("paidAmount", paidAmount);
        participant.put("paymentStatus", paymentStatus);
        participant.put("paidAt", paidAt);
        participant.put("txnId", txnId);
        try {
            groupPurchaseMapper.insertParticipant(participant);
        } catch (DuplicateKeyException e) {
            // findParticipant 조회 후 insert는 원자적이지 않아, 동시 요청이 같은 시점에 둘 다 미참여로 판단할 수 있다.
            // (gp_id, active_member_id) UNIQUE 제약(V18, CANCELLED 참여는 제외)이 최종 방어선이며,
            // 위반 시 지갑 차감·거래내역까지 트랜잭션 전체가 롤백된다.
            throw BusinessException.conflict("이미 참여한 공동구매입니다.");
        }
        int reserved = groupPurchaseMapper.updateQuantity(gpId, quantity);
        if (reserved == 0) {
            throw BusinessException.conflict("목표 수량을 초과했거나 마감된 공동구매입니다.");
        }

        Map<String, Object> savedParticipant = groupPurchaseMapper.findParticipant(gpId, memberId);
        Map<String, Object> updatedGp = groupPurchaseMapper.findById(gpId);

        // updateQuantity의 WHERE 절이 "목표 도달은 그 공동구매의 마지막 성공 join() 호출 단 한 번뿐"임을
        // 이미 보장하므로(도달 이후엔 어떤 join도 이 조건을 만족할 수 없다), 별도 가드 없이 여기서 한 번만
        // delivery_date를 confirmedDate + delivery_estimate_days로 갱신하면 정확히 한 번만 반영된다.
        Integer currentQuantity = toInt(updatedGp.get("current_quantity"));
        Integer targetQuantity = toInt(updatedGp.get("target_quantity"));
        Integer estimateDays = toInt(updatedGp.get("delivery_estimate_days"));
        if (currentQuantity != null && currentQuantity.equals(targetQuantity) && estimateDays != null) {
            groupPurchaseMapper.updateDeliveryDate(gpId, LocalDate.now().plusDays(estimateDays));
        }

        return GroupPurchaseJoinResponse.builder()
                .gpId(gpId)
                .participantId(toLong(savedParticipant.get("participant_id")))
                .quantity(toInt(savedParticipant.get("purchase_quantity")))
                .currentQuantity(currentQuantity)
                .targetQuantity(targetQuantity)
                .recipientName((String) savedParticipant.get("recipient_name"))
                .recipientPhone((String) savedParticipant.get("recipient_phone"))
                .zipCode((String) savedParticipant.get("zip_code"))
                .address((String) savedParticipant.get("address"))
                .addressDetail((String) savedParticipant.get("address_detail"))
                .paymentStatus((String) savedParticipant.get("payment_status"))
                .paidAmount(toDecimal(savedParticipant.get("paid_amount")))
                .paidAt(toLocalDateTime(savedParticipant.get("paid_at")))
                .joinedAt(toLocalDateTime(savedParticipant.get("created_at")))
                .build();
    }

    /**
     * 참여 취소(순서15). OPEN(진행중) 상태에서만 허용한다 — 목표 수량 달성(COMPLETED) 이후에는
     * 관리자 문의로만 취소 가능하고, 마감 후 미달(FAILED)/작성자 취소(CANCELLED)는 별도의 자동 환불
     * 처리(Notion 순서19, GroupPurchaseRefundExecutor) 대상이라 이 API의 범위가 아니다.
     * 취소 이력은 row를 삭제하지 않고 payment_status='CANCELLED'로 남긴다(V18 마이그레이션).
     * 동시 중복 취소 요청은 cancelParticipant의 영향받은 행 수로 감지해 거절한다.
     * 지갑 출금(WalletWithdrawalService.withdraw)과 동일하게, 화면의 간편 비밀번호 사전 확인 결과를
     * 신뢰하지 않고 실제 취소 처리 직전에 SimplePasswordVerificationService로 다시 검증한다.
     */
    @Override
    @Transactional
    public GroupPurchaseLeaveResponse leave(String memberId, String gpId, String password) {
        Map<String, Object> gp = groupPurchaseMapper.findById(gpId);
        if (gp == null) {
            throw BusinessException.notFound("공동구매를 찾을 수 없습니다.");
        }
        Map<String, Object> participant = groupPurchaseMapper.findParticipant(gpId, memberId);
        if (participant == null) {
            throw BusinessException.notFound("참여 내역을 찾을 수 없습니다.");
        }
        if (!simplePasswordVerificationService.verify(memberId, password)) {
            throw new BusinessException("간편 비밀번호가 일치하지 않습니다.");
        }

        LocalDateTime canceledAt = LocalDateTime.now();
        if (groupPurchaseMapper.cancelParticipant(gpId, memberId, canceledAt) == 0) {
            throw BusinessException.conflict("이미 취소된 참여입니다.");
        }

        int quantity = toInt(participant.get("purchase_quantity"));
        if (groupPurchaseMapper.decreaseQuantity(gpId, quantity) == 0) {
            throw BusinessException.conflict("목표 수량 달성 또는 마감 후에는 참여를 취소할 수 없습니다. 관리자에게 문의해주세요.");
        }

        BigDecimal refundedAmount = toDecimal(participant.get("paid_amount"));
        BigDecimal refundedWalletBalance = null;
        if (GroupPurchaseParticipantStatus.PAID.equals(participant.get("payment_status")) && refundedAmount != null) {
            refundedWalletBalance = refundWallet(memberId, gpId, gp, refundedAmount,
                    "공동구매 참여 취소 환불: " + gp.get("product_name") + " (gpId=" + gpId + ")");
        }

        Map<String, Object> updatedGp = groupPurchaseMapper.findById(gpId);

        return GroupPurchaseLeaveResponse.builder()
                .gpId(gpId)
                .participantId(toLong(participant.get("participant_id")))
                .currentQuantity(toInt(updatedGp.get("current_quantity")))
                .targetQuantity(toInt(updatedGp.get("target_quantity")))
                .refundedAmount(refundedAmount)
                .refundedWalletBalance(refundedWalletBalance)
                .paymentStatus(GroupPurchaseParticipantStatus.CANCELLED)
                .canceledAt(canceledAt)
                .build();
    }

    /**
     * 작성자(관리자) 전체 취소(순서18). leave()는 참여자 개인 1명의 참여만 취소하지만, 이 API는
     * 게시글 전체를 취소하면서 모든 참여자(PENDING 포함)의 참여를 CANCELLED로 남기고, 그중 이미
     * 결제한 참여자만 함께 환불한다 — leave()가 PAID/PENDING을 가리지 않고 항상 cancelParticipant를
     * 호출한 뒤 PAID일 때만 환불하는 것과 동일한 원칙이다. PENDING 참여를 그대로 두면 게시글은
     * CANCELLED인데 참여 이력만 살아남아 마이페이지 등에서 계속 "참여 중"으로 보이게 된다.
     * 마감 후 목표 미달 자동 환불(GroupPurchaseRefundExecutor)은 서로 다른 게시글의 후보를 순회하므로
     * 건별 독립 트랜잭션이 맞지만, 이 메서드는 같은 게시글에 속한 참여자들을 관리자가 한 번에 취소하는
     * 단일 액션이라 전체를 하나의 트랜잭션으로 묶는다 — 일부만 환불된 상태로 남는 것을 막기 위해서다.
     * cancelGroupPurchase의 원자적 WHERE절이 join()의 updateQuantity와 동일한 패턴으로 목표 수량 달성
     * (COMPLETED) 또는 마감(FAILED) 이후에는 취소를 거절한다.
     * leave()와 동일하게, 호출한 관리자 본인의 간편 비밀번호를 처리 직전에 다시 검증한다.
     * findActiveParticipants는 FOR UPDATE로 조회한다(GroupPurchaseMapper.xml 참고) — 일반 SELECT였다면
     * REPEATABLE READ 스냅샷이 findById 시점에 고정돼, cancelGroupPurchase 실행 후 그 사이 동시에 커밋된
     * join() 참여가 이 목록에서 누락되어 결제는 됐는데 환불은 안 되는 경우가 생길 수 있었다.
     */
    @Override
    @Transactional
    public GroupPurchaseCancelResponse cancel(String memberId, String gpId, String password) {
        Map<String, Object> gp = groupPurchaseMapper.findById(gpId);
        if (gp == null) {
            throw BusinessException.notFound("공동구매를 찾을 수 없습니다.");
        }
        if (!simplePasswordVerificationService.verify(memberId, password)) {
            throw new BusinessException("간편 비밀번호가 일치하지 않습니다.");
        }
        if (groupPurchaseMapper.cancelGroupPurchase(gpId) == 0) {
            throw BusinessException.conflict("목표 수량 달성 또는 마감 후에는 취소할 수 없습니다.");
        }

        LocalDateTime canceledAt = LocalDateTime.now();
        List<GroupPurchaseCancelParticipantResponse> refunded = groupPurchaseMapper.findActiveParticipants(gpId).stream()
                .map(participant -> cancelAndMaybeRefundParticipant(gpId, gp, participant, canceledAt))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        return GroupPurchaseCancelResponse.builder()
                .gpId(gpId)
                .status(GroupPurchaseStatus.CANCELLED)
                .canceledAt(canceledAt)
                .refundedParticipants(refunded)
                .build();
    }

    /**
     * 참여자 1명을 취소하고, 결제 완료(PAID) 상태였다면 환불까지 처리한다. cancelParticipant의 영향
     * 행이 0이면(동시에 leave()로 이미 처리된 참여자) null을 반환해 결과 목록에서 자연히 제외한다 —
     * leave()/자동환불 배치와 동일한 가드. PENDING 참여는 취소만 하고(환불할 금액이 없으므로) 응답의
     * refundedParticipants 목록에는 포함하지 않는다 — 이 필드는 이름 그대로 "환불된" 참여자만 담는다.
     *
     * 환불 여부는 findActiveParticipants가 읽어온 스냅샷의 payment_status로 판단한다(재조회하지 않음).
     * join()이 payment_status를 insertParticipant 시점에 단 한 번 확정하고, 그 후로는 cancelParticipant를
     * 통한 CANCELLED 전환 외에는 이 값을 바꾸는 코드 경로가 없으므로(PENDING→PAID로 전환하는 "나중에
     * 결제" 흐름이 아직 없음) 스냅샷과 실제 값이 어긋날 수 없다. 이후 그런 흐름이 추가된다면, 스냅샷의
     * PENDING이 이 시점엔 이미 PAID로 바뀌었는데 환불 없이 취소만 되는 레이스가 생길 수 있으므로 이
     * 메서드도 함께 재검토해야 한다.
     */
    private GroupPurchaseCancelParticipantResponse cancelAndMaybeRefundParticipant(String gpId, Map<String, Object> gp,
            Map<String, Object> participant, LocalDateTime canceledAt) {
        String participantMemberId = String.valueOf(participant.get("member_id"));
        if (groupPurchaseMapper.cancelParticipant(gpId, participantMemberId, canceledAt) == 0) {
            return null;
        }
        if (!GroupPurchaseParticipantStatus.PAID.equals(participant.get("payment_status"))) {
            return null;
        }
        BigDecimal paidAmount = toDecimal(participant.get("paid_amount"));
        BigDecimal refundedWalletBalance = refundWallet(participantMemberId, gpId, gp, paidAmount,
                "공동구매 작성자 취소로 인한 환불: " + gp.get("product_name") + " (gpId=" + gpId + ")");
        return GroupPurchaseCancelParticipantResponse.builder()
                .participantId(toLong(participant.get("participant_id")))
                .memberId(participantMemberId)
                .refundedAmount(paidAmount)
                .refundedWalletBalance(refundedWalletBalance)
                .paymentStatus(GroupPurchaseParticipantStatus.CANCELLED)
                .build();
    }

    /** 지갑 잔액을 환급하고 REFUND 타입 환불 거래내역을 생성한 뒤 갱신된 지갑 잔액을 반환한다. memo는 환불 트리거(참여 취소/작성자 취소)별로 구분해서 넘긴다. */
    private BigDecimal refundWallet(String memberId, String gpId, Map<String, Object> gp, BigDecimal amount, String memo) {
        Map<String, Object> wallet = walletMapper.findByMemberId(memberId);
        if (wallet == null) {
            throw BusinessException.notFound("지갑을 찾을 수 없습니다.");
        }
        String walletId = String.valueOf(wallet.get("wallet_id"));
        if (walletMapper.addBalance(walletId, amount) == 0) {
            throw BusinessException.notFound("지갑을 찾을 수 없습니다.");
        }

        Map<String, Object> txn = new HashMap<>();
        txn.put("walletId", walletId);
        txn.put("petId", null);
        txn.put("txnType", "REFUND");
        txn.put("price", amount);
        txn.put("category", toTxnCategory((String) gp.get("category")));
        txn.put("merchantName", gp.get("product_name"));
        txn.put("merchantCategoryCode", null);
        txn.put("memo", memo);
        txn.put("autoTagged", "N");
        txn.put("txnDate", LocalDateTime.now());
        transactionMapper.insert(txn);

        Map<String, Object> updatedWallet = walletMapper.findByMemberId(memberId);
        return (BigDecimal) updatedWallet.get("balance");
    }

    /** 지갑 잔액을 차감하고 거래내역을 생성한 뒤 생성된 txn_id를 반환한다. TransactionServiceImpl#processPayment와 동일한 차감·기록 패턴을 따른다. */
    private Long chargeWallet(String memberId, String gpId, Map<String, Object> gp, BigDecimal amount) {
        Map<String, Object> wallet = walletMapper.findByMemberId(memberId);
        if (wallet == null) {
            throw BusinessException.notFound("지갑을 찾을 수 없습니다.");
        }
        String walletId = String.valueOf(wallet.get("wallet_id"));
        BigDecimal balance = (BigDecimal) wallet.get("balance");
        if (balance.compareTo(amount) < 0) {
            throw new BusinessException("잔액이 부족합니다.");
        }
        // balance 조회 후 절대값을 저장하면 동시 결제에서 갱신이 유실될 수 있어,
        // balance - amount와 balance >= amount 조건을 하나의 원자적 UPDATE로 수행한다.
        if (walletMapper.deductBalance(walletId, amount) == 0) {
            throw new BusinessException("잔액이 부족합니다.");
        }

        Map<String, Object> txn = new HashMap<>();
        txn.put("walletId", walletId);
        txn.put("petId", null);
        txn.put("txnType", "PAYMENT");
        txn.put("price", amount);
        txn.put("category", toTxnCategory((String) gp.get("category")));
        txn.put("merchantName", gp.get("product_name"));
        txn.put("merchantCategoryCode", null);
        txn.put("memo", "공동구매 참여: " + gp.get("product_name") + " (gpId=" + gpId + ")");
        txn.put("autoTagged", "N");
        txn.put("txnDate", LocalDateTime.now());
        transactionMapper.insert(txn);
        return toLong(txn.get("txnId"));
    }

    private static String toTxnCategory(String groupPurchaseCategory) {
        if (GroupPurchaseCategory.FOOD.equals(groupPurchaseCategory)
                || GroupPurchaseCategory.SNACK.equals(groupPurchaseCategory)) {
            return "FOOD";
        }
        return "ETC";
    }

    @Override
    public GroupPurchaseImageUploadResponse uploadImage(MultipartFile image) {
        if (image == null || image.isEmpty()) {
            throw new BusinessException("업로드할 이미지가 없습니다.");
        }

        String originalFilename = image.getOriginalFilename();
        String extension = originalFilename != null && originalFilename.contains(".")
                ? originalFilename.substring(originalFilename.lastIndexOf(".") + 1).toLowerCase()
                : "";
        if (!ALLOWED_IMAGE_EXTENSIONS.contains(extension)) {
            throw new BusinessException("이미지 파일(jpg, jpeg, png, webp)만 업로드할 수 있습니다.");
        }

        try {
            // 저장 키를 그대로 돌려준다. 클라이언트는 이 값을 화면에 쓰지 않고 등록 요청의
            // image 필드로 되돌려 보낸다. 화면에 보일 주소는 조회 시점에 signedUrl로 만든다.
            String key = fileStorage.store(image.getBytes(), "group-purchase", extension);
            return GroupPurchaseImageUploadResponse.builder()
                    .imageUrl(key)
                    .build();
        } catch (IOException e) {
            throw new BusinessException("이미지 업로드에 실패했습니다.");
        }
    }

    private GroupPurchaseResponse toResponse(Map<String, Object> gp, boolean isParticipating) {
        LocalDateTime deadline = toLocalDateTime(gp.get("deadline"));
        Integer currentQuantity = toInt(gp.get("current_quantity"));
        Integer targetQuantity = toInt(gp.get("target_quantity"));
        boolean cancelled = GroupPurchaseStatus.CANCELLED.equals(gp.get("status"));
        return GroupPurchaseResponse.builder()
                .gpId(String.valueOf(gp.get("gp_id")))
                .memberId(String.valueOf(gp.get("member_id")))
                .productName((String) gp.get("product_name"))
                .category((String) gp.get("category"))
                .image(fileStorage.signedUrl((String) gp.get("image")))
                .unitPrice(toDecimal(gp.get("unit_price")))
                .groupPrice(toDecimal(gp.get("group_price")))
                .deliveryMethod((String) gp.get("delivery_method"))
                .deliveryFee(toDecimal(gp.get("delivery_fee")))
                .deliveryDate(toLocalDate(gp.get("delivery_date")))
                .deliveryEstimateDays(toInt(gp.get("delivery_estimate_days")))
                .description((String) gp.get("description"))
                .targetQuantity(targetQuantity)
                .currentQuantity(currentQuantity)
                .status(computeStatus(cancelled, deadline, currentQuantity, targetQuantity))
                .deadline(deadline)
                .createdAt(toLocalDateTime(gp.get("created_at")))
                .isParticipating(isParticipating)
                .build();
    }

    /**
     * 목록 한 페이지의 참여 여부를 한 번에 조회한다. 비로그인이거나 빈 페이지면 쿼리하지 않는다.
     */
    private Set<String> findParticipatingGpIds(String memberId, List<Map<String, Object>> pageRows) {
        if (memberId == null || pageRows.isEmpty()) {
            return Collections.emptySet();
        }
        List<String> gpIds = pageRows.stream()
                .map(gp -> String.valueOf(gp.get("gp_id")))
                .collect(Collectors.toList());
        List<Long> participating = groupPurchaseMapper.findParticipatingGpIds(memberId, gpIds);
        if (participating == null || participating.isEmpty()) {
            return Collections.emptySet();
        }
        return participating.stream()
                .map(String::valueOf)
                .collect(Collectors.toSet());
    }

    private GroupPurchaseListItemResponse toListItemResponse(Map<String, Object> gp, boolean isParticipating) {
        LocalDateTime deadline = toLocalDateTime(gp.get("deadline"));
        Integer currentQuantity = toInt(gp.get("current_quantity"));
        Integer targetQuantity = toInt(gp.get("target_quantity"));
        BigDecimal unitPrice = toDecimal(gp.get("unit_price"));
        BigDecimal groupPrice = toDecimal(gp.get("group_price"));
        // findList SQL이 status != 'CANCELLED'를 무조건 적용하므로 이 목록에는 취소된 게시글이 없다.
        boolean cancelled = GroupPurchaseStatus.CANCELLED.equals(gp.get("status"));
        return GroupPurchaseListItemResponse.builder()
                .id(toLong(gp.get("gp_id")))
                .memberId(toLong(gp.get("member_id")))
                .productName((String) gp.get("product_name"))
                .category((String) gp.get("category"))
                .status(computeStatus(cancelled, deadline, currentQuantity, targetQuantity))
                .unitPrice(unitPrice)
                .groupPrice(groupPrice)
                .currentQuantity(currentQuantity)
                .targetQuantity(targetQuantity)
                .dDay(toDDay(deadline))
                .badgeText(toBadgeText(unitPrice, groupPrice))
                .isParticipating(isParticipating)
                .createdAt(toLocalDateTime(gp.get("created_at")))
                .build();
    }

    /**
     * 공동구매 조회 API 4개(list/getDetail/getStatus/getMyList) 공통 상태 계산.
     * 저장된 status 컬럼을 그대로 내려주지 않고(COMPLETED/FAILED는 컬럼에 저장되지 않는 계산값이라
     * 그대로 내려주면 항상 OPEN으로만 보임) 매번 여기서 다시 계산해야 한다.
     * 판정 순서: [관리자 취소 여부] → [목표 수량 도달 여부] → [마감일 경과 여부].
     * 목표 수량 달성은 마감 전이라도 즉시 COMPLETED로 확정하고(초과 참여는 updateQuantity에서 이미
     * 막혀 있음), 마감 후 미달성은 FAILED(환불 대상), 관리자 취소는 CANCELLED로 각각 구분한다.
     */
    private static String computeStatus(boolean cancelled, LocalDateTime deadline, Integer currentQuantity, Integer targetQuantity) {
        if (cancelled) {
            return GroupPurchaseStatus.CANCELLED;
        }
        int current = currentQuantity == null ? 0 : currentQuantity;
        int target = targetQuantity == null ? 0 : targetQuantity;
        if (isTargetReached(current, target)) {
            return GroupPurchaseStatus.COMPLETED;
        }
        if (deadline != null && deadline.isBefore(LocalDateTime.now())) {
            return GroupPurchaseStatus.FAILED;
        }
        return GroupPurchaseStatus.OPEN;
    }

    /** 각 status 값에 맞는 안내 문구를 반환한다(상태 화면 전용). */
    private static String toNoticeMessage(String status) {
        return switch (status) {
            case GroupPurchaseStatus.COMPLETED -> "목표 인원이 모두 모여 공동구매가 확정되었습니다.";
            case GroupPurchaseStatus.FAILED -> "목표 인원 미달로 공동구매가 취소되어 환불됩니다.";
            case GroupPurchaseStatus.CANCELLED -> "작성자가 취소한 공동구매입니다.";
            default -> "목표 인원이 모두 모이면 공동구매가 최종 확정됩니다.";
        };
    }

    private GroupPurchaseMyItemResponse toMyItemResponse(Map<String, Object> gp) {
        LocalDateTime deadline = toLocalDateTime(gp.get("deadline"));
        Integer currentQuantity = toInt(gp.get("current_quantity"));
        Integer targetQuantity = toInt(gp.get("target_quantity"));
        boolean cancelled = GroupPurchaseStatus.CANCELLED.equals(gp.get("status"));
        return GroupPurchaseMyItemResponse.builder()
                .gpId(toLong(gp.get("gp_id")))
                .memberId(toLong(gp.get("member_id")))
                .productName((String) gp.get("product_name"))
                .status(computeStatus(cancelled, deadline, currentQuantity, targetQuantity))
                .currentQuantity(currentQuantity)
                .targetQuantity(targetQuantity)
                .deadline(deadline)
                .createdAt(toLocalDateTime(gp.get("created_at")))
                .build();
    }

    /**
     * target이 0 이하(마이그레이션 누락·초기 등록 오류 등 비정상 데이터)이면 current(기본값 0)와의
     * 비교만으로 "목표 달성"으로 오판하지 않도록 방어한다. 정상 생성 경로는
     * {@link com.aewol.domain.grouppurchase.dto.GroupPurchaseCreateRequest}의 @Min(1) 검증과
     * target_quantity 컬럼의 NOT NULL DEFAULT 1 제약으로 이 값이 1 이상임을 보장한다.
     */
    private static boolean isTargetReached(int current, int target) {
        return target > 0 && current >= target;
    }

    private static String toDDay(LocalDateTime deadline) {
        if (deadline == null) return null;
        long days = ChronoUnit.DAYS.between(LocalDate.now(), deadline.toLocalDate());
        if (days > 0) return "D-" + days;
        if (days == 0) return "D-DAY";
        return "마감";
    }

    private static String toBadgeText(BigDecimal unitPrice, BigDecimal groupPrice) {
        if (unitPrice == null || groupPrice == null
                || unitPrice.signum() <= 0 || groupPrice.compareTo(unitPrice) >= 0) {
            return null;
        }
        int rate = unitPrice.subtract(groupPrice)
                .multiply(BigDecimal.valueOf(100))
                .divide(unitPrice, 0, RoundingMode.HALF_UP)
                .intValue();
        return rate + "% 할인";
    }

    private static Long toLong(Object value) {
        if (value == null) return null;
        return value instanceof Number ? ((Number) value).longValue() : Long.parseLong(String.valueOf(value));
    }

    private static BigDecimal toDecimal(Object value) {
        if (value == null) return null;
        return value instanceof BigDecimal ? (BigDecimal) value : new BigDecimal(String.valueOf(value));
    }

    private static Integer toInt(Object value) {
        if (value == null) return null;
        return value instanceof Number ? ((Number) value).intValue() : Integer.parseInt(String.valueOf(value));
    }

    private static LocalDate toLocalDate(Object value) {
        if (value == null) return null;
        if (value instanceof LocalDate) return (LocalDate) value;
        if (value instanceof java.sql.Date) return ((java.sql.Date) value).toLocalDate();
        return LocalDate.parse(String.valueOf(value));
    }

    private static LocalDateTime toLocalDateTime(Object value) {
        if (value == null) return null;
        if (value instanceof LocalDateTime) return (LocalDateTime) value;
        if (value instanceof Timestamp) return ((Timestamp) value).toLocalDateTime();
        return LocalDateTime.parse(String.valueOf(value));
    }
}
