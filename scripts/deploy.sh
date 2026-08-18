#!/usr/bin/env bash
# EC2에서 실행되는 배포 스크립트. GitHub Actions가 SSM SendCommand로 호출한다
# (.github/workflows/cd.yml 참고). 손으로 실행해도 동작이 같도록 만들었다 —
# 워크플로가 막혔을 때 SSM 세션에서 그대로 실행할 수 있어야 한다.
#
#   수동 실행:
#     sudo APP_IMAGE=<계정>.dkr.ecr.ap-northeast-2.amazonaws.com/aewol-backend:<태그> \
#          OCR_IMAGE=<계정>.dkr.ecr.ap-northeast-2.amazonaws.com/aewol-ocr:<태그> \
#          REGISTRY=<계정>.dkr.ecr.ap-northeast-2.amazonaws.com \
#          /opt/aewol/scripts/deploy.sh
#
#   필요 권한: EC2 인스턴스 프로파일에 ECR pull + ssm:GetParametersByPath + kms:Decrypt
set -euo pipefail

APP_DIR="${APP_DIR:-/opt/aewol}"
REGION="${AWS_REGION:-ap-northeast-2}"
HEALTH_URL="${HEALTH_URL:-http://localhost:8080/api/health}"
HEALTH_RETRIES="${HEALTH_RETRIES:-40}"
HEALTH_INTERVAL="${HEALTH_INTERVAL:-5}"

: "${REGISTRY:?REGISTRY가 필요합니다}"
: "${APP_IMAGE:?APP_IMAGE가 필요합니다}"
: "${OCR_IMAGE:?OCR_IMAGE가 필요합니다}"

log() { echo "[$(date '+%F %T')] $*"; }
fail() { echo "[$(date '+%F %T')] $*" >&2; }

cd "$APP_DIR"
COMPOSE=(docker compose --env-file .env.prod -f docker-compose.prod.yml)

# 현재 돌고 있는 이미지를 먼저 기록한다. 배포가 깨졌을 때 "무엇으로 되돌릴지"를
# 사람이 알아야 하는데, 컨테이너가 이미 교체된 뒤에는 확인할 방법이 없다.
PREV_IMAGE="$(docker inspect --format '{{.Config.Image}}' aewol-app 2>/dev/null || true)"
log "현재 이미지: ${PREV_IMAGE:-(없음)}"
log "배포할 이미지: ${APP_IMAGE}"

log "ECR 로그인"
aws ecr get-login-password --region "$REGION" \
    | docker login --username AWS --password-stdin "$REGISTRY"

log "SSM에서 환경변수 내려받기"
APP_IMAGE="$APP_IMAGE" OCR_IMAGE="$OCR_IMAGE" OUT="$APP_DIR/.env.prod" ./scripts/fetch-env.sh

# 마이그레이션 직전 백업. 스키마 변경은 되돌릴 수 없고(Flyway는 forward-only), DB가
# 애플리케이션과 같은 인스턴스에 있어 별도 복제본이 없다. 실패하면 배포를 진행하지 않는다.
if [ "${SKIP_BACKUP:-0}" = "1" ]; then
    log "백업 건너뜀 (SKIP_BACKUP=1)"
elif [ -z "$(docker ps -q -f name='^aewol-mysql-prod$')" ]; then
    log "백업 건너뜀 (DB 컨테이너가 아직 없음 — 최초 배포)"
else
    log "마이그레이션 전 백업"
    ENV_FILE="$APP_DIR/.env.prod" ./scripts/backup-db.sh
fi

log "이미지 받기"
"${COMPOSE[@]}" pull

# migrate가 실패하면 app은 service_completed_successfully 조건에 걸려 기동하지 않고,
# compose가 0이 아닌 코드로 끝난다. 스키마가 절반만 적용된 채 앱이 뜨는 상황이 없다.
log "컨테이너 교체"
"${COMPOSE[@]}" up -d --remove-orphans

log "헬스체크 대기 (${HEALTH_URL})"
for i in $(seq 1 "$HEALTH_RETRIES"); do
    if curl -fsS --max-time 5 "$HEALTH_URL" >/dev/null 2>&1; then
        log "헬스체크 통과 (${i}회째)"
        # 직전 이미지는 남긴다. 롤백 대상이기 때문이다. 그보다 오래된 것만 정리한다.
        docker image prune -af --filter 'until=72h' >/dev/null 2>&1 || true
        log "배포 완료: ${APP_IMAGE}"
        exit 0
    fi
    sleep "$HEALTH_INTERVAL"
done

# 자동 롤백은 하지 않는다. 이 시점에는 마이그레이션이 이미 적용되어 있어, 이미지만
# 되돌리면 예전 코드가 새 스키마 위에서 도는 더 나쁜 상태가 된다. 어느 쪽이 안전한지는
# 변경 내용을 아는 사람이 판단해야 한다 — 워크플로의 수동 실행으로 되돌릴 수 있다.
fail "헬스체크 실패 — $((HEALTH_RETRIES * HEALTH_INTERVAL))초 안에 응답하지 않았습니다."
fail "되돌리려면 Actions에서 Backend CD를 image_tag=${PREV_IMAGE##*:} 로 수동 실행하세요.
      (직전 이미지: ${PREV_IMAGE:-확인 불가})"
fail "다만 이번 배포에 마이그레이션이 포함됐다면 이미지만 되돌리는 것은 위험합니다."
fail "----- migrate 로그 -----"
"${COMPOSE[@]}" logs --tail=30 --no-color migrate >&2 || true
fail "----- app 로그 -----"
"${COMPOSE[@]}" logs --tail=60 --no-color app >&2 || true
exit 1
