package com.aewol.domain.wallet.service;

import com.aewol.common.exception.BusinessException;
import com.aewol.domain.transaction.mapper.TransactionMapper;
import com.aewol.domain.wallet.dto.ExternalChargeCommand;
import com.aewol.domain.wallet.dto.TossChargeOrderRequest;
import com.aewol.domain.wallet.dto.TossChargeOrderResponse;
import com.aewol.domain.wallet.dto.TossChargeRequest;
import com.aewol.domain.wallet.dto.WalletResponse;
import com.aewol.domain.wallet.mapper.TossChargeOrderMapper;
import com.aewol.domain.wallet.mapper.WalletMapper;
import com.aewol.external.tosspayments.TossCancelResult;
import com.aewol.external.tosspayments.TossConfirmResult;
import com.aewol.external.tosspayments.TossPaymentAuditLogger;
import com.aewol.external.tosspayments.TossPaymentClaim;
import com.aewol.external.tosspayments.TossPaymentsClient;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * TossPayments 승인을 지갑 충전으로 연결하는 비트랜잭셔널 오케스트레이터.
 *
 * <p><b>자금 흐름</b>: 사용자는 카드로 <i>한 번만</i> 결제하고, 그 금액이 애월 MAIN 지갑 잔액으로
 * 들어온다. 실제 상품 결제는 이 잔액에서 차감된다({@code POST /api/transactions/payment}).
 * PG는 충전 시점에만 개입한다 — 결제 시점에도 PG를 태우면 카드와 지갑에서 이중으로 돈이 빠진다.
 *
 * <p><b>절대 {@code @Transactional}을 붙이지 않는다.</b> {@code DataSourceConfig.java:48}은
 * 커넥션 풀을 10개로 제한하고, 여기서 쓰는 {@code DataSourceTransactionManager}는 트랜잭션
 * 시작 시점에 커넥션을 즉시 바인딩한다. Toss confirm 호출은 최대 20초까지 걸릴 수 있는데,
 * 이 호출이 트랜잭션 안에서 실행되면 그 20초 내내 풀에서 커넥션 하나를 붙잡고 있게 되고,
 * 동시에 10건만 몰려도 로그인·지갑조회 등 무관한 모든 요청이 커넥션 획득 타임아웃(30초)까지
 * 함께 멈춘다. 원장 기록만 {@link WalletService#depositExternal}의 독립된 짧은 트랜잭션으로
 * 처리하고, Toss HTTP 호출은 항상 트랜잭션 밖에서 실행한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TossChargeService {

    private final TossPaymentClaim tossPaymentClaim;
    private final TossPaymentsClient tossPaymentsClient;
    private final TossPaymentAuditLogger auditLogger;
    private final WalletService walletService;
    private final WalletMapper walletMapper;
    private final TossChargeOrderMapper tossChargeOrderMapper;
    private final TransactionMapper transactionMapper;

    /**
     * Toss 결제창을 열기 전에 서버에서 충전 주문을 생성한다.
     *
     * <p>서버가 orderId를 직접 발급하고 회원·금액을 {@code toss_charge_order}에 기록해두면,
     * 이후 {@link #charge} 승인 단계에서 해당 orderId가 서버에서 발급된 것인지, 요청 회원의
     * 주문인지, 금액이 클라이언트에 의해 변조되지 않았는지 검증할 수 있다.
     *
     * <p>클라이언트는 응답의 {@code orderId}로 Toss SDK를 초기화하고, 결제 완료 후
     * {@code POST /api/wallet/toss-charge}에 {@code paymentKey}와 함께 전달한다.
     */
    public TossChargeOrderResponse prepare(String memberId, TossChargeOrderRequest request) {
        Map<String, Object> wallet = walletMapper.findByMemberId(memberId);
        if (wallet == null) {
            throw BusinessException.notFound("지갑을 찾을 수 없습니다.");
        }

        String orderId = UUID.randomUUID().toString();
        Map<String, Object> order = new HashMap<>();
        order.put("orderId", orderId);
        order.put("memberId", memberId);
        order.put("amount", request.getAmount());
        order.put("status", "PENDING");
        tossChargeOrderMapper.insert(order);

        return new TossChargeOrderResponse(orderId, request.getAmount());
    }

    public WalletResponse charge(String memberId, TossChargeRequest request) {
        String paymentKey = request.getPaymentKey();
        String orderId = request.getOrderId();

        // 1. 클레임 획득 — 경합 시에는 실제 원장을 확인한다. 이전 요청이 승인·적립까지
        // 끝났지만 브라우저가 응답을 받지 못한 경우라면, 주문 상태 갱신 성공 여부와 무관하게
        // Toss를 다시 호출하거나 잔액을 다시 적립하지 않고 현재 지갑을 멱등 응답한다.
        // 일치하는 원장이 없으면 실제 처리 중인 요청일 수 있으므로 기존 409를 그대로 반환한다.
        try {
            tossPaymentClaim.acquire(orderId);
        } catch (BusinessException e) {
            if (e.getStatus() == HttpStatus.CONFLICT
                    && hasMatchingCompletedCharge(memberId, request)) {
                markOrderApprovedBestEffort(orderId);
                return walletService.getWallet(memberId);
            }
            throw e;
        }

        // 2. 사전 점검 — Toss 호출 이전에 실패하는 경우 클레임을 해제한다.
        //
        // 클레임 해제 규칙은 개별 실패를 열거하는 대신 제어 흐름으로 보장한다:
        // 이 try 블록 안에서 던져지는 모든 예외는 confirmPayment 호출 이전에 발생한 것이므로
        // — 소유권 검증 실패, 지갑 조회 중 DB 장애 같은 예상 못 한 인프라 예외까지 포함해 —
        // 반드시 클레임을 해제한다. Toss를 호출조차 하지 않았으니 이중충전 위험이 없고,
        // 해제하지 않으면 해당 orderId가 아무 이유 없이 TTL(10분) 동안 잠긴다.
        long confirmAmount;
        try {
            // 2a. 주문 소유권 검증 — orderId가 이 서버에서 발급됐는지, 요청 회원의 주문인지,
            // 이미 처리된 주문은 아닌지, 클라이언트가 금액을 변조하지 않았는지 확인한다.
            // findByOrderId 결과는 resultType="map"이므로 키가 DB 컬럼명 그대로(snake_case)다.
            Map<String, Object> order = tossChargeOrderMapper.findByOrderId(orderId);
            if (order == null) {
                throw BusinessException.notFound("존재하지 않는 주문입니다.");
            }
            // memberId는 인증 Principal의 String이고, BIGINT 컬럼은 MyBatis Map에서 Long으로
            // 반환되므로 문자열로 정규화한 뒤 비교한다.
            if (!memberId.equals(String.valueOf(order.get("member_id")))) {
                throw new BusinessException(HttpStatus.FORBIDDEN, "본인의 주문만 처리할 수 있습니다.");
            }
            BigDecimal orderedAmount = (BigDecimal) order.get("amount");
            if (orderedAmount.compareTo(request.getAmount()) != 0) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "주문 금액이 일치하지 않습니다.");
            }
            Map<String, Object> completedCharge = transactionMapper.findTossPaymentByOrderId(orderId);
            if (completedCharge != null && !completedCharge.isEmpty()) {
                if (!matchesCompletedCharge(completedCharge, memberId, request)) {
                    throw new BusinessException(HttpStatus.CONFLICT,
                            "주문과 일치하지 않는 충전 기록이 존재합니다.");
                }
                // 상태 갱신 실패로 PENDING이 남았더라도 실제 원장이 일치하면 완료된 요청이다.
                markOrderApprovedBestEffort(orderId);
                tossPaymentClaim.release(orderId);
                return walletService.getWallet(memberId);
            }
            if ("APPROVED".equals(order.get("status"))) {
                // 상태만 APPROVED이고 실제 원장이 없다면 성공으로 위장하지 않는다.
                throw new BusinessException(HttpStatus.CONFLICT,
                        "충전 완료 기록을 확인할 수 없습니다.");
            }
            if (!"PENDING".equals(order.get("status"))) {
                throw new BusinessException(HttpStatus.CONFLICT, "이미 처리된 주문입니다.");
            }

            // 2b. 지갑 존재 확인 — 충전은 잔액 요건이 없으므로 존재만 확인한다.
            // 지갑이 없는데 카드부터 긁으면 승인된 돈을 넣을 곳이 없어져 곧바로 보상 경로를
            // 타게 되므로, Toss 호출 전에 걸러낸다.
            Map<String, Object> wallet = walletMapper.findByMemberId(memberId);
            if (wallet == null) {
                throw BusinessException.notFound("지갑을 찾을 수 없습니다.");
            }

            // 금액 변환도 이 블록 안에 둔다. @Digits(fraction=0)가 이미 정수를 보장하지만,
            // 검증을 우회하는 호출 경로가 생겨 ArithmeticException이 나더라도 Toss를 호출하기
            // 전이므로 클레임이 해제되는 것이 맞다 — 의도된 동작이다.
            confirmAmount = request.getAmount().setScale(0, RoundingMode.UNNECESSARY).longValueExact();
        } catch (RuntimeException e) {
            tossPaymentClaim.release(orderId);
            throw e;
        }

        // 3. Toss 승인 — 트랜잭션 밖에서 호출한다. 이 시점 이후로는 확정적 거부와 설정 오류를
        // 제외한 어떤 경로에서도 클레임을 해제하지 않는다.
        TossConfirmResult result = tossPaymentsClient.confirmPayment(paymentKey, orderId, confirmAmount);

        switch (result.getOutcome()) {
            case DEFINITIVE_REJECTION:
                // Toss가 승인을 보유하지 않음이 확정됨 — 클레임 해제, 부작용 없음, 감사 로그 불필요.
                tossPaymentClaim.release(orderId);
                throw new BusinessException(HttpStatus.PAYMENT_REQUIRED, "결제가 거절되었습니다.");
            case CONFIG_ERROR:
                // Toss가 요청을 인증조차 하지 않았으므로 승인을 보유할 수 없다 — 해제 가능.
                // 카드 거절(402)로 노출하면 시크릿 키 미설정 같은 우리 쪽 설정 오류가 데모
                // 중 "카드가 거절되었습니다"로 오인될 수 있어 500으로 명확히 구분한다.
                tossPaymentClaim.release(orderId);
                log.error("TossPayments 설정 오류 - orderId: {}, code: {}, message: {}",
                        orderId, result.getCode(), result.getMessage());
                throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "충전 처리 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.");
            case ALREADY_APPROVED:
                // Toss가 이전 confirm으로 이미 승인을 보유하고 있을 가능성이 있다 —
                // 클레임 유지, 감사 로그 남김.
                auditLogger.alreadyApproved(paymentKey, orderId, memberId, result.getMessage());
                throw new BusinessException(HttpStatus.CONFLICT, "이미 처리된 충전입니다.");
            case INDETERMINATE:
                // 승인 여부를 확정할 수 없음 — 클레임을 풀어 재시도를 유도해서는 안 된다.
                // 타임아웃 직전에 Toss가 승인을 완료했을 수 있으므로 새 주문을 유도하면
                // 카드가 이중 청구될 수 있다. 결제 내역을 먼저 확인하도록 안내한다.
                auditLogger.confirmIndeterminate(paymentKey, orderId, memberId, result.getMessage());
                throw new BusinessException(HttpStatus.BAD_GATEWAY,
                        "충전 상태를 확인할 수 없습니다. 잠시 후 결제 내역을 확인하신 후 충전이 완료되지 않은 경우에만 다시 시도해 주세요.");
            case SUCCESS:
            default:
                break;
        }

        // 4. 원장 기록 — 적립 금액은 요청 DTO의 amount가 아니라 Toss confirm이 확정한
        // totalAmount를 쓴다. 클라이언트가 보낸 금액을 그대로 신뢰하지 않는다.
        ExternalChargeCommand command = ExternalChargeCommand.builder()
                .memberId(memberId)
                .amount(BigDecimal.valueOf(result.getTotalAmount()))
                .paymentKey(paymentKey)
                .orderId(orderId)
                .build();

        try {
            WalletResponse response = walletService.depositExternal(command);

            // 5. 주문 상태 업데이트 — 성공 클레임은 해제하지 않고 TTL(10분) 만료까지 유지한다.
            // updateStatus 실패는 이미 완료된 충전을 취소하지 않는다. payment_key UNIQUE 제약이
            // 재충전을 막으므로 APPROVED 마킹은 감사 목적이며, 실패 시 경고 로그만 남긴다.
            markOrderApprovedBestEffort(orderId);
            return response;
        } catch (DuplicateKeyException e) {
            // 해당 paymentKey 또는 orderId가 이미 원장에 기록되어 있음 — 대사가 이미 완료된 상태라면
            // 감사 마커는 필요 없다. 반드시 이 좁은 타입만 잡는다: 부모 타입인
            // DataIntegrityViolationException을 잡으면 외래키 위반(MySQL 1452, 보상 경로를
            // 검증하는 시나리오)까지 함께 삼켜져 아래 보상 분기가 실행되지 않게 된다.
            if (hasMatchingCompletedCharge(memberId, request)) {
                markOrderApprovedBestEffort(orderId);
                return walletService.getWallet(memberId);
            }
            throw new BusinessException(HttpStatus.CONFLICT, "이미 처리된 충전입니다.");
        } catch (Exception e) {
            // 그 외 모든 실패 — Toss는 이미 승인했으므로 cancel 보상을 시도한다. 원래 실패(e)를
            // 보상 호출의 성공/실패로 감추지 않고, 어느 쪽이든 500으로 응답한다.
            compensate(paymentKey, orderId, memberId, e);
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "충전 처리 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.");
        }
    }

    /**
     * Toss로 나가는 취소 사유. 예외 메시지를 그대로 싣지 않는다 — 위 catch(Exception)은
     * 의도적으로 넓어서 예상 못 한 RuntimeException(NPE, 드라이버 예외 등)도 잡히는데,
     * 그 메시지에는 SQL 조각이나 내부 식별자가 담길 수 있어 외부 API 필드로 나가면 안 된다.
     * 길이 제한에 걸려 보상 호출 자체가 실패할 위험도 있다. 원인 메시지는 외부에 노출되지
     * 않는 감사 로그에만 남긴다.
     */
    private static final String CANCEL_REASON = "지갑 충전 기록 실패로 인한 자동 취소";

    /** 실제 원장에 기록된 회원·주문·결제키·금액이 모두 같을 때만 완료 재요청으로 인정한다. */
    private boolean hasMatchingCompletedCharge(String memberId, TossChargeRequest request) {
        Map<String, Object> completedCharge =
                transactionMapper.findTossPaymentByOrderId(request.getOrderId());
        return completedCharge != null
                && !completedCharge.isEmpty()
                && matchesCompletedCharge(completedCharge, memberId, request);
    }

    private boolean matchesCompletedCharge(Map<String, Object> completedCharge,
                                            String memberId,
                                            TossChargeRequest request) {
        Object amount = completedCharge.get("price");
        return memberId.equals(String.valueOf(completedCharge.get("member_id")))
                && request.getOrderId().equals(completedCharge.get("order_id"))
                && request.getPaymentKey().equals(completedCharge.get("payment_key"))
                && amount instanceof BigDecimal
                && ((BigDecimal) amount).compareTo(request.getAmount()) == 0;
    }

    private void markOrderApprovedBestEffort(String orderId) {
        try {
            tossChargeOrderMapper.updateStatus(orderId, "APPROVED");
        } catch (Exception updateEx) {
            // 원장이 진실의 원천이므로 상태 갱신 실패가 완료 재시도를 막아서는 안 된다.
            log.warn("충전 주문 상태 업데이트 실패 - orderId: {}", orderId, updateEx);
        }
    }

    private void compensate(String paymentKey, String orderId, String memberId, Exception cause) {
        TossCancelResult cancelResult = tossPaymentsClient.cancelPayment(paymentKey, CANCEL_REASON);
        if (cancelResult.isSuccess()) {
            auditLogger.compensated(paymentKey, orderId, memberId, cause.getMessage());
        } else {
            // cancel마저 실패 — 자동 복구 불가. 이 로그의 orderId로 원장을 역조회해
            // 수동 대사해야 한다.
            auditLogger.compensationFailed(paymentKey, orderId, memberId,
                    cause.getMessage() + " / cancel 실패: code=" + cancelResult.getCode()
                            + " message=" + cancelResult.getMessage());
        }
    }
}
