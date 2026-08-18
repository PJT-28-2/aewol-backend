#!/usr/bin/env bash
# 운영 DB를 S3로 백업한다. EC2 호스트에서 cron으로 실행한다.
#
# DB를 애플리케이션과 같은 EC2에 컨테이너로 두기로 한 결정(docs/deployment.md 참고)의
# 전제 조건이다. 인스턴스를 잃으면 mysql-data 볼륨도 함께 사라지므로 이 백업이 유일한
# 복구 지점이다. 백업이 돌지 않으면 그 결정 자체가 성립하지 않는다.
#
#   설치:
#     sudo crontab -e
#     0 4 * * * /opt/aewol/scripts/backup-db.sh >> /var/log/aewol-backup.log 2>&1
#
#   필요 권한: EC2 인스턴스 프로파일에 백업 버킷 s3:PutObject
set -euo pipefail

ENV_FILE="${ENV_FILE:-/opt/aewol/.env.prod}"
CONTAINER="${DB_CONTAINER:-aewol-mysql-prod}"
WORK_DIR="${WORK_DIR:-/var/backups/aewol}"
LOCAL_RETENTION_DAYS="${LOCAL_RETENTION_DAYS:-3}"

log() { echo "[$(date '+%F %T')] $*"; }

[ -f "$ENV_FILE" ] || { log "환경변수 파일이 없습니다: $ENV_FILE"; exit 1; }

# .env 파일을 그대로 source하면 값에 &, ;, $() 같은 셸 메타문자가 있을 때 셸 코드로
# 실행돼 버린다(예: JDBC URL의 "?a=1&b=2"가 백그라운드 실행으로 쪼개짐). 이 파일은
# SSM Parameter Store에서 받아온 값이라 내용을 통제할 수 없으므로, 셸 파싱 없이
# KEY=VALUE만 읽어 export한다.
set -a
while IFS='=' read -r key value; do
    [ -z "$key" ] && continue
    case "$key" in \#*) continue ;; esac
    printf -v "$key" '%s' "$value"
    export "$key"
done < "$ENV_FILE"
set +a

: "${DB_NAME:?DB_NAME이 필요합니다}"
: "${DB_USERNAME:?DB_USERNAME이 필요합니다}"
: "${DB_PASSWORD:?DB_PASSWORD가 필요합니다}"
: "${BACKUP_BUCKET:?BACKUP_BUCKET이 필요합니다}"

mkdir -p "$WORK_DIR"
STAMP="$(date '+%Y%m%dT%H%M%S')"
FILE="$WORK_DIR/${DB_NAME}-${STAMP}.sql.gz"

log "덤프 시작 - container=${CONTAINER} db=${DB_NAME}"

# --single-transaction : InnoDB를 잠그지 않고 일관된 스냅샷을 뜬다. 백업 중에도 서비스가 계속 돈다.
# --default-character-set=utf8mb4
#     이 프로젝트는 문자셋 불일치로 한글이 '?'로 저장된 이력이 있다(#126). 덤프·복원에서
#     같은 사고가 재현되지 않도록 양쪽 모두 명시한다.
# 비밀번호는 MYSQL_PWD로 넘긴다. 인자로 주면 호스트의 프로세스 목록에 그대로 노출된다.
docker exec -i -e MYSQL_PWD="$DB_PASSWORD" "$CONTAINER" mysqldump \
    --user="$DB_USERNAME" \
    --single-transaction --routines --triggers \
    --default-character-set=utf8mb4 \
    "$DB_NAME" | gzip -c > "$FILE"

# set -o pipefail이 mysqldump 실패는 잡아 주지만, 정상 종료하면서 빈 결과를 내는 경우는
# 걸러내지 못한다. 크기로 한 번 더 확인한다.
[ -s "$FILE" ] || { log "덤프 결과가 비어 있습니다"; rm -f "$FILE"; exit 1; }
log "덤프 완료 - $(du -h "$FILE" | cut -f1)"

KEY="db-backup/$(date '+%Y/%m')/$(basename "$FILE")"
aws s3 cp "$FILE" "s3://${BACKUP_BUCKET}/${KEY}"
log "업로드 완료 - s3://${BACKUP_BUCKET}/${KEY}"

# 원격 보관 기간은 S3 수명주기 규칙으로 관리한다. 스크립트는 호스트 디스크만 정리한다.
find "$WORK_DIR" -name "${DB_NAME}-*.sql.gz" -mtime "+${LOCAL_RETENTION_DAYS}" -delete
log "완료"
