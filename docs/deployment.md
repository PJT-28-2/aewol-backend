# 배포 가이드

EC2 한 대에 Docker Compose로 MySQL·Redis·OCR·애플리케이션을 함께 띄우고, CloudFront가
정적 파일과 API를 모두 받는 구성이다.
관련 이슈: #196(컨테이너화), #198(S3 전환), #203(FileUtil 통합), #205(인프라 구성)

## 실제 구성 (ap-northeast-2)

| 리소스 | 값 |
| --- | --- |
| EC2 | `i-0b63ce3e8f7d98f9d` (m7i-flex.large, 8GB) |
| 탄력적 IP | `43.200.225.176` |
| 오리진 DNS | `ec2-43-200-225-176.ap-northeast-2.compute.amazonaws.com` |
| CloudFront | `E2AXIIBUB4L66H` / `d2wzfpczojllq5.cloudfront.net` |
| 정적 호스팅 버킷 | `aewol-prod-596617418243-ap-northeast-2-an` |
| 업로드 파일 버킷 | `aewol-uploads-prods-596617418243-ap-northeast-2-an` |
| DB 백업 버킷 | `aewol-old-backup-596617418243-ap-northeast-2-an` |
| ECR | `596617418243.dkr.ecr.ap-northeast-2.amazonaws.com/aewol-backend`, `.../aewol-ocr` |
| EC2 역할 | `aewol-ec2-role` |
| SSM 경로 | `/aewol/prod/*` |

커스텀 도메인 없이 CloudFront 기본 도메인을 쓴다. HTTPS가 자동으로 제공되어 ACM 발급이
필요 없고, QR 스캔(`getUserMedia`)·Toss·카카오 OAuth 요건을 모두 만족한다.

**m7i-flex.large를 고른 이유는 OCR이다.** 추론 시 700MB에서 1GB를 쓰는데 MySQL·Redis·앱까지
합치면 4GB로는 여유가 없다. flex 계열은 기준 성능 40퍼센트 지속에 버스트가 가능해, 짧고 굵게
CPU를 쓰는 OCR 패턴에 맞는다. 상시 구동이 아니라 필요할 때만 켜서 비용을 통제한다.

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
2. OCR 이미지는 `onnxruntime`·`opencv` 설치에 메모리를 크게 쓴다. 작은 인스턴스에서는
   빌드가 실패한다(m7i-flex.large의 8GB에서는 문제없이 된다).

> **예외**: 최초 부트스트랩 1회는 EC2에서 직접 빌드했다. ECR·OIDC 설정을 뒤로 미뤄
> 검증 대상을 줄이기 위해서다. 시크릿 유출 위험은 없다 — `git archive`로 만든 소스에는
> 추적 파일만 들어가므로 gitignore된 `application-local.yml`이 구조적으로 포함될 수 없다.
> #206에서 CD가 붙으면서 원칙대로 CI 빌드 → ECR push로 돌아왔다.

## 배포 절차

`main`에 머지되면 아래 절차를 `.github/workflows/cd.yml`이 자동으로 수행한다(→ 자동 배포).
여기 적힌 명령은 그 워크플로가 하는 일이자, 워크플로가 막혔을 때 손으로 밟는 절차이기도
하다 — `scripts/deploy.sh`는 SSM으로 불리든 손으로 실행되든 동작이 같다.

환경변수는 리포지토리나 GitHub Secrets가 아니라 **SSM Parameter Store**에 두고, EC2가
직접 받아 간다. 배포 워크플로는 명령만 전달하며 시크릿 값을 알지 못한다.

```bash
APP_IMAGE=<계정>.dkr.ecr.ap-northeast-2.amazonaws.com/aewol-backend:<sha> \
OCR_IMAGE=<계정>.dkr.ecr.ap-northeast-2.amazonaws.com/aewol-ocr:<sha> \
  /opt/aewol/scripts/fetch-env.sh
```

```bash
docker compose --env-file .env.prod -f docker-compose.prod.yml pull
docker compose --env-file .env.prod -f docker-compose.prod.yml up -d
```

`fetch-env.sh`는 `/aewol/prod` 경로의 파라미터를 `.env.prod`로 내려받고 권한을 600으로
좁힌다. 받은 항목이 비정상적으로 적으면(권한 부족·경로 오타) 실패로 끝난다 — 값이 거의
없는 `.env.prod`로 기동해 원인 모를 재시작 루프에 빠지는 것을 막기 위해서다.

이미지 태그는 배포마다 달라지므로 SSM에 두지 않고 호출 시점에 넘긴다.

기동 순서는 compose가 보장한다.

```
mysql (healthy) → migrate (정상 종료) → app
                  redis (healthy) ────┘
                  ocr-service ────────┘
```

`migrate`가 실패하면 `app`은 기동하지 않는다. 스키마가 절반만 적용된 채 애플리케이션이
올라가는 상황을 막기 위한 구성이다.

## 자동 배포 (CD)

`main` 푸시에 `.github/workflows/cd.yml`이 돈다. `workflow_dispatch`로 태그를 직접
지정해 실행하면 그 태그로 되돌아간다(롤백).

```
main push --> build (ECR push) --> [Environment 승인] --> deploy (SSM) --> 외부 헬스체크
                   |                                          |
              태그 = 커밋 SHA                 번들 전송 -> 백업 -> migrate -> app
```

### 선택의 이유

| 선택 | 대안 | 이유 |
| --- | --- | --- |
| OIDC로 AWS 인증 | 액세스 키를 GitHub Secrets에 | 장기 자격증명을 아예 만들지 않는다. 회수·교체 절차가 필요 없고, 신뢰 정책에서 리포지토리와 브랜치까지 좁힐 수 있다 |
| SSM SendCommand | SSH 접속 | GitHub Actions의 IP 대역에 22번 포트를 열 필요도, 개인키를 Secrets에 둘 필요도 없다. 인바운드 규칙이 하나도 늘지 않는다 |
| 커밋 SHA 태그만 사용 | `latest` 병행 | `latest`는 어떤 커밋인지 알 수 없어 롤백 대상으로 지정할 수 없다. 태그가 곧 커밋이면 "무엇이 돌고 있는지"에 답이 하나뿐이다 |
| compose·스크립트를 매번 함께 전송 | 호스트에 둔 파일을 그대로 사용 | 이미지만 새것이고 compose는 옛날 것인 상태가 생기지 않는다. 호스트 파일이 배포 커밋과 항상 일치한다 |
| 시크릿은 SSM Parameter Store | GitHub Secrets | 워크플로가 값을 알지 못하므로 로그에 찍힐 경로 자체가 없다. 값은 EC2가 직접 받아 간다 |
| 마이그레이션 직전 백업 | 하루 1회 cron 백업만 | DB가 앱과 같은 인스턴스에 있어 복제본이 없다. 스키마 변경이 데이터를 망가뜨리면 최대 24시간을 잃는다 |
| 자동 롤백 없음 | 헬스체크 실패 시 이전 이미지로 복귀 | 그 시점엔 마이그레이션이 이미 적용돼 있다. Flyway는 forward-only이므로 이미지만 되돌리면 **예전 코드가 새 스키마 위에서 도는 더 나쁜 상태**가 된다. 되돌릴지는 변경 내용을 아는 사람이 정한다 |
| `concurrency`로 배포 직렬화 | 기본값(동시 실행) | 두 배포가 같은 호스트에서 부딪히면 마이그레이션만 적용되고 앱은 교체되지 않은 상태가 남을 수 있다. 앞선 배포를 취소하지 않고 줄을 세운다 |

### 최초 1회 AWS 설정

**1. GitHub OIDC 자격증명 공급자**

```bash
aws iam create-open-id-connect-provider   --url https://token.actions.githubusercontent.com   --client-id-list sts.amazonaws.com
```

**2. Actions가 맡을 역할** — 신뢰 정책에서 `sub`로 리포지토리와 브랜치를 못박는다. 이
조건이 없으면 **다른 리포지토리의 워크플로도 이 역할을 맡을 수 있다.**

```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Principal": { "Federated": "arn:aws:iam::596617418243:oidc-provider/token.actions.githubusercontent.com" },
    "Action": "sts:AssumeRoleWithWebIdentity",
    "Condition": {
      "StringEquals": { "token.actions.githubusercontent.com:aud": "sts.amazonaws.com" },
      "StringLike": {
        "token.actions.githubusercontent.com:sub": [
          "repo:PJT-28-2/aewol-backend:ref:refs/heads/main",
          "repo:PJT-28-2/aewol-backend:environment:production"
        ]
      }
    }
  }]
}
```

두 개인 이유: `build` 잡은 브랜치 컨텍스트(`ref:...`)로, `deploy` 잡은 Environment를
쓰므로 `environment:production`으로 토큰이 발급된다.

**3. 그 역할의 권한** — ECR push와 SSM SendCommand만 준다.

```json
{
  "Version": "2012-10-17",
  "Statement": [
    { "Sid": "EcrAuth", "Effect": "Allow", "Action": "ecr:GetAuthorizationToken", "Resource": "*" },
    { "Sid": "EcrPush", "Effect": "Allow",
      "Action": ["ecr:BatchCheckLayerAvailability", "ecr:InitiateLayerUpload",
                 "ecr:UploadLayerPart", "ecr:CompleteLayerUpload", "ecr:PutImage",
                 "ecr:BatchGetImage", "ecr:GetDownloadUrlForLayer", "ecr:DescribeImages"],
      "Resource": ["arn:aws:ecr:ap-northeast-2:596617418243:repository/aewol-backend",
                   "arn:aws:ecr:ap-northeast-2:596617418243:repository/aewol-ocr"] },
    { "Sid": "SsmSend", "Effect": "Allow", "Action": "ssm:SendCommand",
      "Resource": ["arn:aws:ec2:ap-northeast-2:596617418243:instance/i-0b63ce3e8f7d98f9d",
                   "arn:aws:ssm:ap-northeast-2::document/AWS-RunShellScript"] },
    { "Sid": "SsmRead", "Effect": "Allow",
      "Action": ["ssm:GetCommandInvocation", "ssm:ListCommandInvocations"], "Resource": "*" }
  ]
}
```

`ssm:SendCommand`의 리소스에 **인스턴스와 문서를 모두** 적어야 한다. 하나만 적으면
AccessDenied가 난다.

**4. GitHub Environment `production`** — 리포지토리 Settings > Environments에서 만든다.
`Deployment branches and tags`를 `Selected branches and tags`로 바꾸고 `main`만 추가한다.
다른 브랜치에서는 이 environment를 쓰는 잡이 실행되지 않는다.

> **승인 게이트는 걸지 못했다.** Environment의 보호 규칙(Required reviewers)은 private
> 리포지토리에서 GitHub Pro/Team/Enterprise 기능이라, 무료 플랜에서는 설정 화면에
> 항목 자체가 나오지 않는다. `environment: production` 선언은 그대로 두는데, 배포 이력이
> Environments 탭에 남고 OIDC 토큰의 `sub`가 `environment:production`으로 발급되는 것은
> 플랜과 무관하게 동작하기 때문이다(신뢰 정책이 그 값을 기대한다).
>
> 대신 실질적인 검토 지점은 PR 리뷰다 — 배포는 `main` 푸시에만 걸려 있고 `main`은
> PR로만 갱신한다. 리포지토리를 public으로 전환하면 보호 규칙이 열리지만, 과거 커밋이
> 전부 공개되므로 히스토리 점검이 선행되어야 한다.

### 롤백

Actions > Backend CD > Run workflow > `image_tag`에 되돌릴 태그(커밋 SHA 12자리)를 넣는다.
빌드를 건너뛰고 ECR에 이미 있는 이미지로 배포한다.

**마이그레이션이 포함된 배포는 이미지 롤백으로 되돌아가지 않는다.** 스키마는 이미 앞으로
가 있다. 이 경우 되돌리는 대신 앞으로 고치는 편이 안전하고, 데이터가 깨졌다면 배포 직전
백업(`db-backup/`)에서 복구한다.

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

### 구성

CloudFront가 정적 파일과 API를 모두 받아 처리한다. EC2 앞에 리버스 프록시를 두지 않는다.

```
사용자 ──HTTPS──▶ CloudFront
                    ├── /api/*  ──HTTP──▶ EC2:8080
                    └── /*      ────────▶ S3 (정적)
```

Nginx 설정도 인증서 갱신도 없고 ALB 비용도 들지 않는다. 대신 **CloudFront→EC2 구간이
HTTP**이므로, 인바운드 8080은 CloudFront 관리형 접두사 목록
(`com.amazonaws.global.cloudfront.origin-facing`)에서만 허용해야 한다. 이 제한이 사실상
유일한 접근 통제다.

운영 서비스라면 오리진도 HTTPS로 가야 한다. 시연 규모에서 보안그룹 제한으로 충분하다고
판단한 트레이드오프다.

`/ws` 경로는 프록시하지 않는다. 실시간 알림이 아직 구현되어 있지 않아(프론트에 연결
호출부가 없고 백엔드에도 발행부가 없다) 동작하지 않는 경로에 설정만 늘어나기 때문이다.
기능 구현 시 함께 추가한다.

커스텀 도메인 없이 CloudFront 기본 도메인(`*.cloudfront.net`)을 쓰면 인증서가 자동으로
제공되므로 ACM 발급도 필요 없다.

## 인스턴스 사양

MySQL(~400MB) + Redis + 애플리케이션(~700MB) + OCR(추론 시 700MB~1GB)을 합치면
최소 2.5GB가 필요하다. **t3.medium(4GB) 이상**을 권장하며, t2.micro·t3.small에서는
OCR 추론 중 OOM으로 컨테이너가 죽는다.

## 보안 주의

- 보안그룹 인바운드는 두 개뿐이다. 22번은 관리자 IP, 8080번은 CloudFront 관리형 접두사
  목록(`com.amazonaws.global.cloudfront.origin-facing`)에서만 허용한다.
  80·443은 쓰지 않는다(CloudFront가 8080으로 붙는다).
- DB·Redis·OCR은 호스트 포트를 열지 않는다.
- docker의 포트 매핑은 iptables를 직접 조작해 호스트 방화벽(ufw 등)을 우회한다.
  compose에서 `ports`를 추가할 때는 이 점을 염두에 둔다.
- Redis에 인증이 걸려 있지 않다. compose 네트워크 밖으로 노출하게 된다면
  `requirepass` 설정이 반드시 선행되어야 한다.

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

### 레거시 경로 (#203에서 정리됨)

한동안 `FileUtil`이 로컬 디스크에 직접 쓰고 읽기만 `FileStorage.signedUrl()`을 타는
갈라진 상태였다. 운영에서는 디스크에 쓰고 S3에서 읽게 되므로 배포를 막는 조건이었다.
#203에서 `FileUtil`을 제거해 **쓰기·읽기가 모두 `FileStorage`를 통한다.**

과거에 `/uploads/...` 형식으로 저장된 기존 행은 `normalize()`가 함께 처리하므로 데이터
마이그레이션이 필요 없다. compose가 `uploads` 볼륨을 계속 마운트하는 것도 그 때문이다.

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

`scripts/backup-db.sh`가 컨테이너의 MySQL을 덤프해 S3로 올린다. 아래 cron 외에
**배포 때마다 마이그레이션 직전에도 한 번 더** 돈다(`scripts/deploy.sh`). 스키마 변경은
되돌릴 수 없으므로 하루 1회 백업만으로는 최대 24시간을 잃을 수 있다.

Amazon Linux 2023에는 cron이 기본 설치되어 있지 않다.

```bash
sudo dnf install -y cronie
sudo systemctl enable --now crond
```

```bash
sudo crontab -e
# 매일 04:00
0 4 * * * /opt/aewol/scripts/backup-db.sh >> /var/log/aewol-backup.log 2>&1
```

- 필요 환경변수: `.env.prod`의 `DB_NAME` / `DB_USERNAME` / `DB_PASSWORD` / `BACKUP_BUCKET`
- 필요 권한: 백업 버킷에 `s3:PutObject`, `s3:GetObject`, `s3:ListBucket`.
  처음에는 최소 권한 취지로 쓰기만 줬는데, **복구를 인스턴스에서 수행하므로 읽기가 필요하다.**
  인스턴스는 이미 같은 데이터를 라이브로 들고 있어 읽기 권한이 노출을 늘리지 않는 반면,
  장애 상황에서 별도 자격증명을 찾는 마찰은 실제 비용이다.
  `ListBucket`은 버킷 ARN에, 객체 권한은 `/*` ARN에 붙여야 한다
- S3 키 형식: `db-backup/YYYY/MM/aewol-YYYYMMDDTHHMMSS.sql.gz`
- 원격 보관 기간은 **S3 수명주기 규칙**으로 관리한다 (스크립트는 호스트 디스크만 정리)
- 배포 직전에도 1회 수행한다 (CD 워크플로 스텝)
- **인스턴스를 중지하면 cron도 멈춘다.** 비용 절감을 위해 껐다 켜는 운영이라면,
  끄기 전에 수동으로 한 번 돌린다

### 복구

**복구해 본 적 없는 백업은 백업이 아니다.** 최소 한 번은 아래 절차를 연습해 둔다.

```bash
aws s3 cp s3://<버킷>/db-backup/2026/08/aewol-20260818T040000.sql.gz .
```

운영 DB를 덮어쓰지 않도록 **검증용 데이터베이스에 복원**한다. 덤프에 `CREATE DATABASE`나
`USE` 문이 없어 아무 DB에나 적재할 수 있다.

```bash
docker exec -e MYSQL_PWD="$PW" aewol-mysql-prod mysql -u root -e "CREATE DATABASE aewol_restore_test CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
```

```bash
gunzip -c aewol-20260818T040000.sql.gz | docker exec -i -e MYSQL_PWD="$PW" aewol-mysql-prod mysql -u root --default-character-set=utf8mb4 aewol_restore_test
```

#### 문자셋 검증

복원 후 **한글이 온전한지 반드시 확인한다.** 이 프로젝트는 문자셋 불일치로 한글이 `?`로
저장된 이력이 있다(#126).

주의할 점이 하나 있다. **조회 클라이언트에 `--default-character-set=utf8mb4`를 빼면
데이터가 멀쩡해도 `?`로 보인다.** 실제로 이 함정에 걸려 백업이 손상된 줄 알았던 적이 있다.
표시 방식과 무관하게 판정하려면 운영 DB와 복원본을 **HEX로 비교**한다.

```bash
docker exec -e MYSQL_PWD="$PW" aewol-mysql-prod mysql -u root --default-character-set=utf8mb4 -e "SELECT bank_code, HEX(bank_name) AS live FROM aewol.bank_master ORDER BY bank_code LIMIT 3; SELECT bank_code, HEX(bank_name) AS restored FROM aewol_restore_test.bank_master ORDER BY bank_code LIMIT 3;"
```

두 HEX가 일치하면 백업은 바이트 단위로 온전하다. 검증이 끝나면 정리한다.

```bash
docker exec -e MYSQL_PWD="$PW" aewol-mysql-prod mysql -u root -e "DROP DATABASE aewol_restore_test;"
```

2026-08-19 기준 이 절차로 검증을 마쳤다 — 테이블 54개 복원, HEX 일치.

### EBS 스냅샷

`mysqldump`는 논리 백업이라 복원에 시간이 걸린다. 인스턴스 자체가 사라진 경우를 대비해
EBS 스냅샷도 함께 건다 (Data Lifecycle Manager로 자동화).
