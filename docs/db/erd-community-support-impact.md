# 공동육아 · 공공지원정책 · 사회적임팩트 ERD

사용자 담당 영역 세 부분만 다룬다. 설계 기준은 팀이 확정한 **신 계보 Flyway V1~V4**이며, V5가 그 위에 담당 영역 스키마를 얹는다. 모든 서로게이트 PK/FK는 **BIGINT AUTO_INCREMENT**다 (정부24 `service_id` 등 외부 자연키 제외).

`member`, `wallet`, `pet`, `transaction`, `activity_log`, `local_support_program`, `donation_organization`, `donation_history`는 기존 테이블이다. **저금통(기부함)은 별도 테이블이 아니라 `wallet`(wallet_type='DONATION') 행이다** — V1에서 donation_pot이 지갑으로 통합됐다. 이 문서에서는 담당 기능에 필요한 FK와 최소 확장만 표시한다.

```mermaid
erDiagram
    MEMBER ||--o{ WALLET : "owns (MAIN/DONATION 타입별 1개)"
    MEMBER ||--o{ PET : owns
    WALLET ||--o{ SHARED_ACCESS : grants
    PET ||--o{ SHARED_ACCESS : shared_for
    MEMBER ||--o{ SHARED_ACCESS : participates
    PET ||--o{ ACTIVITY_LOG : records
    PET ||--o{ TRANSACTION : attributed_to

    GOV24_PUBLIC_SERVICE ||--o| GOV24_PUBLIC_SERVICE_DETAIL : has
    GOV24_PUBLIC_SERVICE ||--o{ GOV24_PUBLIC_SERVICE_SUPPORT_CONDITION : has
    GOV24_PUBLIC_SERVICE ||--o| LOCAL_SUPPORT_PROGRAM : curated_as
    LOCAL_SUPPORT_PROGRAM ||--o{ LOCAL_SUPPORT_PROGRAM_CONDITION : requires
    MEMBER ||--o{ SUPPORT_PROGRAM_INTEREST : saves
    PET ||--o{ SUPPORT_PROGRAM_INTEREST : matched_for
    LOCAL_SUPPORT_PROGRAM ||--o{ SUPPORT_PROGRAM_INTEREST : tracked

    MEMBER ||--o| DONATION_SETTING : configures
    WALLET ||--o{ DONATION_ROUNDUP : "accumulates (DONATION 지갑)"
    TRANSACTION ||--o| DONATION_ROUNDUP : source
    DONATION_ORGANIZATION ||--o{ DONATION_CHANNEL : provides
    DONATION_ORGANIZATION ||--o{ DONATION_CAMPAIGN : runs
    DONATION_CHANNEL ||--o{ DONATION_CAMPAIGN : uses
    MEMBER ||--o{ MEMBER_DONATION_PREFERENCE : prefers
    DONATION_ORGANIZATION ||--o{ MEMBER_DONATION_PREFERENCE : preferred_by
    WALLET ||--o{ DONATION_HISTORY : "funds (DONATION 지갑)"
    DONATION_ORGANIZATION ||--o{ DONATION_HISTORY : receives
    DONATION_CAMPAIGN ||--o{ DONATION_HISTORY : receives
    DONATION_CHANNEL ||--o{ DONATION_HISTORY : processes
    TRANSACTION ||--o| DONATION_HISTORY : settles

    SHARED_ACCESS {
        bigint access_id PK "AUTO_INCREMENT"
        bigint wallet_id FK "소유자의 MAIN 지갑"
        bigint pet_id FK "필수"
        bigint member_id FK "초대 수락 전 NULL"
        bigint invited_by FK
        varchar invite_code UK
        varchar recipient_type "EMAIL PHONE LINK"
        varchar recipient_value
        varchar role "VIEWER MANAGER ADMIN"
        varchar status "PENDING ACCEPTED REJECTED EXPIRED REVOKED"
        datetime accepted_at
        datetime expires_at
        datetime revoked_at
    }
    GOV24_PUBLIC_SERVICE {
        varchar service_id PK "외부 자연키"
        varchar service_name
        text support_target
        text support_content
        varchar organization_name
        varchar detail_url
        datetime source_updated_at
        datetime synced_at
    }
    GOV24_PUBLIC_SERVICE_DETAIL {
        varchar service_id PK_FK
        longtext service_purpose
        longtext required_documents
        varchar online_application_url
        datetime synced_at
    }
    GOV24_PUBLIC_SERVICE_SUPPORT_CONDITION {
        varchar service_id PK_FK
        varchar condition_code PK
        varchar condition_value
    }
    LOCAL_SUPPORT_PROGRAM {
        bigint program_id PK "AUTO_INCREMENT"
        varchar source_service_id FK
        varchar source_type "MANUAL GOV24"
        varchar region
        varchar program_name
        varchar summary
        varchar agency_name
        varchar benefit_summary
        varchar period_text
        varchar apply_url
        varchar target_species "DOG CAT ALL"
        boolean is_active
        datetime synced_at
    }
    LOCAL_SUPPORT_PROGRAM_CONDITION {
        bigint program_condition_id PK "AUTO_INCREMENT"
        bigint program_id FK
        varchar condition_type
        varchar operator
        varchar condition_value
        varchar title
        varchar description
        boolean is_required
        int display_order
    }
    SUPPORT_PROGRAM_INTEREST {
        bigint interest_id PK "AUTO_INCREMENT"
        bigint member_id FK
        bigint program_id FK
        bigint pet_id FK
        varchar status "INTERESTED APPLY_PAGE_OPENED DISMISSED"
        datetime created_at
    }
    WALLET {
        bigint wallet_id PK "AUTO_INCREMENT"
        bigint member_id FK
        varchar wallet_type "MAIN DONATION — UNIQUE(member_id, wallet_type)"
        decimal balance
        datetime updated_at
    }
    DONATION_ORGANIZATION {
        bigint organization_id PK "AUTO_INCREMENT"
        varchar name
        varchar category
        varchar target_species
        varchar homepage_url
        varchar region
        json activity_tags
        boolean is_active
        int display_order
        datetime verified_at
    }
    DONATION_CHANNEL {
        bigint channel_id PK "AUTO_INCREMENT"
        bigint organization_id FK
        varchar channel_type "EXTERNAL_LINK IN_APP"
        varchar donation_type
        varchar donation_url
        boolean is_active
    }
    DONATION_CAMPAIGN {
        bigint campaign_id PK "AUTO_INCREMENT"
        bigint organization_id FK
        bigint channel_id FK
        varchar title
        varchar category
        decimal target_amount
        decimal raised_amount
        int participant_count
        datetime starts_at
        datetime ends_at
        boolean is_recommended
        boolean is_active
    }
    DONATION_SETTING {
        bigint member_id PK_FK
        boolean piggy_bank_enabled
        decimal saving_unit
        boolean auto_donate_enabled
        bigint auto_donate_organization_id FK
        bigint auto_donate_campaign_id FK
        char last_auto_donated_year_month
    }
    MEMBER_DONATION_PREFERENCE {
        bigint member_id PK_FK
        bigint organization_id PK_FK
        datetime created_at
    }
    DONATION_ROUNDUP {
        bigint roundup_id PK "AUTO_INCREMENT"
        bigint source_txn_id FK_UK
        bigint wallet_id FK "적립 대상 DONATION 지갑"
        decimal saving_unit
        decimal roundup_amount
        varchar status
        datetime completed_at
    }
    DONATION_HISTORY {
        bigint donation_id PK "AUTO_INCREMENT"
        bigint wallet_id FK "DONATION 지갑"
        bigint organization_id FK "NULL 허용"
        bigint campaign_id FK
        bigint channel_id FK
        bigint txn_id FK "외부링크 기부는 NULL"
        decimal amount
        varchar status
        varchar recipient_name
        varchar receipt_url
        varchar idempotency_key UK
        datetime created_at
        datetime completed_at
    }
```

## 담당 영역에서 수정하는 기존 테이블

| 테이블 | 수정 이유 |
| --- | --- |
| `member` | 팀 ERD 확정: `zip_code`/`address` 필수화, `address_detail` 300자 확장 |
| `activity_log` | `/share/logs?petId=` 반려동물별 활동 조회 (`pet_id`/`title`/`metadata` 추가) |
| `transaction` | 공동육아 기여도 집계용 인덱스 `(pet_id, txn_date)` 추가 |
| `donation_history` | 기부처·캠페인·채널·중복 요청 추적 컬럼 추가, `organization_id` NULL 완화 |
| `donation_organization` | 기부처 홈페이지·지역·태그·노출 상태 저장 |
| `local_support_program` | Gov24 원본을 반려동물 정책으로 큐레이션하여 화면 DTO 구성 |

## 담당 영역 신규 테이블

- 공동육아: `shared_access` 신규 생성, 기존 `activity_log`, `transaction` 확장
- 공공지원정책: Gov24 원본 3개, `local_support_program_condition`, `support_program_interest`
- 사회적임팩트: `donation_channel`, `donation_campaign`, `donation_setting`, `member_donation_preference`, `donation_roundup` 신규 생성, 기존 `donation_organization`·`donation_history` 확장

## 계산 데이터

- 공동육아 `contributions`: `transaction.pet_id` + `wallet_id -> wallet.member_id`(행위자 유도), 기간 조건으로 집계 — transaction에 member_id가 없다(V1)
- 정책 `eligible`, 조건별 `met`: 회원 주소(address 접두 비교)와 반려동물 정보로 조회 시 계산 — member에 region이 없다(V1)
- 캠페인 `progress`: `raised_amount / target_amount`
- 캠페인 `daysLeft`: `ends_at - 현재 시각`
- 캠페인 `preferred`: `member_donation_preference` 존재 여부
- 저금통 `monthlySaved`: 해당 월의 완료된 `donation_roundup.roundup_amount` 합계
- 잔돈 `roundup_amount`: 당일 결제 금액을 `saving_unit`으로 나눈 나머지
- 저금통 출금: DONATION 지갑 -> MAIN 지갑 `TRANSFER` 원장 기록 (`counter_wallet_id` 필수 CHECK)
- 월말 자동 기부: `last_auto_donated_year_month`와 idempotency key로 회원별 월 1회 보장
