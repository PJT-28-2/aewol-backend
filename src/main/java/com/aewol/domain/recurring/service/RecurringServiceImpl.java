package com.aewol.domain.recurring.service;

import com.aewol.common.exception.BusinessException;
import com.aewol.domain.pet.mapper.PetMapper;
import com.aewol.domain.recurring.dto.RecurringCreateRequest;
import com.aewol.domain.recurring.dto.RecurringResponse;
import com.aewol.domain.recurring.mapper.RecurringMapper;
import com.aewol.domain.wallet.mapper.WalletMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RecurringServiceImpl implements RecurringService {

    private final RecurringMapper recurringMapper;
    private final WalletMapper walletMapper;
    private final PetMapper petMapper;

    @Override
    @Transactional(readOnly = true)
    public List<RecurringResponse> getRecurringPayments(String memberId) {
        Map<String, Object> wallet = walletMapper.findByMemberId(memberId);
        if (wallet == null) throw BusinessException.notFound("지갑을 찾을 수 없습니다.");
        return recurringMapper.findByWalletId(String.valueOf(wallet.get("wallet_id"))).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public RecurringResponse createRecurring(String memberId, RecurringCreateRequest request) {
        Map<String, Object> wallet = walletMapper.findByMemberId(memberId);
        if (wallet == null) throw BusinessException.notFound("지갑을 찾을 수 없습니다.");
        assertPetOwnership(memberId, request.getPetId());

        int paymentDay = request.getCycleDay();

        Map<String, Object> recurring = new HashMap<>();
        recurring.put("walletId", wallet.get("wallet_id"));
        recurring.put("petId", blankToNull(request.getPetId()));
        recurring.put("productName", request.getItemName().trim());
        recurring.put("category", request.getCategory());
        recurring.put("price", request.getPrice());
        recurring.put("paymentDay", paymentDay);
        recurring.put("nextPaymentDate", nextPaymentDate(paymentDay));
        recurringMapper.insert(recurring); // recurring_id AUTO_INCREMENT
        return toResponse(recurring);
    }

    @Override
    @Transactional
    public RecurringResponse updateRecurring(String memberId, String recurringId, RecurringCreateRequest request) {
        Map<String, Object> recurring = recurringMapper.findByIdForUpdate(recurringId);
        if (recurring == null || !isActive(recurring.get("is_active"))) {
            throw BusinessException.notFound("정기결제를 찾을 수 없습니다.");
        }
        Map<String, Object> wallet = walletMapper.findById(String.valueOf(recurring.get("wallet_id")));
        if (wallet == null || !Objects.equals(memberId, String.valueOf(wallet.get("member_id")))) {
            throw BusinessException.forbidden("정기결제를 변경할 권한이 없습니다.");
        }
        assertPetOwnership(memberId, request.getPetId());

        int paymentDay = request.getCycleDay();
        int currentPaymentDay = intValue(value(recurring, "payment_day", "paymentDay", "cycleDay"));
        LocalDate nextPaymentDate = paymentDay == currentPaymentDay
                ? localDateValue(value(recurring, "next_payment_date", "nextPaymentDate"))
                : nextPaymentDate(paymentDay);

        Map<String, Object> params = new HashMap<>();
        params.put("recurringId", recurringId);
        params.put("petId", blankToNull(request.getPetId()));
        params.put("productName", request.getItemName().trim());
        params.put("category", request.getCategory());
        params.put("price", request.getPrice());
        params.put("paymentDay", paymentDay);
        params.put("nextPaymentDate", nextPaymentDate);
        if (recurringMapper.update(params) != 1) {
            throw BusinessException.notFound("정기결제를 찾을 수 없습니다.");
        }
        return toResponse(params);
    }

    @Override
    @Transactional
    public void cancelRecurring(String memberId, String recurringId) {
        Map<String, Object> recurring = recurringMapper.findByIdForUpdate(recurringId);
        if (recurring == null || !isActive(recurring.get("is_active"))) {
            throw BusinessException.notFound("정기결제를 찾을 수 없습니다.");
        }
        Map<String, Object> wallet = walletMapper.findById(String.valueOf(recurring.get("wallet_id")));
        if (wallet == null || !Objects.equals(memberId, String.valueOf(wallet.get("member_id")))) {
            throw BusinessException.forbidden("정기결제를 해지할 권한이 없습니다.");
        }
        if (recurringMapper.deactivate(recurringId) != 1) {
            throw BusinessException.notFound("정기결제를 찾을 수 없습니다.");
        }
    }

    private void assertPetOwnership(String memberId, String petId) {
        String normalizedPetId = blankToNull(petId);
        if (normalizedPetId == null) return;
        Map<String, Object> pet = petMapper.findById(normalizedPetId);
        if (pet == null) throw BusinessException.notFound("반려동물을 찾을 수 없습니다.");
        if (!Objects.equals(memberId, String.valueOf(pet.get("member_id")))) {
            throw BusinessException.forbidden("해당 반려동물을 정기결제에 지정할 권한이 없습니다.");
        }
    }

    /** 매월 N일 기준. 해당 날짜가 없는 달은 그달의 마지막 날로 계산한다. */
    private LocalDate nextPaymentDate(int paymentDay) {
        return nextPaymentDate(paymentDay, LocalDate.now());
    }

    public static LocalDate nextPaymentDate(int paymentDay, LocalDate today) {
        YearMonth currentMonth = YearMonth.from(today);
        LocalDate candidate = paymentDate(currentMonth, paymentDay);
        if (candidate.isAfter(today)) return candidate;
        return paymentDate(currentMonth.plusMonths(1), paymentDay);
    }

    private static LocalDate paymentDate(YearMonth month, int paymentDay) {
        return month.atDay(Math.min(paymentDay, month.lengthOfMonth()));
    }

    private RecurringResponse toResponse(Map<String, Object> recurring) {
        Object recurringId = value(recurring, "recurring_id", "recurringId");
        Object price = value(recurring, "price");
        Object cycleDay = value(recurring, "payment_day", "paymentDay", "cycleDay");
        Object nextPaymentDate = value(recurring, "next_payment_date", "nextPaymentDate");
        Object petId = value(recurring, "pet_id", "petId");
        return RecurringResponse.builder()
                .recurringId(recurringId == null ? null : String.valueOf(recurringId))
                .itemName(stringValue(value(recurring, "product_name", "productName", "itemName")))
                .price(price instanceof BigDecimal ? (BigDecimal) price : new BigDecimal(String.valueOf(price)))
                .cycleDay(cycleDay instanceof Number
                        ? ((Number) cycleDay).intValue() : Integer.parseInt(String.valueOf(cycleDay)))
                .category(stringValue(value(recurring, "category")))
                .petId(petId == null ? null : String.valueOf(petId))
                .nextPaymentDate(nextPaymentDate == null ? null : String.valueOf(nextPaymentDate))
                .build();
    }

    private static Object value(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            if (map.containsKey(key)) return map.get(key);
        }
        return null;
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static int intValue(Object value) {
        return value instanceof Number
                ? ((Number) value).intValue()
                : Integer.parseInt(String.valueOf(value));
    }

    private static LocalDate localDateValue(Object value) {
        return value instanceof LocalDate
                ? (LocalDate) value
                : LocalDate.parse(String.valueOf(value));
    }

    private static boolean isActive(Object value) {
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof Number) return ((Number) value).intValue() == 1;
        return "1".equals(String.valueOf(value)) || "true".equalsIgnoreCase(String.valueOf(value));
    }
}
