# 로컬 개발 DB 환경 세팅

팀원 전원이 동일한 MySQL 스키마로 개발할 수 있도록 Docker Compose(로컬 MySQL/Redis) + Flyway(스키마 버전 관리) 조합을 사용한다.

- DB는 각자 로컬 Docker 컨테이너로 띄운다 (공유 DB 아님).
- 스키마 변경 이력은 `src/main/resources/db/migration`의 마이그레이션 파일로만 관리한다.
- 이 프로젝트는 Spring Boot 미사용 + Gradle(Kotlin DSL) 기반이라 Flyway도 Gradle 플러그인으로 수동 실행한다 (`./gradlew flywayMigrate`).

## 1. 최초 세팅

```bash
docker-compose up -d
./gradlew flywayMigrate
```

- MySQL 8, Redis 7 컨테이너가 뜨고, `aewol` DB/계정이 자동 생성된다 (DB명/계정/비번: `aewol` / `aewol` / `aewol1234`).
- 로컬에 이미 MySQL(3306)이 떠 있는 경우가 흔해 **호스트 포트는 3307로 매핑**했다 (컨테이너 내부는 3306 그대로). 값 오버라이드가 필요하면 `.env.example`을 `.env`로 복사해서 수정한다.
- `flywayMigrate`가 `V1__init_schema.sql`부터 순서대로 적용한다.
- **프로파일 설정 (중요):** `local` 프로파일로 실행하려면 먼저 `application-local.yml`을 만들어야 한다. `application-local.yml.example`을 복사해서 만들고 비밀값을 채운다 (git에 커밋되지 않는 파일). 이 파일이 없으면 서버가 시작 단계에서 명확한 설정 파일 오류와 함께 종료된다.
  ```bash
  cp src/main/resources/application-local.yml.example src/main/resources/application-local.yml
  ```

  이미 `application-local.yml`을 만들어 둔 사람도 예제 파일이 바뀌면 다시 비교해야 한다.
  예를 들어 계좌 암호화 키(`security.account.*`)는 2026-08-13에 추가돼서, 그 전에 만든
  파일에는 항목 자체가 없다. 빠져 있으면 기동 시 `AccountNumberCrypto` 빈 생성에서 멈춘다.

  ```bash
  # 두 값 모두 32바이트(base64 인코딩 전) 이상이어야 한다
  openssl rand -base64 32   # security.account.encryption-key
  openssl rand -base64 32   # security.account.hash-key
  ```

- 개인 설정 없이 docker-compose 기본값으로 바로 띄우려면 `-Dspring.profiles.active=dev`를 사용해도 된다 (`application-dev.yml`의 기본값이 위 계정/포트와 일치).
- 공통 `application.yml`에 더해 활성 프로파일과 같은 이름의 설정 파일 하나만 로드된다. 예를 들어 `dev`는 `application-dev.yml`, 테스트의 `test`는 `application-test.yml`을 사용한다.

## 2. 일상 개발

- 컨테이너는 계속 켜둔 채로 작업한다 (`docker-compose down` 하지 않음).
- 컨테이너를 껐다 다시 켜도 named volume(`mysql-data`)에 데이터가 남아있다.

## 3. 스키마 변경 시

1. `src/main/resources/db/migration/`에 새 버전 파일을 추가한다.
   - 네이밍: `V{n}__{설명}.sql` (언더스코어 2개), 예: `V2__add_pet_weight_history.sql`
   - **이미 적용된(커밋되어 팀원과 공유된) 마이그레이션 파일은 절대 수정하지 않는다.** 수정이 필요하면 새 버전 파일로 고친다. Flyway가 체크섬으로 기존 파일 변경을 감지하면 `flywayMigrate`가 실패한다.
2. 로컬에서 `./gradlew flywayMigrate`로 정상 적용되는지 확인한다.
3. git push.
4. 팀원은 pull 후 `./gradlew flywayMigrate`만 실행하면 로컬 DB가 동기화된다.

## 4. 리셋 (스키마를 처음부터 다시 적용하고 싶을 때)

```bash
docker-compose down -v
docker-compose up -d
./gradlew flywayMigrate
```

`-v`가 named volume까지 삭제하므로 로컬 데이터가 전부 사라진다.

## 5. 관련 파일

| 파일 | 역할 |
|---|---|
| `docker-compose.yml` | 로컬 MySQL 8 / Redis 7 컨테이너 정의 |
| `.env.example` | docker-compose 환경변수 기본값 (복사해서 `.env`로 사용) |
| `build.gradle.kts`의 `flyway { ... }` | Flyway 접속 정보 및 마이그레이션 위치 (`DB_URL`/`DB_USERNAME`/`DB_PASSWORD` 환경변수로 오버라이드 가능) |
| `src/main/resources/db/migration/V1__init_schema.sql` | 최초 스키마 (기존 `src/main/resources/sql/schema.sql` 기반) |
| `src/main/java/com/aewol/config/DataSourceConfig.java` | DataSource / MyBatis SqlSessionFactory·SqlSessionTemplate / TransactionManager 빈 |

> 기존 `src/main/resources/sql/schema.sql`은 참고용으로 남겨두되, 앞으로 스키마 변경은 Flyway 마이그레이션 파일로만 한다.
