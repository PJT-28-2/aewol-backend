package com.aewol.domain.account.service;

import com.aewol.common.exception.BusinessException;
import com.aewol.domain.account.dto.AccountPrimaryRequest;
import com.aewol.common.util.AccountNumberCrypto;
import com.aewol.common.util.RedisRateLimiter;
import com.aewol.domain.account.dto.AccountRegisterRequest;
import com.aewol.domain.account.dto.AccountResponse;
import com.aewol.domain.account.dto.DepositConfirmRequest;
import com.aewol.domain.account.dto.DepositConfirmResponse;
import com.aewol.domain.account.dto.DepositVerificationRequest;
import com.aewol.domain.account.dto.DepositVerificationResponse;
import com.aewol.domain.account.mapper.AccountMapper;
import com.aewol.domain.account.mapper.AccountVerificationMapper;
import com.aewol.external.codef.CodefClient;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AccountServiceImpl implements AccountService {

    private final AccountMapper accountMapper;
    private final AccountVerificationMapper accountVerificationMapper;
    private final CodefClient codefClient;
    private final Environment environment;
    private final RedisRateLimiter redisRateLimiter;
    private final AccountNumberCrypto accountNumberCrypto;

    // 시연 환경에서 입금자명을 API 응답으로 내려줄지 여부(#290). 기본값은 false이고,
    // true로 켜더라도 CODEF 데모 서버에 붙어 있을 때만 실제로 동작한다
    // (isTestExposureAllowed 주석 참고).
    @Value("${demo.expose-depositor-name:false}")
    private boolean exposeDepositorNameForDemo;

    // 프론트(store.requestDepositAuth의 DEPOSIT_AUTH_TIMEOUT_SECONDS)와 동일한 유효시간.
    // 여기서 안 맞추면 프론트는 아직 타이머가 남았다고 보여주는데 서버는 이미
    // 만료 처리하는 상황이 생길 수 있다.
    private static final long DEPOSIT_AUTH_TIMEOUT_SECONDS = 180;

    // 입금자명 후보 공간을 단어 조합(ADJECTIVES x NOUNS, 8,000가지 — CodefClient 참고)으로
    // 넓혀도, confirm API에 재시도 횟수 제한이 없으면 같은 transactionId로 계속 찍어보는
    // 무차별 대입이 가능하다. 5번 틀리면 그 이후엔 정답을 넣어도 통과시키지 않는다
    // (2026-08-07).
    private static final int MAX_VERIFICATION_ATTEMPTS = 5;

    // confirm 오답 제한이 있어도 "다시 보내기"로 매번 새 transactionId(attempt_count=0)를
    // 받으면 그 제한이 무력화된다. 그래서 요청(재전송 포함) 자체도 회원 단위로 제한한다
    // (비즈고 2FA "3분 동안 5회" 참고, 2026-08-07). Redis에 TTL 딸린 카운터로 관리.
    private static final int MAX_VERIFICATION_REQUESTS = 5;
    private static final long VERIFICATION_REQUEST_WINDOW_SECONDS = 180;
    private static final String VERIFICATION_REQUEST_KEY_PREFIX = "deposit-request-attempts:";

    @Override
    // CODEF 호출(동기 HTTP)이 끝난 뒤 insert 한 번만 하면 되는 메서드라 @Transactional을
    // 붙이지 않는다. 트랜잭션 안에서 외부 HTTP 호출을 하면 CODEF 응답을 기다리는 동안
    // DB 커넥션을 계속 점유하게 돼서, CODEF가 지연되면 커넥션 풀 고갈로 서비스 전체가
    // 영향을 받을 수 있다(CodeRabbit 지적, 2026-08-06).
    public DepositVerificationResponse requestDepositVerification(String memberId, DepositVerificationRequest request) {
        enforceRequestRateLimit(memberId);

        String transactionId = generateTransactionId();

        // CODEF 1원 이체 요청 — CODEF가 랜덤 입금자명(authCode)을 직접 생성해서 돌려준다.
        // 실패하면 예외로 여기서 끊기고 account_verification에 아무것도 안 남는다.
        String depositorName = codefClient.requestAccountTransferAuth(request.getBankCode(), request.getAccountNumber());

        Map<String, Object> verification = new HashMap<>();
        verification.put("transactionId", transactionId);
        verification.put("memberId", memberId);
        verification.put("bankCode", request.getBankCode());
        // 계좌번호는 여기서부터 암호화해서 저장한다(AES-256-GCM) — account_verification도
        // linked_account와 마찬가지로 원문 계좌번호를 평문으로 들고 있으면 안 된다
        // (ERD_수정안_계좌관리_고객센터.md 1.3 "결정 완료" 항목, 2026-08-13 반영).
        verification.put("accountNumber", accountNumberCrypto.encrypt(request.getAccountNumber()));
        verification.put("verificationCode", depositorName);
        // DB DEFAULT CURRENT_TIMESTAMP에 맡기면 MySQL 컨테이너 시간대(TZ 미설정 시 UTC)와
        // JVM 로컬 시간대(KST)가 어긋나서 isExpired()가 저장 직후에도 만료로 오판할 수 있다.
        // 항상 JVM 기준 시각을 직접 저장해서 isExpired()의 LocalDateTime.now()와 같은 기준을 쓰게 한다.
        verification.put("requestedAt", LocalDateTime.now());
        accountVerificationMapper.insert(verification);

        return DepositVerificationResponse.builder()
                .transactionId(transactionId)
                .depositorNameLength(depositorName.length())
                .depositorNameForTest(isTestExposureAllowed() ? depositorName : null)
                .build();
    }

    // 이 값을 그대로 내려주면 사용자가 실제 입금 내역을 확인하지 않고도
    // verify-deposit -> confirm을 통과시킬 수 있어 1원 인증의 본인 확인 효력이
    // 무효화된다(CodeRabbit 지적, 2026-08-06).
    //
    // 그래서 한동안 local/test 프로필에서만 값을 채웠다(PR #236, 2026-08-19). 하지만
    // 프로필은 "실제 돈이 오가는가"의 간접 신호일 뿐이라, 배포 서버로 시연할 때는
    // CODEF 데모 서버(=실제 이체 없음)에 붙어 있는데도 입금자명을 확인할 방법이
    // DB 직접 조회밖에 없는 문제가 있었다(#290).
    //
    // 그래서 판단 기준을 프로필에서 "지금 붙어 있는 CODEF 서버가 데모 서버인가"로
    // 바꾼다. 데모 서버가 아니면 프로필이 무엇이든, 플래그가 켜져 있든 무조건 차단한다
    // — 정식 계약 후 CODEF_API_BASE_URL만 교체해도 시연용 설정을 끄는 걸 깜빡한 채
    // 배포되는 사고가 구조적으로 불가능해지므로, PR #236에서 우려한 위험은 오히려
    // 이전보다 확실하게 막힌다.
    //
    // 데모 서버라는 전제 위에서, 실제로 값을 내려줄지는 두 경로로 결정한다.
    //   - local/test 프로필: 개발/테스트 편의를 위해 항상 노출(기존 동작 유지)
    //   - demo.expose-depositor-name=true: 시연용 배포 환경에서 명시적으로 켠 경우
    private boolean isTestExposureAllowed() {
        // 실제 1원이 오가는 정식 서버에서는 어떤 설정으로도 노출하지 않는다.
        if (!codefClient.isDemoServer()) {
            return false;
        }
        return exposeDepositorNameForDemo
                || environment.acceptsProfiles(Profiles.of("local", "test"));
    }

    /**
     * 회원 단위로 "3분 동안 5회"까지만 1원 인증을 요청(최초 요청 + 다시 보내기 포함)할 수 있게 막는다.
     * confirm의 오답 5회 제한이 있어도, 재전송으로 매번 새 attempt_count=0을 받을 수 있으면
     * 그 제한이 무의미해지기 때문에 요청 자체도 같이 제한한다.
     */
    private void enforceRequestRateLimit(String memberId) {
        String key = VERIFICATION_REQUEST_KEY_PREFIX + memberId;
        // INCR와 최초 증가 시 EXPIRE를 Lua 스크립트로 원자 실행 — 그 사이 인스턴스가 죽어도
        // TTL 없는 키가 영구히 남지 않는다(CodeRabbit 지적, 2026-08-07).
        long count = redisRateLimiter.incrementWithExpiry(key, VERIFICATION_REQUEST_WINDOW_SECONDS);
        if (count > MAX_VERIFICATION_REQUESTS) {
            throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "1원 인증 요청이 너무 많아요. 잠시 후 다시 시도해주세요");
        }
    }

    @Override
    @Transactional
    public DepositConfirmResponse confirmDepositVerification(String memberId, DepositConfirmRequest request) {
        // findByIdForUpdate로 행을 잠근 채 한도 검사 -> 값 비교 -> 갱신까지 이 트랜잭션
        // 안에서 순서대로 끝낸다 — findById(잠금 없음)로 읽으면 동시 요청이 같은
        // attempt_count를 읽고 모두 한도 미만으로 통과할 수 있다(CodeRabbit 지적, 2026-08-07).
        Map<String, Object> verification = accountVerificationMapper.findByIdForUpdate(request.getTransactionId());
        if (verification == null || !String.valueOf(verification.get("member_id")).equals(memberId)) {
            throw BusinessException.notFound("1원 인증 요청을 찾을 수 없어요");
        }
        if (!"PENDING".equals(verification.get("status"))) {
            // 이미 VERIFIED/USED인 건을 다시 확인하려는 경우 — 재사용 방지를 위해 실패로 처리
            return DepositConfirmResponse.builder().verified(false).reason("ALREADY_USED").build();
        }
        if (isExpired(verification.get("requested_at"))) {
            return DepositConfirmResponse.builder().verified(false).reason("EXPIRED").build();
        }
        if (toInt(verification.get("attempt_count")) >= MAX_VERIFICATION_ATTEMPTS) {
            // 이미 한도를 넘긴 요청은 이번에 정답을 넣었어도 통과시키지 않는다 —
            // 그렇지 않으면 한도가 "몇 번 틀렸는지 세기"만 하고 실제 방어 역할을
            // 못 한다.
            return DepositConfirmResponse.builder().verified(false).reason("TOO_MANY_ATTEMPTS").build();
        }

        boolean matched = String.valueOf(verification.get("verification_code")).equals(request.getVerificationCode());
        if (matched) {
            accountVerificationMapper.updateStatus(request.getTransactionId(), "VERIFIED");
        } else {
            accountVerificationMapper.incrementAttemptCount(request.getTransactionId());
        }
        return DepositConfirmResponse.builder()
                .verified(matched)
                .reason(matched ? null : "MISMATCH")
                .build();
    }

    @Override
    @Transactional
    public AccountResponse registerAccount(String memberId, AccountRegisterRequest request) {
        Map<String, Object> verification = accountVerificationMapper.findById(request.getTransactionId());
        if (verification == null || !String.valueOf(verification.get("member_id")).equals(memberId)) {
            throw BusinessException.notFound("1원 인증 요청을 찾을 수 없어요");
        }
        if (!"VERIFIED".equals(verification.get("status"))) {
            throw new BusinessException(HttpStatus.CONFLICT, "1원 인증이 완료되지 않았어요");
        }

        // VERIFIED -> USED 전환을 조건부 UPDATE + 영향 행 수 확인으로 원자적으로 처리한다.
        // 위의 findById 읽기와 상태 체크는 잠금이 없어서, 같은 transactionId로
        // registerAccount가 동시에 두 번 들어오면 둘 다 VERIFIED를 읽고 그대로 통과해
        // 계좌가 중복 생성될 수 있다(CodeRabbit 지적, 2026-08-06). markUsedIfVerified는
        // WHERE절에 status='VERIFIED'를 같이 걸기 때문에 둘 중 하나만 실제로 행을
        // 갱신하고, 나머지는 영향 행 0을 받아 계좌 insert 전에 여기서 바로 막힌다.
        int updated = accountVerificationMapper.markUsedIfVerified(request.getTransactionId());
        if (updated == 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "이미 처리된 1원 인증 요청이에요");
        }

        String bankCode = (String) verification.get("bank_code");
        // account_verification에 이미 암호화된 상태로 저장돼 있으므로(requestDepositVerification)
        // 그대로 linked_account에 옮겨 담으면 된다 — 다시 암호화할 필요 없다.
        String encryptedAccountNumber = (String) verification.get("account_number");
        // 다만 중복 체크용 해시(HMAC)는 원문 기준이어야 해서, 저장할 때만 잠깐 복호화한다.
        // GCM은 매번 IV가 달라 같은 계좌번호도 암호문이 서로 다르므로 암호문끼리는
        // 비교할 수 없다 — 그래서 해시는 항상 평문에서 만든다.
        String plainAccountNumber = accountNumberCrypto.decrypt(encryptedAccountNumber);

        // setPrimaryAccount만 기존 대표 계좌를 내리고 있어서, 이미 대표 계좌가 있는
        // 회원이 isPrimary=true로 새 계좌를 등록하면 대표 계좌가 2개가 될 수 있었다
        // (CodeRabbit 지적, 2026-08-08). 등록 경로도 같은 순서(기존 대표를 먼저 내리고
        // 새로 지정)를 따르게 한다.
        boolean makePrimary = Boolean.TRUE.equals(request.getIsPrimary());
        if (makePrimary) {
            accountMapper.clearPrimaryByMemberId(memberId);
        }

        Map<String, Object> account = new HashMap<>();
        account.put("memberId", memberId);
        account.put("bankCode", bankCode);
        account.put("accountNumber", encryptedAccountNumber);
        account.put("accountNumberHash", accountNumberCrypto.hash(plainAccountNumber));
        account.put("isPrimary", makePrimary ? 1 : 0);
        accountMapper.insert(account); // account_id AUTO_INCREMENT로 채워짐

        // useGeneratedKeys가 정상 동작하면 account_id가 채워진다. 못 채우면 아래에서
        // String.valueOf(null) → "null" 문자열이 되어 findByAccountId가 null을 반환하고
        // toAccountResponse에서 NPE가 나기 전에 여기서 먼저 막는다(CodeRabbit 지적, 2026-08-06).
        // @Transactional이라 여기서 던지면 방금 한 markUsedIfVerified/insert도 함께 롤백된다.
        if (account.get("accountId") == null) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "계좌 등록에 실패했어요. 다시 시도해주세요");
        }

        Map<String, Object> saved = accountMapper.findByAccountId(String.valueOf(account.get("accountId")));
        return toAccountResponse(saved);
    }

    @Override
    public List<AccountResponse> getAccounts(String memberId) {
        return accountMapper.findByMemberId(memberId).stream()
                .map(this::toAccountResponse)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public void disconnectAccount(String memberId, String accountId) {
        // 잠금 없이 읽으면(findByAccountId) 이 트랜잭션이 is_primary를 읽은 뒤 다른
        // 트랜잭션이 같은 계좌를 대표로 설정해도 그 변경을 못 보고 그대로 진행해서,
        // 활성 계좌가 있는데도 대표 계좌가 0개로 남을 수 있다(CodeRabbit 지적,
        // 2026-08-08). FOR UPDATE로 행을 잠가서 이 트랜잭션이 끝날 때까지 다른
        // 트랜잭션의 동시 수정을 막는다.
        Map<String, Object> account = accountMapper.findByAccountIdForUpdate(accountId);
        // 소유자 검증이 없으면 accountId만 알면 다른 회원의 계좌도 연동 해제할 수 있었다
        // (CodeRabbit 지적, 2026-08-08). setPrimaryAccount와 같은 방식으로 404 처리한다
        // — 존재 여부와 소유 여부를 구분해서 알려주지 않기 위해서다.
        if (account == null || !String.valueOf(account.get("member_id")).equals(memberId)) {
            throw BusinessException.notFound("연동된 계좌를 찾을 수 없어요");
        }
        boolean wasPrimary = toBool(account.get("is_primary"));

        // is_primary도 같이 0으로 내려감(updateStatus의 CASE WHEN, 2026-08-07 수정)
        accountMapper.updateStatus(accountId, "INACTIVE");

        // 대표 계좌를 해제한 거라면, 남은 ACTIVE 계좌 중 하나를 새 대표로 자동 승격한다
        // — 안 그러면 다른 ACTIVE 계좌가 있는데도 대표 계좌가 0개인 상태가 남는다
        // (CodeRabbit 지적, 2026-08-07). 남은 계좌가 없으면(전부 해제) 승격할 대상이
        // 없으니 그대로 대표 계좌 0개로 둔다 — 다음 계좌를 연동할 때 다시 지정하면 된다.
        if (wasPrimary) {
            List<Map<String, Object>> remaining = accountMapper.findByMemberId(memberId);
            if (!remaining.isEmpty()) {
                String nextPrimaryAccountId = String.valueOf(remaining.get(0).get("account_id"));
                // 후보 계좌도 잠금 없이 선택하면, 선택 직후 다른 트랜잭션이 이 계좌를
                // 연동 해제할 수 있다 — FOR UPDATE로 다시 잠가서 이 트랜잭션이 끝날
                // 때까지 다른 트랜잭션의 동시 수정을 막는다(CodeRabbit 지적, 2026-08-08).
                accountMapper.findByAccountIdForUpdate(nextPrimaryAccountId);
                int updated = accountMapper.setPrimary(nextPrimaryAccountId);
                if (updated != 1) {
                    // setPrimary의 WHERE status='ACTIVE' 조건에 걸려 갱신되지 않으면(예:
                    // 잠그기 직전 이미 연동 해제됨), 대표 계좌 0개 상태가 그대로 커밋되지
                    // 않도록 트랜잭션 전체를 롤백한다(@Transactional이라 여기서 던지면
                    // 위의 updateStatus도 함께 롤백된다).
                    throw new BusinessException(HttpStatus.CONFLICT, "대표 계좌 승격에 실패했어요. 다시 시도해주세요");
                }
            }
        }
    }

    @Override
    @Transactional
    public AccountResponse setPrimaryAccount(String memberId, String accountId, AccountPrimaryRequest request) {
        if (!Boolean.TRUE.equals(request.getIsPrimary())) {
            // isPrimary=false로 대표 계좌를 "해제"하는 요청은 지원하지 않는다 — 다른
            // 계좌를 대표로 지정하면 자동으로 해제되므로, 회원당 대표 계좌 0개 상태를
            // 만들 수 있는 요청 자체를 막는다.
            throw new BusinessException(HttpStatus.BAD_REQUEST, "isPrimary는 true만 지원해요");
        }

        Map<String, Object> account = accountMapper.findByAccountId(accountId);
        if (account == null || !String.valueOf(account.get("member_id")).equals(memberId)) {
            throw BusinessException.notFound("연동된 계좌를 찾을 수 없어요");
        }
        if (!"ACTIVE".equals(account.get("status"))) {
            throw new BusinessException(HttpStatus.CONFLICT, "연동 해제된 계좌는 대표 계좌로 설정할 수 없어요");
        }

        // 회원당 대표 계좌는 항상 1개 — 기존 대표를 먼저 내리고 지정한 계좌만 올린다.
        // 안전한 이유는 "트랜잭션 안이라서"가 아니라, UPDATE ... WHERE 문 자체가
        // 해당 행에 락을 걸어서 동시에 들어온 다른 요청은 이 트랜잭션이 커밋될 때까지
        // 대기하기 때문이다(InnoDB 행 잠금, 2026-08-07 코드리뷰 지적으로 주석 정정).
        accountMapper.clearPrimaryByMemberId(memberId);

        // 위의 findByAccountId 조회 이후, 여기 도달하기 전에 다른 트랜잭션이 같은
        // 계좌를 연동 해제(INACTIVE)할 수 있다. setPrimary는 status='ACTIVE' 조건부라
        // 그 경우 영향 행 0을 반환하므로, 방금 한 clearPrimaryByMemberId까지 롤백해서
        // "대표 계좌 0개" 상태가 커밋되는 걸 막는다(CodeRabbit 지적, 2026-08-07).
        int updated = accountMapper.setPrimary(accountId);
        if (updated == 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "연동 해제된 계좌는 대표 계좌로 설정할 수 없어요");
        }

        return toAccountResponse(accountMapper.findByAccountId(accountId));
    }

    // 연동된 외부 은행 계좌의 실제 잔액은 조회하지 않는다(2026-08-06 결정) — 실시간 조회는
    // CODEF에 사용자의 인터넷뱅킹 아이디/비밀번호를 넘겨야 해서 신뢰 장벽이 크고, 애월의
    // 핵심 기능(버킷/지출관리)은 어차피 이 계좌 잔액이 아니라 내부 지갑(wallet) 잔액만
    // 쓴다. AccountResponse에도 balance 필드 자체가 없다.
    //
    // account_number는 DB에 암호화 저장돼 있어서(AccountNumberCrypto), 응답을 만들 때
    // 딱 한 번 복호화한 뒤 뒤 4자리만 남기고 마스킹해서 내려준다 — 원본 계좌번호 전체를
    // 응답 바디에 실어 보내지 않는다(2026-08-13, 코드리뷰 지적으로 마스킹 필드
    // accountNumberMasked 신설 + 원본 accountNumber 필드 제거).
    //
    // AccountMapper의 SELECT는 la.account_number를 account_number_encrypted로 별칭한다
    // (PR #162 리뷰 반영) — 그대로 "account_number" 키로 읽으면 컴파일은 되지만 값이
    // 항상 null이라 여기서 바로 NPE/복호화 실패로 드러나게 하기 위함이다.
    private AccountResponse toAccountResponse(Map<String, Object> a) {
        String plainAccountNumber = accountNumberCrypto.decrypt((String) a.get("account_number_encrypted"));
        return AccountResponse.builder()
                .accountId(String.valueOf(a.get("account_id")))
                .bankCode((String) a.get("bank_code"))
                .bankName((String) a.get("bank_name"))
                .accountNumberMasked(AccountNumberCrypto.mask(plainAccountNumber))
                .isPrimary(toBool(a.get("is_primary")))
                .status((String) a.get("status"))
                .build();
    }

    /**
     * TX + yyyyMMdd + UUID(하이픈 제거). 기존에는 6자리 랜덤 숫자라 하루 100만 개
     * 공간뿐이어서 하루 몇천 건만 요청돼도 PRIMARY KEY 충돌 확률이 컸다(CodeRabbit
     * 지적, 2026-08-06). transaction_id가 VARCHAR(50)이라 "TX"+8자리 날짜+32자리
     * UUID(총 42자)까지 여유롭게 들어간다.
     */
    private String generateTransactionId() {
        String datePart = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String uuidPart = UUID.randomUUID().toString().replace("-", "");
        return "TX" + datePart + uuidPart;
    }

    // MyBatis 드라이버 설정에 따라 DATETIME 컬럼이 Timestamp/LocalDateTime/Date
    // 중 무엇으로 매핑되는지 달라질 수 있어서 셋 다 방어적으로 처리한다.
    private boolean isExpired(Object requestedAt) {
        LocalDateTime requestedTime;
        if (requestedAt instanceof Timestamp) {
            requestedTime = ((Timestamp) requestedAt).toLocalDateTime();
        } else if (requestedAt instanceof LocalDateTime) {
            requestedTime = (LocalDateTime) requestedAt;
        } else if (requestedAt instanceof java.util.Date) {
            requestedTime = LocalDateTime.ofInstant(((java.util.Date) requestedAt).toInstant(), java.time.ZoneId.systemDefault());
        } else {
            return false;
        }
        return requestedTime.plusSeconds(DEPOSIT_AUTH_TIMEOUT_SECONDS).isBefore(LocalDateTime.now());
    }

    /** TINYINT(1)은 커넥터 설정에 따라 Boolean 또는 Number로 반환된다 */
    private static boolean toBool(Object value) {
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof Number) return ((Number) value).intValue() == 1;
        return false;
    }

    /** attempt_count는 드라이버 설정에 따라 Integer/Long 등으로 반환될 수 있다 */
    private static int toInt(Object value) {
        return value instanceof Number ? ((Number) value).intValue() : 0;
    }
}
