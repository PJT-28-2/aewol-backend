# 공동구매 목록 검색 성능 개선

Issue #325 / 브랜치 `refactor/#325-group-purchase-search-perf`. 구현은 `refactor:`/`test:` 커밋 2건으로 나뉘어 있다(CLAUDE.md 커밋 컨벤션).

## 1. 문제 정의

`GroupPurchaseMapper.xml`의 `findList` 쿼리(공동구매 목록/검색 API, `GET /api/group-purchase`)에서 세 가지 문제를 확인했다.

### 1-1. 키워드 검색 풀스캔

```xml
<if test="keyword != null and keyword != ''">
    AND product_name LIKE CONCAT('%', #{keyword}, '%')
</if>
```

`product_name`(VARCHAR(200))에 인덱스가 없고, 앞부분 와일드카드(`%키워드%`)라 인덱스를 만들어도 옵티마이저가 탈 수 없다. 키워드가 포함된 모든 목록 조회 요청이 전체 테이블 스캔을 유발한다.

### 1-2. OFFSET 페이지네이션의 딥 페이지 비용

```xml
LIMIT #{limit} OFFSET #{offset}
```

`GroupPurchaseServiceImpl.list()`에서 `offset = page * size`로 계산한다. MySQL은 `OFFSET N`을 처리할 때 앞의 N개 행을 읽고 버린 뒤에야 다음 행을 반환하므로, 페이지 번호가 커질수록 응답 시간이 선형으로 증가한다.

### 1-3. 상태 필터 없는 "전체" 탭 정렬 풀스캔

```xml
<if test="status == null or status == ''">
CASE WHEN current_quantity < target_quantity AND deadline >= NOW() THEN 0 ELSE 1 END,
</if>
deadline ASC, created_at DESC, gp_id DESC
```

정렬식에 `NOW()`가 포함되어 있어 어떤 인덱스도 이 정렬 순서를 보장할 수 없다(값이 매초 바뀌므로 함수 인덱스 대상도 될 수 없음). `status` 필터가 없는 "전체" 탭은 항상 풀스캔 후 filesort로 처리된다. `V39__add_group_purchase_list_indexes.sql`, `V42__group_purchase_deadline_sort_indexes.sql` 마이그레이션 주석에 "별도 과제"로 이미 명시돼 있던 항목이다.

### 데이터 규모

`V39` 마이그레이션 측정 기준 약 20만 행(로컬 MySQL 8). 이후 실측도 동일 조건(로컬 MySQL 8, 20만 행)에서 진행한다.

---

## 2. 해결 기술 선택 이유

| 문제 | 채택한 해결책 | 검토했으나 채택하지 않은 대안 | 채택 이유 |
| --- | --- | --- | --- |
| 키워드 LIKE 풀스캔 | MySQL FULLTEXT 인덱스 + `ngram` 파서 | ① B-Tree prefix 인덱스 — `%키워드%` 형태(포함 검색)는 접두사 인덱스로 커버 불가 ② 기본 FULLTEXT(공백 토크나이저) — 한글은 공백으로 단어가 분리되지 않아 매칭이 부정확 ③ Elasticsearch 등 외부 검색엔진 도입 — 대상 컬럼이 `product_name` 단일 컬럼이고 20만 행 규모라 별도 인프라를 둘 정도의 이득이 크지 않다고 판단 | DB 엔진(MySQL 8, InnoDB) 안에서 해결 가능하고, 별도 인프라·동기화 파이프라인이 필요 없음 |
| OFFSET 딥 페이지네이션 | 커서(keyset) 페이지네이션 (`WHERE (deadline, created_at, gp_id) < (?, ?, ?)`) | COUNT 캐싱, 페이지 번호 상한 제한 | 기존 정렬 컬럼과 동일한 `idx_gp_deadline_created`, `idx_gp_category_deadline_created` 인덱스를 그대로 재사용할 수 있어 추가 마이그레이션 없이 적용 가능. 서비스 코드에 이미 있던 `limit+1` 방식의 `hasNext` 판정과도 자연스럽게 결합됨. **프론트(`aewol-frontend`) 확인 결과 목록 화면이 숫자 페이지 버튼이 아니라 IntersectionObserver 기반 무한스크롤("더보기")이고 `page`는 단순 증가 카운터라, 커서 방식과 UX 충돌이 없음을 확인했다** |
| 전체 탭 `NOW()` 정렬 풀스캔 | 파생 컬럼(`is_urgent_active`) + 배치 갱신 | ① 함수 인덱스 — `NOW()`가 인자로 들어가 대상이 될 수 없음(사전 확인 완료) ② DB 트리거로 실시간 갱신 — 마감 시각 경과는 해당 row에 대한 쓰기 이벤트가 아니므로 트리거가 발동하지 않아 이 문제의 핵심(시간 경과에 따른 상태 전환)을 커버하지 못함. 이 프로젝트는 DB 트리거를 쓰는 컨벤션이 없고 H2(`MODE=MySQL`) 테스트 환경과도 충돌 가능 | 프론트가 `deadline`을 항상 해당 날짜 23:59:59로 전송하는 것을 코드로 확인했다(`aewol-frontend/src/views/grouppurchase/GroupPurchaseCreateStep3.vue`: ``deadline: `${deadline.value}T23:59:59`,`` — 앱 전역 규칙은 `utils/date.js`의 `getDeadlineTimestamp()`). 서버가 이를 정규화해 강제하면(`LocalDate.atTime(23,59,59)`) 상태 전환이 자정 단위로만 일어난다는 불변식이 성립한다. 따라서 매일 1회(자정 직후) `@Scheduled` 배치로 충분하며, 기존 `GroupPurchaseRefundJob`과 동일한 패턴이라 운영 부담이 적다 |

---

## 3. 정량적 수치 개선

> 아래 표는 동일 조건(로컬 MySQL 8, 20만 행, `V39` 측정과 동일 데이터셋)에서 `EXPLAIN` 및 실제 응답 시간을 측정해 채운 결과다. 2장에서 채택한 해결책을 실제로 구현·적용한 뒤 측정했다(결과는 "결과" 절 참고).
>
> 측정은 `scripts/gp-perf-bench.sh`로 자동화되어 있다(docker-compose 로컬 MySQL 대상). 20만 행 시드 → 5개 시나리오를 웜업 5회 + 측정 10회로 측정(avg/p95/min/max) → `EXPLAIN` 출력까지 한 번에 실행한다. 2장에서 채택한 해결책(FULLTEXT 인덱스, `is_urgent_active` 컬럼)이 구현되지 않은 상태에서 실행하면 AFTER 구간은 자동으로 건너뛴다 — 이후 코드가 바뀌어 이 스크립트를 재실행할 때도 마찬가지다. 끝나면 `./scripts/gp-perf-bench.sh --cleanup`으로 시드 데이터를 정리한다.

### 측정 방법

1. 로컬 MySQL 8 컨테이너에 시드 데이터 20만 행 적재 (`V39` 시드 방식과 동일 분포: 상태/카테고리/마감일 다양화)
2. 각 시나리오를 워밍업 5회 후 10회 반복 측정, 평균·p95 기록
3. `EXPLAIN`(또는 `EXPLAIN ANALYZE`)으로 실행계획의 `type`, `rows`, `Extra`(Using filesort/Using where 등) 변화를 함께 기록

### 결과

> BEFORE/AFTER 모두 2026-08-23 로컬 docker-compose MySQL(`aewol-mysql` 컨테이너, MySQL 8)에서 `scripts/gp-perf-bench.sh`로 실측했다(시드 20만 행, 워밍업 5회 + 측정 10회). 구현(V48/V49/V50 마이그레이션 + 매퍼/서비스 변경)을 실제로 적용한 뒤 재실행한 결과다.
>
> **이 표는 두 번째 정정판이다.** 리뷰로 벤치마크 스크립트 자체의 오류 세 가지를 추가로 발견해서 고쳤다:
> 1. `gp-perf-seed.sql`이 `is_urgent_active` 컬럼을 INSERT 목록에서 빠뜨려서, V49 적용 후 시드 20만 행이 전부 기본값 0으로 들어가고 있었다 — "전체 탭" 측정이 사실상 정렬 키가 상수인 자명한 케이스를 측정한 것이라 실제 운영 데이터(진행중/완료/마감실패가 섞인 분포)를 대표하지 못했다. 실제 서비스와 동일한 규칙(현재수량<목표수량 AND 마감>=현재시각)으로 채우도록 고쳤다.
> 2. `gp-perf-bench-after.sql.template`의 딥 페이지 시나리오가 `(deadline, created_at, gp_id) < (x, y, z)` 튜플 비교를 썼는데, 이건 세 컬럼 모두 오름차순 비교라 실제 매퍼의 `deadline ASC, created_at DESC, gp_id DESC` 혼합 정렬과 의미가 다르다(특히 `deadline` 비교 방향이 반대) — 실제 매퍼의 OR 전개식으로 바꿨다.
> 3. 같은 파일의 키워드 검색 시나리오 2개가 `ORDER BY CASE WHEN ... NOW() ...`(V49 이전 코드)를 그대로 쓰고 있어서, 실제 매퍼가 쓰는 `is_urgent_active DESC`와 다른 정렬식을 측정하고 있었다.
>
> (이전 정정에서는 `is_urgent_active ASC/DESC` 방향 버그와 `NATURAL LANGUAGE MODE`→`BOOLEAN MODE` 불일치를 고쳤다.) 세 가지를 모두 고치고 시드부터 다시 적재해 재측정한 값이 아래 표다. 절대 시간은 로컬 머신의 그 순간 부하에 따라 실행마다 달라질 수 있어(이번 BEFORE 수치가 이전 정정판보다 전반적으로 높게 나온 것도 그 때문으로 보인다) 배수(비율)와 `EXPLAIN` 실행계획 변화를 신뢰할 신호로 본다.

| 시나리오 | Before (응답시간, avg/p95) | After (응답시간, avg/p95) | 비고 |
| --- | --- | --- | --- |
| 키워드 검색 (`keyword` 있음, `status` 없음) | avg 1011.30ms / p95 1255.69ms | avg 180.37ms / p95 205.20ms (약 5.6배) | `type=ALL`(풀스캔) → `type=fulltext`. 매칭 후보가 전체의 약 0.7%(137행마다 1개)라 fulltext 자체 비용 + `is_urgent_active` 정렬을 위한 `Using filesort`가 남아 있지만, 테이블 크기와 무관해지는 게 핵심 — 20만 행이 200만 행이 돼도 이 비용은 거의 그대로다 |
| 키워드 검색 + 카테고리 필터 | avg 771.18ms / p95 969.72ms | avg 204.24ms / p95 266.11ms (약 3.8배) | FULLTEXT 인덱스가 우선 사용되고 category는 `Using where`로 추가 필터링됨(`possible_keys`에 `idx_gp_category_deadline_created`도 후보로 잡히지만 옵티마이저는 `ft_gp_product_name`을 선택) |
| 목록 조회 1페이지 (offset=0 / 커서 없음, status=OPEN) | avg 2.41ms / p95 5.39ms | avg 1.13ms / p95 3.24ms | 예상대로 거의 동일 — 커서 방식도 1페이지는 OFFSET 0과 같은 쿼리라 개선 여지가 없음(측정 노이즈 범위) |
| 목록 조회 딥 페이지 (offset=1000 / 그 지점 커서, size=10 기준 약 100페이지, status=OPEN) | avg 14.51ms / p95 22.48ms | avg 1.61ms / p95 4.03ms (약 9배) | offset=1000은 아직 OFFSET 비용이 크지 않은 지점인데도 이 정도 차이. offset이 더 커지거나 테이블이 커질수록 BEFORE는 계속 선형으로 증가하고 AFTER(keyset)는 거의 그대로 유지된다 |
| 전체 탭 (상태 필터 없음) | avg 442.43ms / p95 602.98ms | **avg 0.93ms / p95 1.66ms (약 475배)** | `type=ALL`(풀스캔) + `Using filesort` → `type=index`, `key=idx_gp_urgent_deadline_created`, `rows=11`, filesort 없음. V39/V42에 "별도 과제"로 남아있던 항목이 세 항목 중 가장 극적으로 개선됨. 이번엔 `is_urgent_active`가 실제 운영과 같은 분포(진행중 약 40% / 완료·마감실패 약 60%)로 채워진 데이터로 측정했다 |

### 참고 — 동일 프로젝트의 기존 측정 사례

`V39__add_group_purchase_list_indexes.sql` 마이그레이션 주석에 기록된 실측치(상태/카테고리 필터 인덱스 적용 전후, 20만 행 기준):

- 진행중 탭: 101ms → 2.7ms (20만 행 스캔 → 20행 인덱스 스캔)
- 카테고리 필터: 풀스캔 → 0.84ms

이번 개선도 동일한 형식(Before/After ms, 실행계획 변화)으로 기록해 일관성을 유지한다.

---

## 4. 프론트엔드 영향 (`aewol-frontend` 확인 결과)

세 해결책 중 API 계약이 바뀌는 건 커서 페이지네이션 하나뿐이다. 나머지 둘은 프론트 코드 변경이 필요 없다.

### 변경 불필요 — FULLTEXT 검색 (문제 1), 전체 탭 정렬 (문제 3)

- 요청 파라미터(`keyword`)와 응답 형태가 그대로 유지된다.
- `deadline` 23:59:59 정규화는 프론트가 이미 그렇게 보내고 있음을 코드로 확인했다. `GroupPurchaseCreateStep3.vue`(104~114행)에서 제출 직전 `` `${deadline.value}T23:59:59` ``로 조립하며, 이 규칙은 `utils/date.js`의 `getDeadlineTimestamp()`와 `stores/groupPurchase.js`의 `isStep2Complete()`에서도 동일하게 참조된다. 서버가 저장 시점에 같은 규칙을 강제해도 프론트 동작에는 변화가 없다.

### 변경 필요 — 커서 페이지네이션 (문제 2)

`GroupPurchaseListView.vue`를 확인한 결과 페이지네이션은 숫자 버튼이 아니라 **IntersectionObserver 기반 무한스크롤("더보기")**이고, `page`는 단순 증가 카운터(`ref(0)`)다. 임의 페이지로 점프하는 UI가 없어 커서 방식과 UX 충돌이 없다.

| 대상 | 현재 | 변경 후 |
| --- | --- | --- |
| `page` state | `const page = ref(0)`, `loadMore()`에서 `+1` | `const cursor = ref(null)`로 교체, 서버가 내려준 `nextCursor` 저장 |
| 요청 파라미터 | `groupPurchaseApi.getList({ page, size, ... })` | `page` 대신 `cursor` 전달 |
| `resetAndLoad()` | `page.value = 0` | `cursor.value = null` |
| 응답 파싱 | `hasNext`만 사용 | `hasNext` + 신규 `nextCursor` 필드 저장 |
| 경쟁 상태 방지(`requestGeneration`) | 그대로 | 변경 없음 (재사용) |
| 리스트 이어붙이기 로직 | 그대로 | 변경 없음 (재사용) |

백엔드는 커서를 `deadline`/`created_at`/`gp_id`를 그대로 노출하지 않는 불투명(opaque) 토큰 문자열로 내려주는 것을 권장한다 — 프론트는 정렬 키를 해석할 필요 없이 받은 값을 다음 요청에 그대로 실어 보내면 되고, 백엔드는 향후 정렬 기준이 바뀌어도 프론트 계약을 깨지 않는다.

관련 프론트 파일:
- `src/views/grouppurchase/GroupPurchaseListView.vue` (`page` state, `fetchPage`, `loadMore`, `resetAndLoad`)
- `src/api/groupPurchase.js` (`getList` 요청 파라미터)
- `src/views/grouppurchase/GroupPurchaseCreateStep3.vue` (deadline 조립부, 변경 불필요 — 참고용)

---

## 5. 관련 파일

**백엔드 (`aewol-backend`)**

구현(`refactor:` 커밋):
- `src/main/resources/mapper/grouppurchase/GroupPurchaseMapper.xml`
- `src/main/java/com/aewol/domain/grouppurchase/mapper/GroupPurchaseMapper.java`
- `src/main/java/com/aewol/domain/grouppurchase/service/GroupPurchaseService.java`
- `src/main/java/com/aewol/domain/grouppurchase/service/GroupPurchaseServiceImpl.java`
- `src/main/java/com/aewol/domain/grouppurchase/service/GroupPurchaseCursor.java` (신규 — 불투명 커서 인코더/디코더)
- `src/main/java/com/aewol/domain/grouppurchase/controller/GroupPurchaseController.java`
- `src/main/java/com/aewol/domain/grouppurchase/dto/GroupPurchaseListResponse.java`
- `src/main/java/com/aewol/domain/insight/service/collector/SpendingInsightCollector.java` (호출부 시그니처 변경 반영)
- `src/main/java/com/aewol/batch/GroupPurchaseUrgentFlagJob.java` (신규 — 자정 배치, `GroupPurchaseRefundJob`과 동일 패턴)
- `src/main/resources/db/migration/V48__group_purchase_fulltext_search.sql` (신규)
- `src/main/resources/db/migration/V49__group_purchase_urgent_active_flag.sql` (신규)
- `src/main/resources/db/migration/V50__group_purchase_target_quantity_check.sql` (신규 — 리뷰 반영)

테스트(`test:` 커밋):
- `src/test/java/com/aewol/domain/grouppurchase/mapper/GroupPurchaseMapperTest.java`
- `src/test/java/com/aewol/domain/grouppurchase/service/GroupPurchaseServiceImplTest.java`
- `src/test/java/com/aewol/domain/insight/service/collector/SpendingInsightCollectorTest.java`
- `src/test/java/com/aewol/domain/grouppurchase/service/GroupPurchaseCursorTest.java` (신규)
- `src/test/java/com/aewol/domain/grouppurchase/mapper/GroupPurchaseFullTextSearchIntegrationTest.java` (신규 — 실 MySQL 대상, H2는 FULLTEXT 미지원)
- `src/test/java/com/aewol/batch/GroupPurchaseUrgentFlagJobTest.java` (신규)

배경 참고(기존 파일, 이번에 수정하지 않음):
- `src/main/java/com/aewol/domain/grouppurchase/dto/GroupPurchaseCreateRequest.java` (deadline 정규화는 이 DTO가 아니라 `GroupPurchaseServiceImpl#create`에서 수행)
- `src/main/resources/db/migration/V39__add_group_purchase_list_indexes.sql`
- `src/main/resources/db/migration/V42__group_purchase_deadline_sort_indexes.sql`

벤치마크 자동화(docs/scripts 커밋):
- `scripts/gp-perf-bench.sh` (측정 오케스트레이터), `scripts/gp-perf-seed.sql`, `scripts/gp-perf-bench-core.sql`, `scripts/gp-perf-bench-before.sql`, `scripts/gp-perf-bench-after.sql.template`, `scripts/gp-perf-cleanup.sql`

**프론트엔드 (`aewol-frontend`)**
- `src/views/grouppurchase/GroupPurchaseListView.vue`
- `src/api/groupPurchase.js`
- `src/views/grouppurchase/GroupPurchaseCreateStep3.vue`
