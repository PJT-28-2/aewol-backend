# 애월 (Aewol) Backend

> 반려동물의 일상과 금융을 하나의 지갑으로 연결하는 반려동물 전용 전자지갑 API 및 배치 서버

[서비스 바로가기](https://www.aewol.store) · [전체 프로젝트 소개](https://github.com/PJT-28-2) · [Frontend](https://github.com/PJT-28-2/aewol-frontend)

## 소개

Aewol Backend는 인증, 반려동물 관리, 전자지갑, QR 결제, 지출 분석, 보험, 함께 돌보기와 공동구매 기능을 제공하는 Java 17 기반 API 서버입니다.

Spring Boot가 아닌 Spring MVC 5.3과 Embedded Tomcat 9으로 구성했으며, MyBatis와 MySQL을 기반으로 데이터를 관리합니다. Redis를 활용한 인증·중복 요청 방지, Spring Scheduling 기반 정기 작업, OCR·생성형 AI와 다양한 외부 API 연동을 지원합니다.

## 🛠️ 기술 스택

| 영역 | 기술 |
| --- | --- |
| **Core** | ![Java](https://img.shields.io/badge/Java_17-ED8B00?style=flat-square&logo=openjdk&logoColor=white) ![Spring MVC](https://img.shields.io/badge/Spring_MVC_5.3-6DB33F?style=flat-square&logo=spring&logoColor=white) ![Tomcat](https://img.shields.io/badge/Embedded_Tomcat_9-F8DC75?style=flat-square&logo=apachetomcat&logoColor=black) |
| **Auth · Scheduling** | ![Spring Security](https://img.shields.io/badge/Spring_Security_5.8-6DB33F?style=flat-square&logo=springsecurity&logoColor=white) ![Spring Scheduling](https://img.shields.io/badge/Spring_Scheduling-6DB33F?style=flat-square&logo=spring&logoColor=white) ![Spring Batch](https://img.shields.io/badge/Spring_Batch_4.3-6DB33F?style=flat-square&logo=spring&logoColor=white) ![Redis](https://img.shields.io/badge/Redis_7-DC382D?style=flat-square&logo=redis&logoColor=white) ![JWT](https://img.shields.io/badge/JWT_RTR-000000?style=flat-square&logo=jsonwebtokens&logoColor=white) |
| **Database** | ![MyBatis](https://img.shields.io/badge/MyBatis_3.5-BF0000?style=flat-square&logoColor=white) ![MySQL](https://img.shields.io/badge/MySQL_8-4479A1?style=flat-square&logo=mysql&logoColor=white) ![Flyway](https://img.shields.io/badge/Flyway_9-CC0200?style=flat-square&logo=flyway&logoColor=white) |
| **AI · OCR** | ![RapidOCR](https://img.shields.io/badge/RapidOCR-FF6F00?style=flat-square&logoColor=white) ![Gemini](https://img.shields.io/badge/Gemini-8E75B2?style=flat-square&logo=googlegemini&logoColor=white) ![OpenAI](https://img.shields.io/badge/OpenAI-412991?style=flat-square&logo=openai&logoColor=white) |
| **External API** | ![Kakao](https://img.shields.io/badge/Kakao-FFCD00?style=flat-square&logo=kakao&logoColor=black) ![CODEF](https://img.shields.io/badge/CODEF-3366FF?style=flat-square&logoColor=white) ![TossPayments](https://img.shields.io/badge/TossPayments-0064FF?style=flat-square&logoColor=white) ![Naver](https://img.shields.io/badge/Naver-03C75A?style=flat-square&logo=naver&logoColor=white) ![공공데이터](https://img.shields.io/badge/공공데이터-005BAC?style=flat-square&logoColor=white) ![Solapi](https://img.shields.io/badge/Solapi-4A65F6?style=flat-square&logoColor=white) |
| **Build · Test** | ![Gradle](https://img.shields.io/badge/Gradle_Kotlin_DSL-02303A?style=flat-square&logo=gradle&logoColor=white) ![JUnit5](https://img.shields.io/badge/JUnit_5-25A162?style=flat-square&logo=junit5&logoColor=white) ![Mockito](https://img.shields.io/badge/Mockito-78A641?style=flat-square&logoColor=white) |
| **Monitoring** | ![Micrometer](https://img.shields.io/badge/Micrometer-5C2D91?style=flat-square&logoColor=white) ![Prometheus](https://img.shields.io/badge/Prometheus-E6522C?style=flat-square&logo=prometheus&logoColor=white) ![Grafana](https://img.shields.io/badge/Grafana-F46800?style=flat-square&logo=grafana&logoColor=white) |
| **Deployment** | ![AWS](https://img.shields.io/badge/AWS_EC2_·_ECR_·_S3-FF9900?style=flat-square&logo=amazonwebservices&logoColor=white) ![Docker Compose](https://img.shields.io/badge/Docker_Compose-2496ED?style=flat-square&logo=docker&logoColor=white) ![GitHub Actions](https://img.shields.io/badge/GitHub_Actions-2088FF?style=flat-square&logo=githubactions&logoColor=white) ![OIDC](https://img.shields.io/badge/OIDC-FF7F50?style=flat-square&logoColor=white) |

## 주요 도메인

- 이메일·카카오 인증과 JWT 재발급
- 회원과 반려동물·증명서 관리
- 지갑 충전·출금, QR 결제와 거래 내역
- 자동 지출 분류, 지출 대시보드와 정기결제
- 보험 시뮬레이션, 영수증 OCR과 보험 청구 보조
- 가족 초대, 육아일기와 함께 돌보기
- 공동구매, 기부와 반려동물 생활 지원
- 지원사업 및 응급 동물병원 검색
- 알림과 고객 지원

## 시스템 구성

```text
Browser / Mobile Web
  └─ HTTPS
       └─ AWS CloudFront
            ├─ 정적 파일 ─────────────▶ AWS S3
            └─ /api/* ── HTTP ───────▶ AWS EC2
                                          └─ Docker Compose
                                               ├─ Aewol Backend :8080
                                               │    ├─ Spring MVC
                                               │    ├─ Embedded Tomcat
                                               │    ├─ Spring Security
                                               │    ├─ MyBatis / Flyway
                                               │    └─ Spring Scheduling
                                               ├─ RapidOCR Service
                                               ├─ MySQL 8
                                               ├─ Redis 7
                                               └─ Monitoring (선택)
                                                    ├─ Prometheus
                                                    └─ Grafana

Aewol Backend
  ├─ AWS S3
  ├─ Kakao / CODEF / TossPayments
  ├─ Naver / 공공데이터 / Solapi
  └─ Gemini / OpenAI / RapidOCR
```

운영 환경에는 Nginx나 ALB를 별도로 두지 않습니다. CloudFront의 `/api/*` 요청이 EC2의 8080 포트에서 실행되는 Embedded Tomcat으로 전달됩니다.

Prometheus와 Grafana는 `METRICS_TOKEN`, `GRAFANA_ADMIN_PASSWORD` 등 관련 환경변수가 설정된 경우에만 Docker Compose의 monitoring 프로파일로 실행됩니다. 외부 포트로 공개하지 않으며, 운영 확인 시 SSM 포트 포워딩을 사용합니다.

백엔드에 STOMP/SockJS 설정은 존재하지만 운영 CloudFront에서 `/ws`를 연결하지 않고 있습니다. 현재 프론트엔드 연결부와 서버 알림 발행부가 없어 실시간 알림 기능으로는 사용하지 않습니다.

## 프로젝트 구조

```text
src/
├── main/
│   ├── java/com/aewol/
│   │   ├── AewolApplication.java  # 애플리케이션 진입점
│   │   ├── batch/                 # 배치 작업
│   │   ├── common/                # 공통 응답·예외·필터·유틸리티
│   │   ├── config/                # Web, Security, DB, Redis 설정
│   │   ├── domain/                # 도메인별 Controller·Service·Mapper·DTO
│   │   └── external/              # 외부 서비스 연동
│   └── resources/
│       ├── application.yml
│       ├── application-local.yml.example
│       ├── db/migration/          # Flyway 마이그레이션
│       └── mapper/                # MyBatis XML Mapper
└── test/                          # 단위·통합 테스트
```

## 시작하기

### 요구사항

- JDK 17
- MySQL 8
- Redis 7
- Docker 및 Docker Compose 권장

### 데이터베이스 및 로컬 서비스 준비

```bash
docker compose up -d
./gradlew flywayMigrate
```

로컬 Docker Compose는 MySQL, Redis와 OCR 서비스를 실행합니다. 백엔드 애플리케이션은 IDE 또는 Fat JAR로 별도 실행합니다.

상세한 초기 설정은 [SETUP.md](./SETUP.md)를 참고하세요.

### 로컬 설정

저장소에 포함된 예시 파일을 복사해 로컬 설정 파일을 만듭니다.

```bash
cp src/main/resources/application-local.yml.example \
  src/main/resources/application-local.yml
```

생성한 `application-local.yml` 또는 실행 환경에 다음 값을 설정합니다.

- MySQL 접속 정보
- Redis 접속 정보
- JWT Secret
- 계좌번호 암호화 키와 해시 키
- Kakao, CODEF, TossPayments
- Gemini, OpenAI, RapidOCR
- Naver, 공공데이터, Solapi
- SMTP 등 사용하는 외부 연동 정보

`application-local.yml`과 실제 비밀값은 Git에 커밋하지 않습니다.

예시 파일은 로컬 Docker Compose 환경을 기준으로 다음 주소를 사용합니다.

```text
MySQL: localhost:3307
Redis: localhost:6379
OCR:   localhost:8000
```

JWT와 계좌정보 보호에 필요한 주요 환경변수는 다음과 같습니다.

```dotenv
DB_PASSWORD=
JWT_SECRET=
ACCOUNT_ENCRYPTION_KEY=
ACCOUNT_HASH_KEY=
```

`JWT_SECRET`은 256비트 이상의 값을 사용합니다. 계좌번호 암호화 키와 해시 키는 다음과 같이 생성할 수 있습니다.

```bash
openssl rand -base64 32
```

### 빌드 및 실행

```bash
./gradlew fatJar

java -Dspring.profiles.active=local \
  -jar build/libs/aewol-backend-0.0.1-SNAPSHOT-all.jar
```

Spring Boot 프로젝트가 아니므로 `bootRun`이 아니라 Fat JAR 명령 또는 `AewolApplication`의 `main` 메서드를 사용합니다.

## 테스트

```bash
./gradlew test
```

새 기능에는 JUnit 5 기반 단위 테스트를 함께 작성합니다.

외부 API는 Mockito로 격리하고, MyBatis Mapper 인터페이스를 변경할 때는 대응하는 XML Mapper와 테스트도 함께 확인합니다.

프로젝트 발표 시점 기준으로 서비스·컨트롤러·Mapper·통합 테스트를 포함한 백엔드 테스트 1,449개가 통과했습니다.

## 핵심 기술과 성능 개선

### 결제 원자성 보장

결제 시 조건부 잔액 차감과 거래 기록을 하나의 트랜잭션으로 처리해 잔액과 거래 내역의 일관성을 보장합니다.

### 중복 충전 방지

TossPayments 충전 주문은 서버가 직접 발급하고 회원·금액·상태를 데이터베이스에 저장합니다.

승인 요청 시 서버가 주문 소유자와 금액을 다시 검증하고, Redis `SET NX` 기반 클레임과 DB `UNIQUE(order_id)` 제약을 함께 적용해 동일 주문이 중복 반영되는 것을 방지합니다.

### 외부 결제 호출과 트랜잭션 분리

TossPayments 승인과 같은 외부 HTTP 호출을 DB 트랜잭션 밖에서 수행합니다. 승인 결과의 원장 기록만 짧은 독립 트랜잭션으로 처리해 외부 응답을 기다리는 동안 DB 커넥션이 점유되는 것을 방지합니다.

### OCR 트랜잭션 최적화

영수증 OCR 외부 호출을 DB 트랜잭션 밖으로 분리해 외부 응답을 기다리는 동안 데이터베이스 커넥션이 점유되는 시간을 최소화했습니다.

### 공동구매 검색 성능 개선

공동구매 검색에 FULLTEXT ngram 인덱스, 커서 페이지네이션과 복합 인덱스를 적용했습니다. 로컬 20만 행 데이터 기준 검색 응답 시간을 최대 475배 단축했습니다.

### 잔돈 적립 동시성 개선

잔돈 적립을 회원별 독립 트랜잭션으로 분리해 평균 락 보유 시간을 108.96ms에서 13.92ms로 줄였습니다. 일부 회원의 처리 실패가 전체 작업에 영향을 주지 않도록 실패 범위도 격리했습니다.

## API 응답

API는 공통적으로 `ApiResponse<T>` 형식을 사용합니다.

```json
{
  "status": 200,
  "message": "success",
  "result": {}
}
```

오류 응답에는 필요한 경우 `errorCode`가 추가됩니다.

```json
{
  "status": 400,
  "message": "잘못된 요청입니다.",
  "result": null,
  "errorCode": "INVALID_REQUEST"
}
```

Swagger UI는 local/dev 환경의 `/swagger-ui/index.html`에서 제공되며, prod 환경에서는 비활성화됩니다.

## CI/CD

### CI

`develop` 또는 `main` 브랜치에 Push하거나 해당 브랜치를 대상으로 PR을 생성하면 Backend CI가 실행됩니다.

```text
Push / Pull Request
  └─ Backend CI
       ├─ JDK 17 설정
       ├─ MySQL 8 서비스 컨테이너 실행
       ├─ Flyway 마이그레이션
       └─ ./gradlew clean build
            └─ 전체 테스트 실행
```

### CD

`main` 브랜치에 변경 사항이 반영되면 Backend CD가 실행됩니다.

```text
main 브랜치 Push
  └─ Backend CD
       ├─ AWS OIDC 인증
       ├─ Backend Docker 이미지 빌드
       │    └─ Fat JAR 생성 (-x test)
       ├─ RapidOCR Docker 이미지 빌드
       ├─ AWS ECR 이미지 Push
       └─ GitHub production Environment
            └─ AWS SSM SendCommand
                 └─ EC2 Docker Compose 배포
                      ├─ 운영 환경변수 조회
                      ├─ 데이터베이스 백업
                      ├─ Flyway 마이그레이션
                      ├─ Backend·OCR 컨테이너 교체
                      ├─ 내부 헬스체크
                      └─ CloudFront /api/health 확인
```

- GitHub Actions와 AWS는 OIDC로 인증하므로 장기 AWS Access Key를 사용하지 않습니다.
- Backend와 OCR 이미지는 커밋 SHA 기반 태그로 ECR에 저장합니다.
- 운영 비밀값은 EC2가 AWS Systems Manager Parameter Store에서 직접 가져옵니다.
- 배포 명령은 SSH 대신 AWS Systems Manager의 SSM SendCommand로 실행합니다.
- 배포 전 운영 데이터베이스를 백업하며, 최초 배포처럼 DB 컨테이너가 없는 경우에는 백업을 건너뜁니다.
- Flyway 마이그레이션에 실패하면 Backend 컨테이너를 실행하지 않습니다.
- 배포 완료 후 내부 헬스체크와 CloudFront를 통한 외부 헬스체크를 수행합니다.
- 이전 ECR 이미지 태그를 지정해 수동 재배포할 수 있지만, 마이그레이션이 적용된 배포는 스키마 호환성을 먼저 확인해야 합니다.

자세한 운영 절차는 [배포 및 운영 문서](./docs/deployment.md)를 참고하세요.

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
- Spring Boot 전용 설정이나 명령을 적용하지 않습니다.
- MyBatis Mapper 인터페이스를 변경할 때는 대응하는 XML Mapper를 함께 확인합니다.
