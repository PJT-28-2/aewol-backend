# 배포 가이드

EC2 한 대에 Docker Compose로 MySQL·Redis·OCR·애플리케이션을 함께 띄우는 구성이다.
관련 이슈: #196(컨테이너화), #198(S3 전환)

## 로컬 개발은 달라지지 않는다

이 문서의 내용은 **운영 전용**이다. 로컬은 기존 방식 그대로다.

```bash
docker-compose up -d          # mysql/redis/ocr만 (앱은 IDE에서 실행)
./gradlew flywayMigrate
```

| | 로컬 | 운영 |
| --- | --- | --- |
| compose 파일 | `docker-compose.yml` | `docker-compose.prod.yml` |
| 프로파일 | `local` (기본값) | `prod` |
| 앱 실행 | IDE에서 `AewolApplication` | 컨테이너 |
| DB 주소 | `localhost:3307` | `mysql:3306` |
| 마이그레이션 | `./gradlew flywayMigrate` | `migrate` 컨테이너 (`MigrateMain`) |
| 파일 저장 | `./uploads` | S3 (`FileStorage` 경로), 그 외는 `/app/uploads` 볼륨 |

## 이미지 빌드는 CI에서만 한다

두 가지 이유가 있다.

1. `fatJar`는 `src/main/resources`를 통째로 넣기 때문에, 로컬에서 빌드하면 gitignore된
   `application-local.yml`의 실제 시크릿이 이미지에 실려 레지스트리로 새어나간다.
   CI 체크아웃에는 그 파일이 애초에 없다. (`.dockerignore`와 Dockerfile의 검증 단계가
   이중 방어선이지만, 구조적으로 막는 쪽이 우선이다.)
2. OCR 이미지는 `onnxruntime`·`opencv` 설치에 메모리를 크게 써서 작은 EC2에서는
   빌드가 실패한다.

## 배포 절차

```bash
docker compose --env-file .env.prod -f docker-compose.prod.yml pull
docker compose --env-file .env.prod -f docker-compose.prod.yml up -d
```

기동 순서는 compose가 보장한다.

```
mysql (healthy) → migrate (정상 종료) → app
                  redis (healthy) ────┘
                  ocr-service ────────┘
```

`migrate`가 실패하면 `app`은 기동하지 않는다. 스키마가 절반만 적용된 채 애플리케이션이
올라가는 상황을 막기 위한 구성이다.

## 환경변수 체크리스트

`.env.prod.example`을 복사해 채운다. 빠뜨리기 쉬운 것부터 정리한다.

### 없으면 기동에 실패하는 값

| 변수 | 비고 |
| --- | --- |
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | `migrate`, `app` 양쪽에서 사용 |
| `DB_ROOT_PASSWORD` | mysql 컨테이너 초기화용 |
| `JWT_SECRET` | 256bit 이상 |
| `ACCOUNT_ENCRYPTION_KEY` / `ACCOUNT_HASH_KEY` | 계좌번호 암호화. 기본값이 의도적으로 없다 |
| `CORS_ALLOWED_ORIGINS` | 운영 도메인 |
| `KAKAO_REDIRECT_URI` | 운영 도메인 |
| `MAIL_USERNAME` / `MAIL_PASSWORD` | Gmail 앱 비밀번호 |
| `APP_IMAGE` / `OCR_IMAGE` | CD가 커밋 SHA로 채운다 |

### 외부 콘솔에도 등록이 필요한 값

코드만 바꿔서는 동작하지 않는다. 짝을 맞춰야 한다.

- `KAKAO_REDIRECT_URI` → 카카오 개발자센터의 Redirect URI 목록
- 카카오맵 JS 키 → 플랫폼 도메인 화이트리스트 (프론트엔드)
- `TOSS_*` → Toss 성공·실패 리다이렉트 URL

## HTTPS는 선택이 아니다

- QR 스캔이 `getUserMedia`를 쓰는데 **secure context에서만 동작**한다. http로 띄우면
  QR 기능 자체가 켜지지 않는다.
- Toss 결제 SDK, 카카오 OAuth 리다이렉트도 https를 요구한다.

`app` 컨테이너는 `127.0.0.1:8080`에만 바인딩되어 있으므로, 앞단에 리버스 프록시(Nginx 또는
ALB)를 두고 인증서를 붙인다. WebSocket(`/ws`, SockJS)을 쓰므로 프록시에 `Upgrade`·
`Connection` 헤더 전달 설정이 필요하다.

## 인스턴스 사양

MySQL(~400MB) + Redis + 애플리케이션(~700MB) + OCR(추론 시 700MB~1GB)을 합치면
최소 2.5GB가 필요하다. **t3.medium(4GB) 이상**을 권장하며, t2.micro·t3.small에서는
OCR 추론 중 OOM으로 컨테이너가 죽는다.

## 보안 주의

- 보안그룹은 80·443·22만 연다. DB·Redis·OCR은 호스트 포트를 열지 않는다.
- docker의 포트 매핑은 iptables를 직접 조작해 호스트 방화벽(ufw 등)을 우회한다.
  compose에서 `ports`를 추가할 때는 이 점을 염두에 둔다.
- Redis에 인증이 걸려 있지 않다. compose 네트워크 밖으로 노출하게 된다면
  `requirepass` 설정이 반드시 선행되어야 한다.

## 백업

DB가 컨테이너 볼륨(EBS)에 있어 인스턴스를 잃으면 함께 사라진다. 최소한 아래는 확보한다.

- `mysqldump` → S3 정기 백업 (배포 직전에도 1회)
- EBS 스냅샷

## 파일 저장소 (S3)

`prod` 프로파일에서는 `FileStorage` 구현체가 `S3FileStorage`로 바뀐다. 로컬·테스트는
`LocalFileStorage`가 그대로 쓰이므로 **팀원은 AWS 계정 없이 개발할 수 있다.**

필요한 환경변수는 `S3_BUCKET`(필수), `S3_REGION`(기본 `ap-northeast-2`) 두 개다.
자격증명은 설정하지 않는다 — EC2 인스턴스 프로파일(IAM Role)을 기본 제공자 체인이 찾는다.

버킷 설정:

- 퍼블릭 액세스 **전면 차단**. 조회는 presigned URL로만 한다
- 브라우저가 presigned URL로 직접 받아가므로 **버킷 CORS 설정**이 필요하다
- **수명주기 규칙으로 불완전 멀티파트 업로드를 7일 후 삭제**한다.
  실패한 업로드 조각은 목록에 보이지 않으면서 저장 요금만 계속 발생한다
- 버저닝은 사용하지 않는다. 켠다면 비현행 버전 만료 규칙을 반드시 함께 건다

### ⚠️ 아직 남은 작업

`FileUtil`(레거시 경로)은 여전히 로컬 디스크에 저장한다. 공동구매 이미지, 1:1 문의 첨부,
반려동물 서류가 여기에 해당한다. 특히 문의 첨부와 반려동물 서류는 **쓰기는 `FileUtil`,
읽기는 `FileStorage.signedUrl()`** 로 갈라져 있어, 운영에서는 디스크에 쓰고 S3에서 읽는
상태가 된다.

**`FileUtil` 통합이 끝나기 전에는 운영 배포를 하면 안 된다.**

## 데이터베이스를 같은 EC2에 둔 이유

MySQL을 RDS로 분리하지 않고 애플리케이션과 같은 EC2에 컨테이너로 올렸다.

**판단 기준은 프로젝트 기간과 비용이다.** RDS는 자동 백업·장애조치·파라미터 튜닝을 제공하지만,
이 프로젝트는 상시 운영이 아니라 정해진 기간의 시연이 목표라 그 이점이 회수되기 전에 끝난다.

**대신 포기한 것을 명확히 한다.**

- 인스턴스를 잃으면 `mysql-data` 볼륨도 함께 사라진다
- 자동 복구 지점이 없다 — `docker compose down -v` 한 번이면 전체가 지워진다
- DB가 애플리케이션·OCR과 같은 메모리를 나눠 쓴다

그래서 **아래 백업이 이 결정의 전제 조건이다.** 백업이 돌지 않으면 이 선택은 정당화되지 않는다.

### 언제 RDS로 옮기는가

실사용 트래픽을 받기 시작하면 옮긴다. 전환 비용은 크지 않다.

- 애플리케이션은 접속 정보를 전부 환경변수(`DB_URL` / `DB_USERNAME` / `DB_PASSWORD`)로 받는다.
  `MigrateMain`도 같은 값을 읽으므로 마이그레이션 경로가 그대로 유지된다
- 실제 작업은 `DB_URL` 교체와 `docker-compose.prod.yml`에서 `mysql` 서비스·볼륨 제거가 전부다

⚠️ **단, 문자셋 설정이 함께 사라진다.** 지금은 compose의 `--character-set-server`,
`--collation-server`, `--init-connect` 세 플래그가 한글 깨짐을 막고 있는데, RDS에서는 이
플래그를 쓸 수 없고 **커스텀 파라미터 그룹**으로 같은 값을 설정해야 한다. 기본 파라미터
그룹을 그대로 쓰면 세션이 latin1로 떨어져 한글이 `?`로 저장되고 되돌릴 수 없다.
이 프로젝트는 이미 한 번 겪은 문제다(#126).

## 백업과 복구

### 백업

`scripts/backup-db.sh`가 컨테이너의 MySQL을 덤프해 S3로 올린다.

```bash
sudo crontab -e
# 매일 04:00
0 4 * * * /opt/aewol/scripts/backup-db.sh >> /var/log/aewol-backup.log 2>&1
```

- 필요 환경변수: `.env.prod`의 `DB_NAME` / `DB_USERNAME` / `DB_PASSWORD` / `BACKUP_BUCKET`
- 필요 권한: EC2 인스턴스 프로파일에 백업 버킷 `s3:PutObject`
- S3 키 형식: `db-backup/YYYY/MM/aewol-YYYYMMDDTHHMMSS.sql.gz`
- 원격 보관 기간은 **S3 수명주기 규칙**으로 관리한다 (스크립트는 호스트 디스크만 정리)
- 배포 직전에도 1회 수행한다 (CD 워크플로 스텝)

### 복구

**복구해 본 적 없는 백업은 백업이 아니다.** 최소 한 번은 아래 절차를 연습해 둔다.

```bash
aws s3 cp s3://<버킷>/db-backup/2026/08/aewol-20260818T040000.sql.gz .
```

```bash
gunzip -c aewol-20260818T040000.sql.gz | docker exec -i -e MYSQL_PWD="$DB_PASSWORD" aewol-mysql-prod mysql --user="$DB_USERNAME" --default-character-set=utf8mb4 "$DB_NAME"
```

복원 후 **한글 데이터를 조회해 문자셋을 반드시 확인한다.** `?`로 보이면 덤프나 복원 중
문자셋이 어긋난 것이므로 그 백업은 쓸 수 없다.

### EBS 스냅샷

`mysqldump`는 논리 백업이라 복원에 시간이 걸린다. 인스턴스 자체가 사라진 경우를 대비해
EBS 스냅샷도 함께 건다 (Data Lifecycle Manager로 자동화).
