# 애월 (AeWol) — 반려동물 전자지갑 서비스 Backend

## 프로젝트 소개

애월(AeWol)은 반려동물 전용 전자지갑 서비스입니다. 반려동물의 의료비, 보험, 정기결제, 공동 양육 비용 등을 한 곳에서 관리할 수 있으며, 짜투리 금액 기부, 지자체 지원사업 매칭, 응급 병원 검색 등 반려동물 생활 전반을 지원합니다.

KB IT's Your Life 7기 팀 이파리 28-2팀 종합실무 프로젝트입니다.

---

## 기술 스택

| 구분 | 기술 | 비고 |
|------|------|------|
| Language | Java 17 | LTS |
| Framework | Spring 5.3.x | Spring MVC, Spring Security 5.8.x |
| ORM | MyBatis 3.5+ | XML Mapper |
| Auth | JWT (jjwt 0.12.x) + Redis RTR | Access 30분 / Refresh 7일 |
| Batch | Spring Batch 4.3.x | 일배치 3종 |
| Server | Embedded Tomcat 9 | Fat JAR |
| DB | MySQL 8.0 | InnoDB, utf8mb4 |
| Cache | Redis 7 | 토큰 저장, Rate Limit |
| Build | Gradle (Kotlin DSL) | fatJar 태스크 |

---

## 프로젝트 구조

```
src/main/java/com/aewol/
├── AewolApplication.java       # 메인 클래스 (임베디드 Tomcat 9)
├── config/                     # 설정
│   ├── AppConfig.java          # @ComponentScan + YAML 로딩 + @EnableScheduling
│   ├── YamlPropertySourceFactory.java  # application.yml 로딩 지원
│   ├── DataSourceConfig.java   # DataSource + MyBatis + TransactionManager
│   ├── MailConfig.java         # JavaMailSender
│   ├── RedisConfig.java        # Lettuce ConnectionFactory + RedisTemplate
│   ├── SecurityConfig.java     # Spring Security 5.8.x 필터 체인
│   ├── WebMvcConfig.java       # @EnableWebMvc + Jackson + Multipart
│   ├── WebSocketConfig.java    # STOMP + SockJS
│   ├── BatchConfig.java        # @EnableBatchProcessing
│   └── RestTemplateConfig.java # RestTemplate
├── common/
│   ├── exception/              # GlobalExceptionHandler, BusinessException
│   ├── response/               # ApiResponse<T>
│   ├── filter/                 # JwtAuthenticationFilter
│   └── util/                   # JwtUtil, FileUtil
├── domain/
│   ├── auth/                   # 인증 (회원가입, 로그인, 카카오 OAuth, RTR)
│   ├── member/                 # 회원 프로필
│   ├── pet/                    # 반려동물 CRUD + 문서 관리
│   ├── wallet/                 # 지갑 + 버킷 관리
│   ├── account/                # 연동 계좌 (CODEF)
│   ├── transaction/            # 결제 + 자동 태깅
│   ├── dashboard/              # 지출 대시보드 집계
│   ├── recurring/              # 정기결제
│   ├── insurance/              # 보험 시뮬레이션 + 청구 (Gemini Vision OCR)
│   ├── share/                  # 공동 양육
│   ├── grouppurchase/          # 공동구매
│   ├── donation/               # 짜투리 저금통 + 기부
│   ├── support/                # 지자체 지원사업 매칭
│   ├── emergency/              # 응급 병원 찾기
│   └── activity/               # 활동 로그
├── external/
│   ├── kakao/                  # 카카오 로그인 + 로컬 API
│   ├── gemini/                 # Gemini Vision API (OCR)
│   ├── codef/                  # CODEF 계좌 연동
│   ├── naver/                  # 네이버 쇼핑 API
│   ├── apms/                   # 반려동물 등록정보 조회
│   ├── smtp/                   # 이메일 인증
│   └── tosspayments/           # TossPayments
└── batch/
    ├── DashboardAggregationJob.java    # 자정 지출 집계
    ├── RecurringPaymentJob.java        # 오전 9시 정기결제
    └── DonationRoundUpJob.java         # 오후 11시 잔돈 적립

src/main/resources/
├── application.yml             # 공통 설정
├── application-local.yml       # 로컬 환경 설정 (DB, Redis, API 키)
├── application-dev.yml         # 개발 서버 설정
└── mapper/                     # MyBatis XML 매퍼 (16개 도메인)
    ├── auth/
    ├── member/
    ├── pet/
    └── ...
```

---

## 로컬 개발 환경 설정

### 사전 요구사항

- JDK 17
- MySQL 8.0
- Redis 7

### MySQL / 스키마 세팅

Docker Compose + Flyway로 관리한다. 자세한 명령어 순서는 [SETUP.md](./SETUP.md) 참고.

```bash
docker-compose up -d
./gradlew flywayMigrate
```

### 환경변수 설정

`src/main/resources/application-local.yml`을 생성하고 아래 항목을 설정합니다.

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/aewol?useSSL=false&serverTimezone=Asia/Seoul&characterEncoding=UTF-8
    username: aewol
    password: aewol1234
  redis:
    host: localhost
    port: 6379

external:
  kakao:
    client-id: {카카오 REST API 키}
    redirect-uri: http://localhost:8080/api/auth/kakao/callback
  gemini:
    api-key: {Gemini API 키}
  codef:
    client-id: {CODEF 클라이언트 ID}
    client-secret: {CODEF 클라이언트 시크릿}
  toss:
    secret-key: {TossPayments 시크릿 키}
  naver:
    client-id: {네이버 클라이언트 ID}
    client-secret: {네이버 클라이언트 시크릿}

jwt:
  secret: {JWT 시크릿 키 (256비트 이상)}
  access-expiration: 1800000
  refresh-expiration: 604800000
```

### 애플리케이션 실행

```bash
# Fat JAR 빌드 후 실행
./gradlew fatJar
java -Dspring.profiles.active=local -jar build/libs/aewol-backend-0.0.1-SNAPSHOT-all.jar
```

또는 IDE에서 `AewolApplication.java`의 main 메서드를 직접 실행합니다. 이 경우 VM 옵션에 `-Dspring.profiles.active=local`을 추가합니다.

### 한글이 깨져 보일 때

MySQL은 접속할 때 클라이언트가 요청한 문자셋을 그대로 받아준다. 그래서 문자셋을
지정하지 않은 DB 클라이언트로 붙으면 세션이 latin1로 떨어지고, 그 상태로 INSERT하면
한글이 `?`나 `ê¹€ì• ì›`처럼 저장된다. 애플리케이션은 JDBC URL에
`characterEncoding=UTF-8`이 있어 영향을 받지 않는다.

`docker-compose.yml`의 `--init-connect`가 접속 직후 세션 문자셋을 다시 덮어써서 이를
막는다. **다만 이것은 MySQL 서버 기동 옵션이라, 컨테이너를 다시 만들어야 적용된다.**
예전에 만든 컨테이너를 계속 쓰고 있다면 설정이 반영되지 않은 상태다.

```bash
docker exec aewol-mysql mysql -uaewol -paewol1234 -e "SHOW VARIABLES LIKE 'init_connect'"
```

값이 비어 있으면 컨테이너를 다시 만든다. 데이터 볼륨은 유지된다.

```bash
docker-compose up -d --force-recreate mysql
```

이미 깨진 채로 저장된 행은 이 설정으로 복구되지 않는다. 원래 바이트가 유실됐기
때문이다. 아래로 깨진 행이 남아 있는지 확인한다.

```bash
docker exec aewol-mysql mysql -uaewol -paewol1234 aewol -e "SELECT member_id, name FROM member WHERE HEX(name) REGEXP '^([0-9A-F][0-9A-F])*(C3..|C2[89].|EFBFBD)'"
```

깨진 행의 원래 바이트는 남아 있지 않으므로, 삭제부터 하지 말고 먼저 정상 백업에서
복원한다. 백업이 없는 회원 입력값은 사용자에게 다시 입력받아야 한다. 지원사업 데이터는
`GOV24_API_KEY`를 설정한 뒤 아래 관리자 동기화 API로 다시 가져온다.

> **주의:** `./gradlew flywayClean`은 특정 테이블만 비우는 명령이 아니라 DB의 전체
> 스키마와 데이터를 삭제한다. 공유·스테이징·운영 DB의 복구 절차로 절대 사용하지 않는다.
> 로컬 일회용 DB를 통째로 초기화하려는 경우에도 필요한 데이터의 백업과 삭제 범위를
> 먼저 확인한다.

### 지원사업 목록이 비어 있을 때

정부24 지원사업 27건은 `V36__seed_gov24_support_programs.sql`에 들어 있으므로
`./gradlew flywayMigrate`만 돌리면 채워진다. 그보다 앞선 시점에 DB를 만들었다면
마이그레이션을 다시 돌린다.

최신 데이터가 필요하면 `GOV24_API_KEY`를 설정한 뒤 동기화를 직접 실행한다. 이때
시드로 넣은 행은 `source_service_id` 기준으로 갱신되므로 중복이 생기지 않는다.

```bash
curl -X POST http://localhost:8080/api/admin/support/sync -H "Authorization: Bearer {관리자 토큰}"
```

---

## 브랜치 전략

| 브랜치 | 용도 |
|--------|------|
| `main` | 프로덕션 |
| `develop` | 개발 통합 |
| `feature/{기능명}` | 기능 개발 |

PR은 `feature/{기능명}` → `develop` → `main` 순서로 병합합니다.

---

## 주요 API 엔드포인트

12개 도메인, 50개 이상의 API를 제공합니다.

| 도메인 | 메서드 | 경로 | 설명 |
|--------|--------|------|------|
| 인증 | POST | `/api/auth/signup` | 이메일 회원가입 |
| 인증 | POST | `/api/auth/login` | 로그인 (JWT 발급) |
| 인증 | GET | `/api/auth/kakao/callback` | 카카오 OAuth 콜백 |
| 인증 | POST | `/api/auth/reissue` | 토큰 재발급 (RTR) |
| 반려동물 | GET | `/api/pets` | 반려동물 목록 조회 |
| 반려동물 | POST | `/api/pets` | 반려동물 등록 |
| 반려동물 | GET | `/api/pets/{petId}` | 반려동물 상세 조회 |
| 지갑 | GET | `/api/wallet` | 지갑 조회 (MAIN) |
| 지갑 | POST | `/api/wallet/deposit` | 지갑 충전 |
| 거래 | GET | `/api/transactions` | 거래 내역 조회 |
| 거래 | POST | `/api/transactions` | 거래 등록 |
| 대시보드 | GET | `/api/dashboard/summary` | 월별 지출 요약 |
| 보험 | POST | `/api/insurance/simulations` | 보험 시뮬레이터 결과 계산 |
| 보험 | GET | `/api/insurance/products` | 보험 상품 리스트 조회 |
| 보험 | POST | `/api/insurance/claims` | 보험 청구 (OCR) |
| 공동양육 | POST | `/api/share/invite` | 공동양육 초대 |
| 공동양육 | GET | `/api/share/pets` | 소유·공유 반려동물 조회 |
| 공동양육 | GET | `/api/share/{petId}/members` | 공동양육 멤버 조회 |
| 응급병원 | GET | `/api/emergency/hospitals` | 주변 응급 병원 검색 |
| 응급병원 | GET | `/api/emergency/hospitals/{hospitalId}` | 병원 상세 조회 |
| 응급병원 | POST | `/api/admin/emergency/hospitals/sync` | 동물병원 공공데이터 시딩 수동 실행 (관리자, 비동기 202) |
| 지원사업 | GET | `/api/support/matched?petId=` | 반려동물 조건별 지원사업 매칭 |
| 기부 | GET | `/api/donation` | 저금통·캠페인·설정 통합 조회 |
| 기부 | POST | `/api/donation` | 저금통 잔액 기부 |
| 기부 | PUT | `/api/donation/settings` | 잔돈 적립·월말 자동 기부 설정 |
| 정기결제 | GET | `/api/recurring` | 정기결제 목록 조회 |
| 정기결제 | POST | `/api/recurring` | 정기결제 등록 |
| 정기결제 | DELETE | `/api/recurring/{recurringId}` | 정기결제 해지 |

---

## Spring Boot 미사용 안내

이 프로젝트는 순수 Spring 5.3.x를 사용합니다. Spring Boot 의존성 없이 구성되어 있으며, 임베디드 Tomcat 9를 `AewolApplication.java`에서 직접 초기화합니다. `application.yml`은 Spring Boot의 자동 로드 기능을 사용하지 않고, `YamlPropertySourceFactory`를 통해 `@PropertySource`로 수동 로드합니다.

따라서 `./gradlew bootRun`은 사용할 수 없으며, Fat JAR 빌드 후 `java -jar` 명령으로 실행하거나 IDE에서 main 메서드를 직접 실행해야 합니다.

---

## 팀

KB IT's Your Life 7기 | 팀 이파리 | PJT 28-2팀
