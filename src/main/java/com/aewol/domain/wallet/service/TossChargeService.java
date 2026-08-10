package com.aewol.domain.wallet.service;

import com.aewol.common.exception.BusinessException;
import com.aewol.domain.wallet.dto.ExternalChargeCommand;
import com.aewol.domain.wallet.dto.TossChargeRequest;
import com.aewol.domain.wallet.dto.WalletResponse;
import com.aewol.domain.wallet.mapper.WalletMapper;
import com.aewol.external.tosspayments.TossCancelResult;
import com.aewol.external.tosspayments.TossConfirmResult;
import com.aewol.external.tosspayments.TossPaymentAuditLogger;
import com.aewol.external.tosspayments.TossPaymentClaim;
import com.aewol.external.tosspayments.TossPaymentsClient;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
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

    public WalletResponse charge(String memberId, TossChargeRequest request) {
        String paymentKey = request.getPaymentKey();
        String orderId = request.getOrderId();

        // 1. 클레임 획득 — 경합 시 409, Redis 장애 시 503(fail-closed).
        // 새로고침/뒤로가기로 같은 orderId가 두 번 들어와도 잔액이 두 번 늘지 않게 한다.
        tossPaymentClaim.acquire(memberId, orderId);

        // 2. 사전 점검 — 충전은 잔액 요건이 없으므로 지갑 존재만 확인한다. 지갑이 없는데
        // 카드부터 긁으면 승인된 돈을 넣을 곳이 없어져 곧바로 보상 경로를 타게 되므로,
        // Toss 호출 전에 걸러낸다.
        //
        // 클레임 해제 규칙은 개별 실패를 열거하는 대신 제어 흐름으로 보장한다:
        // 이 try 블록 안에서 던져지는 모든 예외는 confirmPayment 호출 이전에 발생한 것이므로
        // — 지갑 조회 중 DB 장애 같은 예상 못 한 인프라 예외까지 포함해 — 반드시 클레임을
        // 해제한다. Toss를 호출조차 하지 않았으니 이중충전 위험이 없고, 해제하지 않으면
        // 해당 orderId가 아무 이유 없이 TTL(10분) 동안 잠긴다.
        long confirmAmount;
        try {
            Map<String, Object> wallet = walletMapper.findByMemberId(memberId);
            if (wallet == null) {
                throw BusinessException.notFound("지갑을 찾을 수 없습니다.");
            }
            // 금액 변환도 이 블록 안에 둔다. @Digits(fraction=0)가 이미 정수를 보장하지만,
            // 검증을 우회하는 호출 경로가 생겨 ArithmeticException이 나더라도 Toss를 호출하기
            // 전이므로 클레임이 해제되는 것이 맞다 — 의도된 동작이다.
            confirmAmount = request.getAmount().setScale(0, RoundingMode.UNNECESSARY).longValueExact();
        } catch (RuntimeException e) {
            tossPaymentClaim.release(memberId, orderId);
            throw e;
        }

        // 3. Toss 승인 — 트랜잭션 밖에서 호출한다. 이 시점 이후로는 확정적 거부와 설정 오류를
        // 제외한 어떤 경로에서도 클레임을 해제하지 않는다.
        TossConfirmResult result = tossPaymentsClient.confirmPayment(paymentKey, orderId, confirmAmount);

        switch (result.getOutcome()) {
            case DEFINITIVE_REJECTION:
                // Toss가 승인을 보유하지 않음이 확정됨 — 클레임 해제, 부작용 없음, 감사 로그 불필요.
                tossPaymentClaim.release(memberId, orderId);
                throw new BusinessException(HttpStatus.PAYMENT_REQUIRED, "결제가 거절되었습니다.");
            case CONFIG_ERROR:
                // Toss가 요청을 인증조차 하지 않았으므로 승인을 보유할 수 없다 — 해제 가능.
                // 카드 거절(402)로 노출하면 시크릿 키 미설정 같은 우리 쪽 설정 오류가 데모
                // 중 "카드가 거절되었습니다"로 오인될 수 있어 500으로 명확히 구분한다.
                tossPaymentClaim.release(memberId, orderId);
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
                auditLogger.confirmIndeterminate(paymentKey, orderId, memberId, result.getMessage());
                throw new BusinessException(HttpStatus.BAD_GATEWAY,
                        "충전 상태를 확인할 수 없습니다. 새 주문번호로 다시 시도해 주세요.");
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
            // 5. 성공 — 클레임은 해제하지 않고 TTL(10분) 만료까지 유지한다.
            return walletService.depositExternal(command);
        } catch (DuplicateKeyException e) {
            // 해당 paymentKey가 이미 원장에 기록되어 있음 — 대사가 이미 완료된 상태이므로
            // 감사 마커는 필요 없다. 반드시 이 좁은 타입만 잡는다: 부모 타입인
            // DataIntegrityViolationException을 잡으면 외래키 위반(MySQL 1452, 보상 경로를
            // 검증하는 시나리오)까지 함께 삼켜져 아래 보상 분기가 실행되지 않게 된다.
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
