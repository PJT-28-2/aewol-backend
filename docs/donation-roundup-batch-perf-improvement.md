# 잔돈 적립 배치 성능/안정성 개선

Issue #349 / 브랜치 `refactor/#349-donation-roundup-batch`. 구현 완료(2장에서 채택한 방향대로 진행). `docs/group-purchase-list-search-perf-improvement.md`와 같은 형식(문제 정의 → 해결 기술 선택 이유 → 개선 효과 → 관련 파일)을 따른다.

## 1. 문제 정의

`DonationRoundUpJob.roundUpDonations()` → `DonationServiceImpl.processDailyRoundUps()`(`DonationServiceImpl.java` 214~242행)에서 세 가지 문제를 확인했다.

### 1-1. 배치 전체가 단일 트랜잭션 — 한 건의 실패가 그날 전체를 롤백시킨다

```java
@Override
@Transactional
public int processDailyRoundUps() {
    int completedCount = 0;
    for (Map<String, Object> candidate : donationMapper.findTodayRoundUpCandidates()) {
        ...
        if (donationMapper.increasePotBalance(walletId, roundUpAmount) != 1
                || donationMapper.completeRoundUp(text(roundUp, "roundupId")) != 1) {
            throw BusinessException.conflict("잔돈 적립을 반영하지 못했습니다.");
        }
        completedCount++;
    }
    return completedCount;
}
```

`@Transactional`이 `for` 루프 전체를 감싸고 있어서, 999번째 회원까지 정상 처리되고 1000번째에서 예외가 나면 이미 처리된 999건도 전부 롤백된다. 한 회원의 엣지 케이스(동시성 문제, 데이터 이상 등)가 그날 다른 모든 회원의 적립을 막을 수 있다.

### 1-2. 회원당 `FOR UPDATE` 락이 배치 전체가 끝날 때까지 유지된다

```xml
<!-- findPotForUpdate -->
SELECT * FROM wallet WHERE member_id = #{memberId} AND wallet_type = 'DONATION' FOR UPDATE
```

`wallet_id`/`member_id`에 유니크 인덱스(`uq_wallet_member_type`)가 있어 락 범위 자체는 정확히 그 1행(테이블/갭 락 아님)이라 문제없다. 문제는 **보유 시간**이다 — 1-1과 같은 이유로 이 락이 그 회원의 처리가 끝나는 즉시가 아니라 배치 전체(`processDailyRoundUps()` 전체)가 끝날 때까지 유지된다. 배치가 도는 동안 그 회원이 저금통 출금·기부 등 다른 지갑 작업을 시도하면 배치가 끝날 때까지 대기한다.

(참고: `SchedulingConfig`(develop에 최근 반영)가 8개 스레드 풀로 **배치-배치 간** 상호 대기 문제는 이미 별도로 해결해뒀다. 그 코멘트도 정기결제·공동구매 환불의 "독립 트랜잭션" 패턴을 그대로 언급한다. 이 문서가 다루는 1-1/1-2는 그것과 다른 축 — **한 배치 내부**의 전체 롤백 범위와, **배치 대 실사용자 요청 간** 락 경합이다.)

### 1-3. 회원당 순차 쿼리 5개

`getOrCreatePotForUpdate`(`findPotByMemberId` + `findPotForUpdate`) → `insertRoundUp` → `increasePotBalance` → `completeRoundUp`. 신규 회원(저금통 미생성)이면 `insertPot`이 더해져 최대 7개. 대상이 N명이면 N×5회 DB 왕복이 발생한다.

### 데이터 규모

저장소에 일일 처리 건수에 대한 근거(시드 데이터, 문서, 이슈)가 없다. `V51__add_transaction_wallet_date_index.sql` 주석이 "잔돈 적립 배치는 `idx_txn_date`를 그대로 쓴다"고 언급하지만 이는 인덱스 선택 근거이지 건수 추정치가 아니다. 실측이 필요하면 공동구매 때처럼 시드 데이터를 직접 설계해야 한다.

---

## 2. 해결 기술 선택 이유

| | **건별 독립 트랜잭션 (추천)** | 집합 기반 쿼리 |
| --- | --- | --- |
| 해결하는 문제 | 1-1(전체 롤백), 1-2(락 보유 시간) | 1-3(라운드트립 수) |
| 구현 방식 | `DonationRoundUpJob`은 후보 조회 + for 루프만, 건별 처리는 별도 빈 `DonationRoundUpExecutor`의 `@Transactional` 메서드로 위임 | `INSERT ... SELECT ... ON DUPLICATE KEY UPDATE`로 `donation_roundup` 일괄 삽입 + `UPDATE wallet w JOIN (집계 서브쿼리) ...`로 잔액 일괄 반영 |
| 구현 리스크 | 낮음 — 이 저장소에 이미 검증된 동일 패턴 2건 존재(`GroupPurchaseRefundExecutor`, `RecurringPaymentExecutor`) | 높음 — 여러 테이블(wallet/donation_roundup)에 걸친 원자성을 새로 설계해야 하고, 기존 테스트 커버리지가 없는 새 SQL |
| 실패 격리 | 회원 단위로 격리(한 건 실패해도 나머지는 커밋) | 사실상 all-or-nothing에 가까움(집합 연산 특성상 건별 격리가 본질적으로 어려움) |
| 쿼리 수(라운드트립) | 그대로(회원당 5개) | 상수 개로 감소(N×5 → 대략 O(1)) |
| 락 보유 시간 | 배치 전체 → 회원 1건 처리 시간으로 단축 | 벌크 UPDATE 자체의 짧은 실행 시간으로 단축(방식이 다를 뿐 결과적으로도 짧음) |
| "지금 필요하다"는 근거 | 코드만 봐도 명백한 정합성/가용성 문제(1-1, 1-2) — 데이터 규모와 무관하게 항상 참 | 처리량이 실제 병목인지 뒷받침할 데이터가 없음(1절 "데이터 규모" 참고) |

### 추천: 건별 독립 트랜잭션

1. **더 심각한 문제부터 해결한다.** "한 건 실패가 전체 롤백"과 "락이 필요 이상 오래 유지된다"는 처리 속도보다 먼저 고쳐야 할 정합성·가용성 문제다. 라운드트립 수 문제(1-3)는 상대적으로 덜 급하다 — 로컬/동일 리전 DB 왕복 지연을 고려하면(왕복당 수 ms 수준) 회원 수가 수만 명 규모가 아닌 이상 LOCK_TTL(30분) 안에서 감당 가능한 수준일 가능성이 높다.
2. **이미 검증된 패턴이라 구현 리스크가 낮다.** `GroupPurchaseRefundExecutor` 클래스 주석이 이 접근을 정확히 설명한다: "배치 메서드에서 self-invocation으로 `@Transactional`을 붙이면 프록시가 적용되지 않으므로 별도 빈으로 분리... 한 후보의 실패가 같은 배치 실행의 다른 후보 처리를 롤백시키지 않는다." `RecurringPaymentExecutor`도 동일 구조다. 이 저장소의 이미 확립된 컨벤션을 그대로 따르는 것이라 리뷰하기도, 유지보수하기도 쉽다.
3. **처리량 문제는 근거 없이 미리 최적화하지 않는다.** 집합 기반 쿼리는 라운드트립을 줄이는 데는 효과적이지만, 지금 이게 실제 병목이라는 근거가 없다. 근거 없는 최적화에 "여러 테이블에 걸친 원자적 벌크 연산"이라는 더 큰 구현 리스크와 "건별 실패 격리 상실"이라는 트레이드오프를 감수할 이유가 약하다.
4. **두 방향은 배타적이지 않다.** 건별 독립 트랜잭션으로 먼저 정합성 문제를 해결해두고, 이후 실측(예: 공동구매 때처럼 `scripts/` 벤치마크 스크립트로 재현 데이터 실측)으로 라운드트립 수 자체가 병목임이 확인되면 그때 집합 기반 쿼리를 후속 개선으로 검토하면 된다.

---

## 3. 개선 효과

### 3-1. 부분 실패 시 롤백 범위 — 구조로 고정, 테스트로 검증

`DonationRoundUpTransactionBoundaryTest`(`PaymentTransactionBoundaryTest`와 동일한 리플렉션 기반 접근)로 다음을 고정했다.

- `DonationRoundUpExecutor.execute()`에 `@Transactional`이 있다.
- `DonationRoundUpJob`의 어떤 메서드에도 `@Transactional`이 없다.

Spring의 `@Transactional`은 기본 전파(REQUIRED)에서, 트랜잭션 밖의 호출자가 `@Transactional` 메서드를 호출할 때마다 매번 새 트랜잭션을 연다. 이 구조가 유지되는 한 "후보 1건 실패가 다른 후보의 이미 커밋된 처리를 롤백시키는" 것은 원리적으로 불가능하다 — 실제로 N건을 넣고 한 건을 실패시켜 나머지가 커밋되는지 실행해 확인하는 통합테스트 없이도 항상 성립한다(이 방식을 택한 이유는 위 문서의 "리팩토링 이유"가 아니라 이 프로젝트의 기존 컨벤션이다).

### 3-2. wallet 행 락 보유 시간 — 실측 완료

로컬 docker-compose MySQL(`aewol-mysql`, MySQL 8.4.11)에서 실제 `wallet`/`donation_roundup`/`transaction` 테이블에 회원 30명(전용 시드 계정, 측정 후 정리)을 만들어 두 시나리오를 실측했다(2026-08-23). `DonationRoundUpExecutor`/구 `processDailyRoundUps`와 동일한 5단계(FOR UPDATE 잠금 → 적립 기록 → 잔액 반영 → 완료 처리)를 그대로 재현했고, `FOR UPDATE` 획득 시각부터 그 트랜잭션의 COMMIT 시각까지를 락 보유 시간으로 측정했다.

| 시나리오 | 1번째 처리 회원 | 마지막(30번째) 처리 회원 | 평균 |
| --- | --- | --- | --- |
| **AS-IS 재현** (30건을 단일 트랜잭션 하나로 묶음) | 184.08ms | 6.80ms | 108.96ms |
| **TO-BE** (건별 독립 트랜잭션) | 5.74ms | 12.41ms | 13.92ms (이상치 1건 89ms 포함, 제외 시 약 11.5ms) |

AS-IS는 처리 순서가 앞설수록 락 보유 시간이 배치 나머지 전체 소요 시간에 비례해 길어진다(첫 번째 회원이 사실상 배치 전체 소요 시간만큼 자신의 지갑을 잠근 채 대기시킨다) — 회원 수(N)가 늘수록 초기 회원들의 대기 시간도 함께 늘어나는 구조다. TO-BE는 처리 순서와 무관하게 항상 그 회원 1건의 처리 시간(수 ms~십수 ms)만큼만 유지되며, N이 커져도 이 값 자체는 늘지 않는다(각 트랜잭션이 서로 독립적이므로).

### 3-3. 배치 전체 소요 시간 — 트레이드오프, 목표 아님

회원별로 트랜잭션을 새로 여는 오버헤드가 추가되어 배치 전체 소요 시간은 AS-IS보다 비슷하거나 소폭 늘어날 수 있다(위 실측에서도 TO-BE 30건 합계가 AS-IS 30건 합계보다 약간 더 걸렸다 — AS-IS는 락 보유 시간의 합이 실제 총 소요 시간과 다르지만, TO-BE는 트랜잭션 개수만큼 BEGIN/COMMIT 왕복이 늘어난다). 이건 정직하게 감수하는 트레이드오프이지 이번 개선의 목표가 아니다(목표는 정합성·가용성).

---

## 4. 구현 완료

- `DonationRoundUpExecutor` 신규(`GroupPurchaseRefundExecutor` 패턴 그대로) — `execute(candidate)`에 `@Transactional`, 기존 5쿼리 로직을 그대로 이동
- `DonationRoundUpJob`이 후보 목록을 직접 조회하고 for 루프 + try/catch로 `executor.execute(candidate)`에 위임하는 구조로 변경(`GroupPurchaseRefundJob`과 동일). 성공/스킵/오류 건수 집계 로그. 스킵은 건별 warn을 남기지 않는다 — "결제액이 저금 단위로 딱 떨어짐"이 대부분이라 정상적인 흔한 케이스이기 때문(`GroupPurchaseRefundJob`과의 의도적인 차이)
- `DonationService`/`DonationServiceImpl`에서 `processDailyRoundUps()`와 그 전용 헬퍼(`roundUpAmount()`) 완전히 제거 — 더 이상 호출되지 않는 경로를 죽은 코드로 남기지 않았다
- `insertRoundUp`을 `ON DUPLICATE KEY UPDATE source_txn_id = VALUES(source_txn_id)`(no-op 업데이트 흉내)에서 `INSERT IGNORE`로 변경(리뷰로 발견) — 이 프로젝트의 JDBC URL은 `useAffectedRows`를 명시하지 않아 MySQL Connector/J 기본값(found-rows 모드)이 적용되는데, 이 모드에서는 no-op 업데이트도 영향 행이 0이 아니라 1로 보고되어 재실행 시 중복 건을 신규 삽입으로 오인해 잔돈을 두 번 적립할 수 있었다. `INSERT IGNORE`는 커넥터 모드와 무관하게 신규 삽입=1, 중복 무시=0을 항상 보장한다
- 테스트: `DonationRoundUpExecutorTest`(8건, 정상 적립/신규 저금통/SKIPPED/중복 스킵/유효성/예외), `DonationRoundUpTransactionBoundaryTest`(4건, 트랜잭션 경계 구조 검증), `DonationRoundUpInsertIdempotencyIntegrationTest`(신규, 실 MySQL로 `insertRoundUp` 재실행 시 영향 행이 항상 0임을 검증 — H2는 커넥터별 affected-rows 해석 차이를 재현하지 못해 실 MySQL 전용 테스트로 분리), 기존 `DonationServiceImplTest`의 `processDailyRoundUps` 테스트 3건은 커버리지 손실 없이 제거

## 5. 관련 파일

- `src/main/java/com/aewol/batch/DonationRoundUpExecutor.java` (신규)
- `src/main/java/com/aewol/batch/DonationRoundUpJob.java`
- `src/main/java/com/aewol/domain/donation/service/DonationService.java`, `DonationServiceImpl.java`
- `src/main/resources/mapper/donation/DonationMapper.xml` (`findTodayRoundUpCandidates`, `findPotForUpdate`, `insertRoundUp`, `increasePotBalance`, `completeRoundUp`)
- `src/test/java/com/aewol/batch/DonationRoundUpExecutorTest.java`, `DonationRoundUpTransactionBoundaryTest.java` (신규)
- `src/test/java/com/aewol/domain/donation/mapper/DonationRoundUpInsertIdempotencyIntegrationTest.java` (신규, 실 MySQL)
- `src/test/java/com/aewol/domain/donation/service/DonationServiceImplTest.java`
- 참고 패턴: `src/main/java/com/aewol/batch/GroupPurchaseRefundJob.java`, `GroupPurchaseRefundExecutor.java`, `RecurringPaymentJob.java`, `RecurringPaymentExecutor.java`, `PaymentTransactionBoundaryTest.java`
- `src/main/java/com/aewol/batch/ScheduledJobLock.java` (동시 인스턴스 실행 방지 — 이번 개선과 무관하게 이미 해결돼 있음, 참고용)
