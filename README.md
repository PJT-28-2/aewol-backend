# 애월 (AeWol) Backend

> 반려동물 전용 전자지갑 애월의 API 및 배치 서버

[서비스 바로가기](https://www.aewol.store) · [전체 프로젝트 소개](https://github.com/PJT-28-2) · [Frontend](https://github.com/PJT-28-2/aewol-frontend)

## 소개

애월 백엔드는 인증, 반려동물, 지갑, 거래, 보험과 공동 양육 기능을 제공하는 Java 17 기반 API 서버입니다. Spring Boot가 아닌 Spring MVC 5.3과 Embedded Tomcat 9으로 구성되어 있으며, MyBatis와 MySQL을 사용합니다.

## 기술 스택

| 구분 | 기술 |
| --- | --- |
| Language | Java 17 |
| Framework | Spring MVC 5.3, Spring Security 5.8, Spring Batch 4.3 |
| Server | Embedded Tomcat 9 |
| Persistence | MyBatis 3.5, MySQL 8, Flyway 9 |
| Cache / Auth | Redis 7, JWT Refresh Token Rotation |
| Storage | AWS S3 |
| Build / Test | Gradle Kotlin DSL, JUnit 5, Mockito |
| Monitoring | Micrometer, Prometheus, Grafana |
| AI / External | RapidOCR, Gemini, OpenAI, Kakao, CODEF, TossPayments |

## 주요 도메인

- 이메일·카카오 인증과 JWT 재발급
- 회원과 반려동물·증명서 관리
- 지갑 충전·출금, QR 결제와 거래 내역
- 자동 지출 분류, 대시보드와 정기결제
- 보험 시뮬레이션, 영수증 OCR과 청구 보조
- 공동 양육과 알림
- 공동구매, 기부, 지원사업과 응급 병원 검색

## 시스템 구성

```text
AeWol Frontend
  └─ REST API / WebSocket
       └─ Nginx
            └─ Spring MVC + Embedded Tomcat
                 ├─ MySQL / MyBatis / Flyway
                 ├─ Redis
                 ├─ Spring Batch
                 └─ 외부 API 및 AWS S3
```

## 프로젝트 구조

```text
src/main/java/com/aewol/
├── AewolApplication.java  # 애플리케이션 진입점
├── batch/                 # 배치 작업
├── common/                # 공통 응답·예외·필터·유틸리티
├── config/                # Web, Security, DB, Redis 설정
├── domain/                # 도메인별 Controller·Service·Mapper·DTO
└── external/              # 외부 서비스 연동

src/main/resources/
├── application.yml
├── db/migration/          # Flyway 마이그레이션
└── mapper/                # MyBatis XML Mapper
```

## 시작하기

### 요구사항

- JDK 17
- MySQL 8
- Redis 7
- Docker 및 Docker Compose 권장

### 데이터베이스 준비

```bash
docker compose up -d
./gradlew flywayMigrate
```

상세한 초기 설정은 [SETUP.md](./SETUP.md)를 참고하세요.

### 로컬 설정

`src/main/resources/application-local.yml`을 만들고 로컬 DB, Redis, JWT 및 외부 API 설정을 입력합니다. 이 파일과 실제 비밀값은 Git에 커밋하지 않습니다.

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3307/aewol?serverTimezone=Asia/Seoul&characterEncoding=UTF-8
    username: aewol
    password: 로컬_DB_비밀번호
  data:
    redis:
      host: localhost
      port: 6379

jwt:
  secret: 256비트_이상의_로컬_시크릿
  access-token-expiry: 1800000
  refresh-token-expiry: 604800000
```

외부 연동에 필요한 Kakao, CODEF, TossPayments, Gemini, Naver 등의 값은 사용하는 기능에 맞게 추가합니다.

### 빌드 및 실행

```bash
./gradlew fatJar
java -Dspring.profiles.active=local \
  -jar build/libs/aewol-backend-0.0.1-SNAPSHOT-all.jar
```

Spring Boot 프로젝트가 아니므로 `bootRun`이 아니라 위 Fat JAR 명령 또는 `AewolApplication`의 `main` 메서드를 사용합니다.

## 테스트

```bash
./gradlew test
```

새 기능에는 JUnit 5 기반 단위 테스트를 함께 작성합니다. 외부 API는 Mockito로 격리하고, MyBatis Mapper 인터페이스를 변경할 때는 대응하는 XML도 함께 확인합니다.

프로젝트 발표 시점 기준으로 서비스·컨트롤러·Mapper·통합 테스트를 포함한 백엔드 테스트 1,449개가 통과했습니다.

## 핵심 기술과 성능 개선

- 결제 시 조건부 잔액 차감과 거래 기록을 하나의 트랜잭션으로 처리해 원자성을 보장합니다.
- Toss 충전 요청은 서버 검증, Redis `SET NX`와 DB `UNIQUE(order_id)`로 중복 반영을 방지합니다.
- OCR 외부 호출을 DB 트랜잭션 밖으로 분리해 커넥션 점유 시간을 최소화했습니다.
- 공동구매 검색에 FULLTEXT ngram 인덱스, 커서 페이지네이션과 복합 인덱스를 적용해 로컬 20만 행 기준 응답 시간을 최대 475배 단축했습니다.
- 잔돈 적립을 회원별 독립 트랜잭션으로 분리해 평균 락 보유 시간을 108.96ms에서 13.92ms로 줄이고 실패 범위를 격리했습니다.

## API 응답

API는 공통적으로 `ApiResponse<T>` 형식을 사용합니다.

```json
{
  "status": 200,
  "message": "success",
  "result": {}
}
```

<!-- TODO: 실제 운영 중인 Swagger/OpenAPI 주소를 확인한 뒤 링크를 추가하세요. -->
<!-- - API 문서: https://example.com/swagger-ui/ -->

## 브랜치와 PR

```text
main
└── develop
    ├── feat/#이슈번호-기능명
    ├── fix/#이슈번호-버그명
    └── refactor/#이슈번호-대상
```

기능 브랜치는 `develop`에서 만들고 완료 후 `develop`을 대상으로 PR을 생성합니다. 기능 구현과 대응 테스트는 같은 PR에 포함합니다.

## 관련 문서

- [로컬 환경 설정](./SETUP.md)
- [배포 및 운영](./docs/deployment.md)
- [프로젝트 설계](./docs/애월_프로젝트_설계문서.md)
- [Frontend 저장소](https://github.com/PJT-28-2/aewol-frontend)

## 주의사항

- 비밀값, 토큰, API 키와 `application-local.yml`을 커밋하지 않습니다.
- 운영 또는 공유 DB에서 `flywayClean`을 실행하지 않습니다.
- 이 프로젝트는 `javax.*` 기반이므로 라이브러리를 임의로 `jakarta.*`로 변경하지 않습니다.
