# 애월 (AeWol) — Backend

> 반려동물 전용 전자지갑 서비스
> 팀 이파리 | KB IT's Your Life 7기

## 기술 스택

| 구분 | 기술 | 비고 |
|------|------|------|
| Language | Java 17 | LTS |
| Framework | Spring Boot 3.3.2 | Security, Batch, WebSocket |
| ORM/Mapper | MyBatis 3.5+ | XML + Annotation |
| Auth | JWT (jjwt) + Redis RTR | Access 30분 / Refresh 7일 |
| Database | MySQL 8.0 | InnoDB, utf8mb4 |
| Cache | Redis 7 | Token, 캐시, Rate Limit |
| API Docs | Swagger (springdoc-openapi) | `/swagger-ui.html` |
| Build | Gradle (Kotlin DSL) | |

## 프로젝트 구조

```
com.aewol
├── config/           # Security, Redis, Swagger, Batch, WebSocket, WebMvc
├── common/           # ApiResponse, BusinessException, JwtUtil, JwtFilter, FileUtil
├── domain/
│   ├── auth/         # 회원가입, 로그인, 카카오 OAuth, JWT
│   ├── member/       # 회원 CRUD
│   ├── pet/          # 반려동물 + 문서 관리
│   ├── wallet/       # 지갑 + 버킷 (카테고리별 예산)
│   ├── account/      # CODEF 연동 계좌
│   ├── transaction/  # 결제 + 자동태깅 (카카오로컬 + Gemini)
│   ├── dashboard/    # 지출 대시보드
│   ├── recurring/    # 정기결제
│   ├── insurance/    # 보험 시뮬레이션 + OCR 청구 (Gemini Vision)
│   ├── share/        # 공동 양육
│   ├── grouppurchase/# 공동구매
│   ├── donation/     # 짜투리 저금통 + 기부
│   ├── support/      # 지자체 지원사업
│   ├── emergency/    # 응급 병원 검색
│   └── activity/     # 활동 로그
├── external/         # 카카오, Gemini, CODEF, 네이버, APMS, SMTP, TossPayments
└── batch/            # 일배치 집계, 정기결제, 잔돈 올림
```

## 로컬 개발 환경

### 사전 요구사항

- JDK 17+
- Docker & Docker Compose

### 인프라 실행

```bash
docker compose up -d
```

- MySQL: `localhost:3306` (DB: aewol, User: aewol / aewol1234)
- Redis: `localhost:6379`

### DDL 실행

```bash
mysql -u aewol -paewol1234 aewol < src/main/resources/sql/schema.sql
```

### 애플리케이션 실행

```bash
./gradlew bootRun
```

- API: `http://localhost:8080`
- Swagger: `http://localhost:8080/swagger-ui.html`

## 브랜치 전략

```
main ← develop ← feature/{이슈번호}-{기능명}
```

| 브랜치 | 용도 |
|--------|------|
| `main` | 프로덕션 배포 |
| `develop` | 개발 통합 |
| `feature/*` | 기능 개발 |

## 팀

PJT-28-2 (팀 이파리)
