package com.aewol.domain.transaction.service;

import com.aewol.common.exception.BusinessException;
import com.aewol.domain.transaction.dto.PaymentRecordCommand;
import com.aewol.domain.transaction.dto.PaymentRequest;
import com.aewol.domain.transaction.dto.TransactionResponse;
import com.aewol.domain.transaction.dto.TransactionTagUpdateRequest;
import com.aewol.domain.transaction.dto.TransactionPageResponse;
import com.aewol.domain.pet.mapper.PetMapper;
import com.aewol.domain.transaction.mapper.TransactionMapper;
import com.aewol.domain.wallet.mapper.WalletMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class TransactionServiceImpl implements TransactionService {

    private static final Pattern CURSOR_PATTERN = Pattern.compile(
            "\\{\\\"date\\\":\\\"([^\\\"]+)\\\",\\\"id\\\":(\\d+)}");

    private final TransactionMapper transactionMapper;
    private final WalletMapper walletMapper;
    private final AutoTaggingService autoTaggingService;
    private final PetMapper petMapper;
    private final PaymentLedgerService paymentLedgerService;

    /**
     * 결제 처리.
     *
     * <p><b>여기에 {@code @Transactional}을 붙이지 않는다.</b> 카테고리 판정이 카카오 로컬
     * API를 호출하는데, {@code DataSourceTransactionManager}는 트랜잭션 시작 시점에 커넥션을
     * 바인딩한다. 트랜잭션 안에서 호출하면 카카오 응답을 기다리는 내내 커넥션 하나를 붙잡고
     * 있게 된다. 풀은 10개(DataSourceConfig.java:47)이고 이 호출은 최대 20초까지 걸릴 수
     * 있어(연결 10초 + 읽기 10초), 처음 보는 상호로 결제가 몰리면 풀이 비고 결제와 무관한
     * 요청까지 함께 멈춘다.
     *
     * <p>실제로 재현해 봤다. 결제 10건이 동시에 들어오고 카카오가 20초간 응답하지 않을 때,
     * 무관한 요청의 대기 시간이 <b>19.9초에서 29ms로</b> 줄었다.
     *
     * <p>같은 이유로 {@code TossChargeService}도 비트랜잭셔널 오케스트레이터다. 원장 기록만
     * {@link PaymentLedgerService#record}의 짧은 트랜잭션으로 넘긴다.
     */
    @Override
    public TransactionResponse processPayment(String memberId, PaymentRequest request) {
        validatePaymentRequest(request);
        // 반려동물 소유권과 지갑 존재는 짧은 조회라 커넥션을 바로 반납한다. 외부 호출보다
        // 먼저 걸러야 처음부터 안 될 요청이 카카오 응답을 20초 기다린 뒤에 실패하지 않는다.
        assertOwnedPet(memberId, request.getPetId());
        if (walletMapper.findByMemberId(memberId) == null) {
            throw BusinessException.notFound("지갑을 찾을 수 없습니다.");
        }

        // 외부 호출은 트랜잭션 밖에서 끝낸다.
        String category = autoTaggingService.categorize(request.getMerchantName());
        log.info("자동 태깅 결과 - merchant: {}, category: {}", request.getMerchantName(), category);

        PaymentRecordCommand command = PaymentRecordCommand.builder()
                .memberId(memberId)
                .merchantName(request.getMerchantName())
                .amount(request.getAmount())
                .petId(request.getPetId())
                .memo(request.getMemo())
                .build();

        // 원장 기록과 그 결과를 읽는 것까지 한 트랜잭션이다. 커밋 뒤에 읽으면 그 조회가
        // 실패했을 때 돈은 빠지고 사용자는 오류를 받는 상태가 된다.
        return toResponse(paymentLedgerService.record(command, category));
    }

    private void validatePaymentRequest(PaymentRequest request) {
        if (request == null) {
            throw new BusinessException("결제 정보를 입력해 주세요.");
        }
        String merchantName = request.getMerchantName();
        if (merchantName == null || merchantName.isBlank()) {
            throw new BusinessException(PaymentRequest.MERCHANT_NAME_REQUIRED_MESSAGE);
        }
        if (merchantName.length() > PaymentRequest.MAX_MERCHANT_NAME_LENGTH) {
            throw new BusinessException(PaymentRequest.MERCHANT_NAME_LENGTH_MESSAGE);
        }
        BigDecimal amount = request.getAmount();
        if (amount == null || amount.compareTo(PaymentRequest.MIN_AMOUNT) < 0) {
            throw new BusinessException(PaymentRequest.AMOUNT_MIN_MESSAGE);
        }
        if (amount.stripTrailingZeros().scale() > 0) {
            throw new BusinessException(PaymentRequest.AMOUNT_INTEGER_MESSAGE);
        }
        if (amount.compareTo(PaymentRequest.MAX_AMOUNT) > 0) {
            throw new BusinessException(PaymentRequest.AMOUNT_MAX_MESSAGE);
        }
    }

    @Override
    public TransactionPageResponse getTransactions(String memberId, String type, String period,
                                                    String cursor, int size) {
        if (size < 1 || size > 100) {
            throw new BusinessException("거래 조회 개수는 1개 이상 100개 이하여야 합니다.");
        }
        String txnFilter = normalizeListTransactionType(type);
        YearMonth targetMonth = parseMonth(period);
        TransactionCursor decodedCursor = decodeCursor(cursor);
        Map<String, Object> wallet = walletMapper.findByMemberId(memberId);
        if (wallet == null) {
            throw BusinessException.notFound("지갑을 찾을 수 없습니다.");
        }
        String walletId = String.valueOf(wallet.get("wallet_id"));
        List<Map<String, Object>> rows = transactionMapper.findByWalletId(
                walletId, txnFilter, targetMonth.atDay(1).atStartOfDay(),
                targetMonth.plusMonths(1).atDay(1).atStartOfDay(),
                decodedCursor == null ? null : decodedCursor.date(),
                decodedCursor == null ? null : decodedCursor.id(), size + 1);
        boolean hasNext = rows.size() > size;
        List<Map<String, Object>> pageRows = hasNext ? rows.subList(0, size) : rows;
        List<TransactionResponse> transactions = pageRows.stream()
                .map(this::toResponse).collect(Collectors.toList());
        String nextCursor = hasNext ? encodeCursor(pageRows.get(pageRows.size() - 1)) : null;
        return TransactionPageResponse.builder()
                .transactions(transactions)
                .nextCursor(nextCursor)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public List<TransactionResponse> getRecentTransactions(String memberId, String type, int limit) {
        if (limit < 1 || limit > 20) {
            throw new BusinessException("최근 거래 조회 개수는 1개 이상 20개 이하여야 합니다.");
        }
        String txnFilter = normalizeTransactionType(type);
        Map<String, Object> wallet = walletMapper.findByMemberId(memberId);
        if (wallet == null) {
            throw BusinessException.notFound("지갑을 찾을 수 없습니다.");
        }
        String walletId = String.valueOf(wallet.get("wallet_id"));
        return transactionMapper.findRecentByWalletId(walletId, txnFilter, limit).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public TransactionResponse getTransaction(String memberId, String txnId) {
        Map<String, Object> txn = transactionMapper.findById(txnId);
        if (txn == null) {
            throw BusinessException.notFound("거래를 찾을 수 없습니다.");
        }
        Map<String, Object> wallet = walletMapper.findById(String.valueOf(txn.get("wallet_id")));
        boolean isOwner = wallet != null && Objects.equals(memberId, String.valueOf(wallet.get("member_id")));
        if (!isOwner) {
            throw BusinessException.forbidden("거래를 조회할 권한이 없습니다.");
        }
        return toResponse(txn);
    }

    @Override
    @Transactional
    public TransactionResponse updateTag(String memberId, String txnId, TransactionTagUpdateRequest request) {
        TransactionResponse transaction = getTransaction(memberId, txnId);
        if (!"PAYMENT".equals(transaction.getTxnType())) {
            throw new BusinessException("결제 거래만 태그를 수정할 수 있습니다.");
        }
        assertOwnedPet(memberId, request.getPetId());
        if (transactionMapper.updateTag(txnId, request.getCategory(), request.getPetId()) == 0) {
            throw BusinessException.notFound("거래를 찾을 수 없습니다.");
        }
        return getTransaction(memberId, txnId);
    }

    private void assertOwnedPet(String memberId, String petId) {
        if (petId == null || petId.isBlank()) {
            return;
        }
        if (petMapper.findByIdAndMemberId(petId, memberId) == null) {
            throw BusinessException.notFound("반려동물을 찾을 수 없습니다.");
        }
    }

    private String normalizeTransactionType(String type) {
        if (type == null || "ALL".equalsIgnoreCase(type)) return null;
        if ("CHARGE".equalsIgnoreCase(type)) return "CHARGE";
        if ("WITHDRAW".equalsIgnoreCase(type)) return "WITHDRAW";
        if ("PAYMENT".equalsIgnoreCase(type)) return "PAYMENT";
        throw new BusinessException("거래 유형은 ALL, CHARGE, WITHDRAW, PAYMENT 중 하나여야 합니다.");
    }

    private String normalizeListTransactionType(String type) {
        if (type == null || "ALL".equalsIgnoreCase(type)) return "ALL";
        if ("CHARGE".equalsIgnoreCase(type)) return "CHARGE";
        if ("WITHDRAW".equalsIgnoreCase(type)) return "WITHDRAW";
        if ("PAYMENT".equalsIgnoreCase(type)) return "PAYMENT";
        throw new BusinessException("거래 유형은 ALL, CHARGE, WITHDRAW, PAYMENT 중 하나여야 합니다.");
    }

    private YearMonth parseMonth(String period) {
        if (period == null || period.isBlank()) return YearMonth.now();
        try {
            return YearMonth.parse(period);
        } catch (DateTimeParseException exception) {
            throw new BusinessException("조회 기간은 yyyy-MM 형식이어야 합니다.");
        }
    }

    private TransactionCursor decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) return null;
        try {
            String json = new String(Base64.getDecoder().decode(cursor), StandardCharsets.UTF_8);
            Matcher matcher = CURSOR_PATTERN.matcher(json);
            if (!matcher.matches()) throw new IllegalArgumentException();
            return new TransactionCursor(LocalDateTime.parse(matcher.group(1)), Long.valueOf(matcher.group(2)));
        } catch (IllegalArgumentException | DateTimeParseException exception) {
            throw new BusinessException("유효하지 않은 거래 커서입니다.");
        }
    }

    private String encodeCursor(Map<String, Object> transaction) {
        String json = "{\"date\":\"" + transaction.get("txn_date")
                + "\",\"id\":" + transaction.get("txn_id") + "}";
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private record TransactionCursor(LocalDateTime date, Long id) {}

    private TransactionResponse toResponse(Map<String, Object> txn) {
        return TransactionResponse.builder()
                .txnId(String.valueOf(txn.get("txn_id")))
                .walletId(String.valueOf(txn.get("wallet_id")))
                .txnType((String) txn.get("txn_type"))
                .amount((BigDecimal) txn.get("price"))
                .category((String) txn.get("category"))
                .petId(txn.get("pet_id") != null ? String.valueOf(txn.get("pet_id")) : null)
                .merchantName((String) txn.get("merchant_name"))
                .memo((String) txn.get("memo"))
                .autoTagged((String) txn.get("auto_tagged"))
                .taggedBy("Y".equals(txn.get("auto_tagged")) ? "AUTO" : "MANUAL")
                .txnDate(txn.get("txn_date") != null ? txn.get("txn_date").toString() : null)
                .paymentMethod("애월 통합 지갑")
                .build();
    }
}
