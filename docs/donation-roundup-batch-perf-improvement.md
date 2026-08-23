# 잔돈 적립 배치 성능/안정성 개선

방향성 결정 문서 — 실제 구현은 아직 착수 전이다. `docs/group-purchase-list-search-perf-improvement.md`와 같은 형식(문제 정의 → 해결 기술 선택 이유 → 기대 효과 → 관련 파일)을 따른다.

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

## 3. 기대 개선 효과 (구현 후 검증 예정)

구현 전이라 아래는 실측치가 아니라 **무엇을, 어떻게 검증할지에 대한 계획**이다. 공동구매 사례처럼 실제 구현 후 로컬 MySQL 실측으로 채운다.

| 항목 | 검증 방법 | 현재(예상) | 개선 후(예상) |
| --- | --- | --- | --- |
| 부분 실패 시 롤백 범위 | 통합 테스트: N건 중 1건을 의도적으로 실패시키고 나머지 커밋 여부 확인 | 전체 롤백 | 실패한 1건만 스킵, 나머지 커밋 |
| wallet 행 락 보유 시간 | `SHOW ENGINE INNODB STATUS` 또는 트랜잭션 시작~커밋 타임스탬프 로그로 측정 | 배치 전체 소요 시간과 동일 | 회원 1건 처리 시간(수 ms~수십 ms) |
| 배치 전체 소요 시간 | 동일 시드 데이터로 Before/After 실측 | 기준치 | **비슷하거나 소폭 증가할 수 있음** — 회원별로 트랜잭션을 새로 여는 오버헤드가 추가되기 때문. 이건 정직하게 감수하는 트레이드오프이지 이번 개선의 목표가 아니다(목표는 안정성) |

---

## 4. 구현 스케치 (미착수, 방향성만)

- `DonationRoundUpExecutor` 신규 추가(`GroupPurchaseRefundExecutor` 패턴 그대로) — `execute(candidate)` 메서드에 `@Transactional`, 기존 5쿼리 로직을 여기로 이동
- `DonationServiceImpl.processDailyRoundUps()`에서 `@Transactional` 제거, 후보 조회 + for 루프 + try/catch로 `executor.execute(candidate)` 위임 (또는 `DonationRoundUpJob`으로 이 루프 자체를 옮기는 것도 검토 — `GroupPurchaseRefundJob`이 그 구조)
- 실패 건은 로그로 남기고(`GroupPurchaseRefundJob`처럼 성공/스킵/오류 건수 집계), 배치 전체는 계속 진행
- `insertRoundUp`의 `ON DUPLICATE KEY UPDATE` 멱등성은 그대로 유지 — 독립 트랜잭션으로 바뀌어도 재실행 안전성에 영향 없음

## 5. 관련 파일

- `src/main/java/com/aewol/batch/DonationRoundUpJob.java`
- `src/main/java/com/aewol/domain/donation/service/DonationServiceImpl.java` (`processDailyRoundUps`, `getOrCreatePotForUpdate`)
- `src/main/resources/mapper/donation/DonationMapper.xml` (`findTodayRoundUpCandidates`, `findPotForUpdate`, `insertRoundUp`, `increasePotBalance`, `completeRoundUp`)
- 참고 패턴: `src/main/java/com/aewol/batch/GroupPurchaseRefundJob.java`, `GroupPurchaseRefundExecutor.java`, `RecurringPaymentJob.java`, `RecurringPaymentExecutor.java`
- `src/main/java/com/aewol/batch/ScheduledJobLock.java` (동시 인스턴스 실행 방지 — 이번 개선과 무관하게 이미 해결돼 있음, 참고용)
