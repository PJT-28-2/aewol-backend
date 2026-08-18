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
| 파일 저장 | `./uploads` | `/app/uploads` 볼륨 → 추후 S3(#198) |

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
