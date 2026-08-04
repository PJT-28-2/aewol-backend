# 공동육아 · 공공지원정책 · 사회적임팩트 DB 1단계 인수인계

## 1. 작업 범위와 완료 상태

이 문서는 다음 세 담당 영역의 DB 설계와 개발·시연용 데이터 전달을 위한 문서다.

- 공동육아
- 공공지원정책
- 사회적임팩트(기부)

DB 1단계 산출물과 현재 프론트 화면용 API 구현을 완료했다. **설계 기준은 팀이 확정한 신 계보 Flyway V1~V4다** (V1 전면 개편, V2 보험 카탈로그, V3 PK BIGINT 전환, V4 계좌/문의/등록증 보완). V5는 V1~V4 위에서 실행되도록 작성했고, 모든 서로게이트 PK/FK는 BIGINT AUTO_INCREMENT다. 구 계보(`sql/schema.sql`, UUID 기준)로 쓰였던 초기 V5 원본은 `docs/db/V5_original_backup.sql`에 보관한다. 세 영역의 프론트 mock은 실제 API 호출로 교체했고 서비스 단위테스트를 함께 작성했다.

## 2. 전달 파일

| 파일 | 용도 | 실행 여부 |
| --- | --- | --- |
| `src/main/resources/db/migration/V5__create_community_support_impact_schema.sql` | 정부24 원본 캐시와 세 담당 영역의 서비스용 스키마 | 필수 |
| `docs/db/erd-community-support-impact.md` | 담당 영역 한정 ERD와 계산 필드 정의 | 문서 |
| `docs/db/seed-community-support-impact.sql` | 화면·API 개발 및 시연용 가상 데이터 | 개발·시연 DB에서만 선택 |
| `docs/db/cleanup-community-support-impact.sql` | 위 가상 데이터만 제거 | 운영 반영 전 또는 필요 시 |

시드 데이터는 Flyway 마이그레이션에 넣지 않았다. 따라서 운영 DB에는 V5만 적용하고 테스트 데이터는 제외할 수 있다.

개발·시연용 시드 계정은 다음 두 개이며 비밀번호는 모두 `test1234`다.

- `owner@example.test`: 보리 소유자, 공동육아·지원정책·기부 데이터 보유
- `family@example.test`: 공동육아 참여자

## 3. 프론트 요구사항과 DB 매핑

### 공동육아

| 프론트 기능 | 저장 또는 조회 기준 |
| --- | --- |
| 반려동물별 공동육아 그룹 | `shared_access.pet_id` |
| 이메일·전화·링크 초대 | `invite_code`, `recipient_type`, `recipient_value`, `expires_at` |
| 초대 수락 전 사용자 | `member_id IS NULL`, `status = 'PENDING'` |
| 역할 변경 및 참여 상태 | `role`, `status`, `accepted_at`, `revoked_at` |
| 구성원별 기여 금액 | `transaction.pet_id`, `transaction.member_id`, 기간 조건 집계 |
| 반려동물별 활동 내역 | `activity_log.pet_id`, `title`, `metadata` |

V1~V4 적용 상태에는 `shared_access`가 없으므로 V5가 새로 생성한다. `pet_id`는 DB와 신규 초대 API 모두 필수다. `member_id`만 초대 수락 전까지 NULL을 허용한다. `transaction`에는 `member_id`가 없으므로(V1) 기여도 집계는 `wallet_id -> wallet.member_id`로 행위자를 유도한다.

### 공공지원정책

| 프론트 기능 | 저장 또는 조회 기준 |
| --- | --- |
| 정부24 원본 보관 | V5의 `gov24_public_service*` 3개 테이블 |
| 반려동물 정책 카드 | `local_support_program`의 요약·기관·혜택·기간 필드 |
| 조건 목록과 충족 여부 | `local_support_program_condition` + 회원·반려동물 정보 비교 |
| 관심 또는 신청 페이지 이동 상태 | `support_program_interest` |
| 반려동물 관련 정책만 노출 | 큐레이션 시 `target_species`, `is_active` 지정 |

정부24 원본 전체를 서비스 화면에 직접 노출하지 않는다. API 응답은 V5의 원본 테이블에 동기화하고, 반려동물 관련 데이터만 `local_support_program`으로 큐레이션한다.

### 사회적임팩트(기부)

| 프론트 기능 | 저장 또는 조회 기준 |
| --- | --- |
| 저금통 잔액 | `wallet` (wallet_type='DONATION') — V1에서 donation_pot이 지갑으로 통합됨 |
| 이달 모인 금액 | 완료된 `donation_roundup.roundup_amount` 월 합계 |
| 기부처와 공식 기부 경로 | `donation_organization`, `donation_channel` |
| 캠페인 진행률·참여자·마감일 | `donation_campaign` 원본값으로 계산 |
| 선호 기부처 | `member_donation_preference` |
| 자투리 저금·자동 기부 설정 | `donation_setting` |
| 중복 저금 방지 | `donation_roundup.source_txn_id` UNIQUE |
| 기부 결과·영수증·중복 요청 방지 | 확장된 `donation_history` |

기존 백엔드 호환을 위해 `donation_history.recipient_name`은 유지한다. 신규 기부 저장 시 선택한 기부처 이름을 함께 기록해야 한다.

- 잔돈 적립: 매일 23:00(Asia/Seoul), 당일 결제 금액을 설정 단위로 나눈 나머지를 적립한다.
- 월말 자동 기부: 매월 말일 23:10(Asia/Seoul), 저금통 전액을 선택한 캠페인에 월 1회 기부한다.

## 4. 적용 전 확인

1. 대상 DB가 신 계보 V1~V4 적용 상태인지 `flyway_schema_history`로 확인한다 (V2가 `insurance_product_catalog`, V3가 `pk_to_long`이어야 신 계보다). 구 계보(V2 `fix_schema_and_align_api`, V3 `notification_setting_category_based`)가 적용된 DB에는 V5를 올릴 수 없고, DB를 리셋한 뒤 V1부터 재적용한다.
2. 이미 적용된 Flyway 파일은 이름이나 내용을 수정하지 않는다. 체크섬 불일치는 DB 담당자가 배포 이력을 확인한 뒤 처리하며, 임의 `repair`는 실행하지 않는다.

## 5. 적용 순서

1. `./gradlew flywayMigrate` — V1~V5가 순서대로 적용된다.
2. 개발 또는 시연 DB일 때만 `docs/db/seed-community-support-impact.sql`

시드 스크립트는 **9000번대 명시 ID**(BIGINT)와 `seed-` 접두 자연키, `example.test` 도메인만 사용하며 재실행 가능(멱등)하게 작성했다. 9000번대 ID를 업무 데이터에 사용하면 안 된다.

## 6. 적용 후 검증 SQL

```sql
-- 담당 영역 테이블 생성 여부
SELECT table_name
FROM information_schema.tables
WHERE table_schema = DATABASE()
  AND table_name IN (
    'gov24_public_service',
    'gov24_public_service_detail',
    'gov24_public_service_support_condition',
    'local_support_program_condition',
    'support_program_interest',
    'donation_organization',
    'donation_channel',
    'donation_campaign',
    'donation_setting',
    'member_donation_preference',
    'donation_roundup'
  )
ORDER BY table_name;

-- 기존 테이블 확장 컬럼 확인
SELECT table_name, column_name, is_nullable, column_type
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND (
    (table_name = 'shared_access' AND column_name IN
      ('pet_id', 'invite_code', 'recipient_type', 'recipient_value', 'accepted_at', 'expires_at', 'revoked_at', 'updated_at'))
    OR (table_name = 'transaction' AND column_name = 'pet_id')
    OR (table_name = 'activity_log' AND column_name IN ('pet_id', 'title', 'metadata'))
    OR (table_name = 'local_support_program' AND column_name IN
      ('source_service_id', 'source_type', 'summary', 'agency_name', 'benefit_summary', 'target_species', 'period_text', 'application_method', 'is_active', 'source_updated_at', 'synced_at'))
    OR (table_name = 'donation_history' AND column_name IN
      ('organization_id', 'campaign_id', 'channel_id', 'txn_id', 'status', 'receipt_url', 'idempotency_key', 'completed_at'))
  )
ORDER BY table_name, ordinal_position;

-- 시드 적용 시 영역별 최소 데이터 확인 (시드는 9000번대 명시 ID)
SELECT COUNT(*) AS shared_access_count FROM shared_access WHERE access_id BETWEEN 9000 AND 9999;
SELECT COUNT(*) AS support_program_count FROM local_support_program WHERE program_id BETWEEN 9000 AND 9999;
SELECT COUNT(*) AS donation_campaign_count FROM donation_campaign WHERE campaign_id BETWEEN 9000 AND 9999;
```

예상 시드 결과는 공유 접근 2건, 정책 2건, 기부 캠페인 3건이다.

## 7. 테스트 데이터 제거

실제 사용자 데이터나 운영 데이터 반영 전에는 `docs/db/cleanup-community-support-impact.sql`을 실행한다. 이 파일은 FK 역순으로 삭제하며 9000번대 명시 ID(BIGINT)와 `seed-` 접두 자연키 데이터만 대상으로 한다. 마지막 회원 삭제는 `example.test` 이메일 조건까지 함께 검사한다.

## 8. 구현된 API

| 영역 | 메서드와 경로 | 용도 |
| --- | --- | --- |
| 공동육아 | `GET /api/share/pets` | 소유·공유 반려동물 목록 |
| 공동육아 | `GET /api/share/{petId}/members` | 구성원·대기 초대 목록 |
| 공동육아 | `GET /api/share/contributions?petId=` | 이달 구성원별 기여도 |
| 공동육아 | `GET /api/share/logs?petId=` | 반려동물별 활동 내역 |
| 공동육아 | `POST /api/share/invite`, `POST /api/share/invite/link` | 이메일·전화·링크 초대 생성 |
| 공동육아 | `GET/POST /api/share/invites/{inviteCode}` | 초대 확인·수락 |
| 공동육아 | `PATCH /api/share/members/{memberId}/role` | 역할 변경 |
| 공동육아 | `DELETE /api/share/members/{memberId}?petId=` | 참여자 제거 |
| 지원정책 | `GET /api/support/matched?petId=` | 회원·반려동물 조건별 정책 매칭 |
| 지원정책 | `POST /api/support/{programId}/interest` | 관심·신청 페이지 이동 상태 저장 |
| 기부 | `GET /api/donation` | 잔액·월 적립액·캠페인·설정 통합 조회 |
| 기부 | `POST /api/donation` | 저금통 기부 |
| 기부 | `POST /api/donation/pot/withdraw` | 저금통에서 지갑으로 출금 |
| 기부 | `PUT /api/donation/settings` | 잔돈 적립·월말 자동 기부 설정 |
| 기부 | `GET /api/donation/history` | 기부 내역 |

정부24 원본 동기화 API·배치는 별도 외부연동 단계다. 현재 화면은 반려동물 관련 데이터로 큐레이션된 `local_support_program`을 조회한다.

## 9. 검증과 커밋 전달

백엔드의 세 서비스와 거래 반려동물 태그 단위테스트를 JUnit5·Mockito로 작성했다. 프론트는 Vue 프로덕션 빌드, 백엔드는 Gradle 컴파일과 단위테스트로 검증한다. 커밋은 사용자가 직접 다음 순서로 분리한다.

1. `feat: 기능명 구현`
2. `test: 기능명 단위테스트 추가`

기능 파일과 테스트 파일이 현재 작업 트리에 함께 있으므로 커밋할 때는 기능 파일을 먼저 `feat`, 테스트 파일을 뒤이어 `test` 커밋으로 나눈다.

## 10. 범위 밖 연동 확인 사항

프론트 전역 인증은 다른 담당 영역이다. 현재 `src/stores/auth.js`와 토큰 갱신 인터셉터가 백엔드 `ApiResponse.data`를 한 단계 풀지 않고 있고, 개발용 mock 로그인은 토큰 없이 화면만 이동한다. 이 상태에서는 인증이 필요한 실제 API가 401을 반환하므로 인증 담당자가 다음을 반영해야 한다.

- 로그인·카카오 로그인·refresh 응답에서 `response.data.data`의 토큰 저장
- 개발용 mock 로그인 대신 실제 시드 계정 로그인 또는 유효한 개발 토큰 저장

이 문서의 시드 계정은 백엔드 로그인 API 직접 테스트에도 사용할 수 있다.

## 11. 인수인계 체크리스트

- [ ] 실제 Flyway 이력과 V5 버전 충돌 여부 확인
- [ ] V5 적용
- [ ] 검증 SQL로 테이블·컬럼 확인
- [ ] 개발·시연 DB에만 시드 적용
- [ ] 인증 담당의 응답 unwrap 및 개발 로그인 수정
- [ ] 프론트 연결 API 실구동 테스트 후 시드 제거 여부 결정
- [ ] 운영 반영에는 시드·제거 SQL을 Flyway로 등록하지 않음
