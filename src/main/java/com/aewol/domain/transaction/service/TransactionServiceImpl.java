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

    @Override
    @Transactional
    public TransactionResponse processPayment(String memberId, PaymentRequest request) {
        PaymentRecordCommand command = PaymentRecordCommand.builder()
                .memberId(memberId)
                .merchantName(request.getMerchantName())
                .amount(request.getAmount())
                .petId(request.getPetId())
                .memo(request.getMemo())
                .build();
        return recordPayment(command);
    }

    private TransactionResponse recordPayment(PaymentRecordCommand command) {
        Map<String, Object> wallet = walletMapper.findByMemberId(command.getMemberId());
        if (wallet == null) {
            throw BusinessException.notFound("지갑을 찾을 수 없습니다.");
        }

        String walletId = String.valueOf(wallet.get("wallet_id"));

        // 자동 태깅
        String category = autoTaggingService.categorize(command.getMerchantName());
        log.info("자동 태깅 결과 - merchant: {}, category: {}", command.getMerchantName(), category);

        // 지갑 잔액 차감 (V1에서 버킷 폐기 — 지갑 단일 잔액)
        BigDecimal balance = (BigDecimal) wallet.get("balance");
        if (balance.compareTo(command.getAmount()) < 0) {
            throw new BusinessException("잔액이 부족합니다.");
        }
        // balance 조회 후 절대값을 저장하면 동시 결제에서 갱신이 유실될 수 있어,
        // balance - amount와 balance >= amount 조건을 하나의 원자적 UPDATE로 수행한다.
        if (walletMapper.deductBalance(walletId, command.getAmount()) == 0) {
            throw new BusinessException("잔액이 부족합니다.");
        }

        // 거래 기록 생성 — txn_id는 AUTO_INCREMENT 생성 키
        Map<String, Object> txn = new HashMap<>();
        txn.put("walletId", walletId);
        txn.put("petId", command.getPetId());
        txn.put("txnType", "PAYMENT");
        txn.put("price", command.getAmount());
        txn.put("category", category);
        txn.put("merchantName", command.getMerchantName());
        txn.put("merchantCategoryCode", null);
        txn.put("memo", command.getMemo());
        txn.put("autoTagged", "Y");
        txn.put("txnDate", LocalDateTime.now());
        transactionMapper.insert(txn);

        return getTransaction(command.getMemberId(), String.valueOf(txn.get("txnId")));
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
        if (request.getPetId() != null
                && petMapper.findByIdAndMemberId(request.getPetId(), memberId) == null) {
            throw BusinessException.notFound("반려동물을 찾을 수 없습니다.");
        }
        if (transactionMapper.updateTag(txnId, request.getCategory(), request.getPetId()) == 0) {
            throw BusinessException.notFound("거래를 찾을 수 없습니다.");
        }
        return getTransaction(memberId, txnId);
    }

    private String normalizeTransactionType(String type) {
        if (type == null || "ALL".equalsIgnoreCase(type)) return null;
        if ("CHARGE".equalsIgnoreCase(type)) return "CHARGE";
        if ("WITHDRAW".equalsIgnoreCase(type)) return "WITHDRAW";
        throw new BusinessException("거래 유형은 ALL, CHARGE, WITHDRAW 중 하나여야 합니다.");
    }

    private String normalizeListTransactionType(String type) {
        if (type == null || "ALL".equalsIgnoreCase(type)) return "ALL";
        if ("CHARGE".equalsIgnoreCase(type)) return "CHARGE";
        if ("WITHDRAW".equalsIgnoreCase(type)) return "WITHDRAW";
        throw new BusinessException("거래 유형은 ALL, CHARGE, WITHDRAW 중 하나여야 합니다.");
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
