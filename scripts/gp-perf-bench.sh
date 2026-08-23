#!/usr/bin/env bash
# gp-perf-bench.sh — 공동구매 목록 검색 성능 측정 (docs/group-purchase-list-search-perf-improvement.md 3장)
#
# 로컬 docker-compose MySQL 컨테이너에 20만 행을 시드하고, findList의 5개 시나리오를
# 웜업 5회 + 측정 10회로 측정해 avg/p95/min/max(ms)와 EXPLAIN을 출력한다.
# "개선 후" 시나리오는 FULLTEXT 인덱스(ft_gp_product_name)와 is_urgent_active 컬럼이
# 실제로 만들어진 뒤에만 실행된다 — 둘 다 아직 구현 전이면 자동으로 건너뛴다.
#
# 사용법 (aewol-backend 디렉터리에서, docker-compose로 MySQL이 떠 있는 상태):
#   ./scripts/gp-perf-bench.sh              # 시드 확인 후 before(+ 가능하면 after) 측정
#   ./scripts/gp-perf-bench.sh --cleanup    # 시드 데이터만 지우고 종료
#
# 접속 정보는 docker-compose.yml 기본값을 따른다. 다른 값을 쓰려면 환경변수로 덮어쓴다:
#   DB_CONTAINER=aewol-mysql DB_NAME=aewol DB_USERNAME=aewol DB_PASSWORD=aewol1234
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

DB_CONTAINER="${DB_CONTAINER:-aewol-mysql}"
DB_NAME="${DB_NAME:-aewol}"
DB_USERNAME="${DB_USERNAME:-aewol}"
DB_PASSWORD="${DB_PASSWORD:-aewol1234}"
SEED_ROWS=200000

log() { echo "[$(date '+%H:%M:%S')] $*" >&2; }

mysql_run() {
    # 표준 결과 테이블 출력. 스크립트 파일을 stdin으로 흘려 DELIMITER 지시자가 먹히게 한다.
    docker exec -i -e MYSQL_PWD="$DB_PASSWORD" "$DB_CONTAINER" \
        mysql --user="$DB_USERNAME" --database="$DB_NAME" --table "$@"
}

mysql_scalar() {
    # -N -B: 헤더 없이 탭 구분 1줄. 값 하나만 뽑을 때 쓴다.
    docker exec -i -e MYSQL_PWD="$DB_PASSWORD" "$DB_CONTAINER" \
        mysql --user="$DB_USERNAME" --database="$DB_NAME" -N -B "$@"
}

if ! docker ps --format '{{.Names}}' | grep -qx "$DB_CONTAINER"; then
    echo "컨테이너 '$DB_CONTAINER'가 실행 중이 아닙니다. 'docker compose up -d mysql'로 먼저 띄우세요." >&2
    exit 1
fi

if [ "${1:-}" = "--cleanup" ]; then
    log "시드 데이터 정리 중..."
    mysql_run < "$SCRIPT_DIR/gp-perf-cleanup.sql"
    log "완료"
    exit 0
fi

# ── 1) 시드 확인/적재 ──────────────────────────────────────────────────────────
EXISTING=$(mysql_scalar -e "SELECT COUNT(*) FROM group_purchase gp JOIN member m ON m.member_id = gp.member_id WHERE m.email = 'gp-perf-seed@example.test';" 2>/dev/null || echo 0)
if [ "$EXISTING" -ge "$SEED_ROWS" ]; then
    log "시드 데이터 이미 존재 (${EXISTING}행) — 재적재 생략"
else
    log "시드 데이터 적재 중 (목표 ${SEED_ROWS}행, 기존 ${EXISTING}행)... 잠시 걸립니다"
    mysql_run < "$SCRIPT_DIR/gp-perf-seed.sql"
fi

# ── 2) before 측정 ─────────────────────────────────────────────────────────────
log "프로시저 정의 로드 중..."
mysql_run < "$SCRIPT_DIR/gp-perf-bench-core.sql"

log "=== BEFORE (개선 전, 현재 findList) ==="
mysql_run < "$SCRIPT_DIR/gp-perf-bench-before.sql"

# ── 3) after 준비 여부 확인 ────────────────────────────────────────────────────
HAS_FULLTEXT=$(mysql_scalar -e "SELECT COUNT(*) FROM information_schema.STATISTICS WHERE table_schema = '$DB_NAME' AND table_name = 'group_purchase' AND index_name = 'ft_gp_product_name';")
HAS_URGENT_COL=$(mysql_scalar -e "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE table_schema = '$DB_NAME' AND table_name = 'group_purchase' AND column_name = 'is_urgent_active';")

if [ "$HAS_FULLTEXT" -eq 0 ] || [ "$HAS_URGENT_COL" -eq 0 ]; then
    log "AFTER 측정 스킵 — 2장에서 채택한 해결책이 아직 구현되지 않았습니다."
    [ "$HAS_FULLTEXT" -eq 0 ] && log "  - FULLTEXT 인덱스 ft_gp_product_name 없음"
    [ "$HAS_URGENT_COL" -eq 0 ] && log "  - is_urgent_active 컬럼 없음"
    log "구현 후 이 스크립트를 다시 실행하면 AFTER 결과까지 함께 나옵니다."
    exit 0
fi

# ── 4) after 측정: 딥 페이지네이션 커서 값을 before 쿼리로 먼저 구한다 ──────────
log "커서 값 조회 중 (100페이지 상당, offset=999번째 행)..."
CURSOR_LINE=$(mysql_scalar -e "SELECT deadline, created_at, gp_id FROM group_purchase WHERE status != 'CANCELLED' AND target_quantity > 0 AND current_quantity < target_quantity AND deadline >= NOW() ORDER BY deadline ASC, created_at DESC, gp_id DESC LIMIT 1 OFFSET 999;")
CURSOR_DEADLINE=$(echo "$CURSOR_LINE" | cut -f1)
CURSOR_CREATED_AT=$(echo "$CURSOR_LINE" | cut -f2)
CURSOR_GPID=$(echo "$CURSOR_LINE" | cut -f3)

if [ -z "$CURSOR_GPID" ]; then
    log "커서 행을 찾지 못했습니다 (시드 데이터가 부족할 수 있음) — AFTER 딥 페이지 시나리오를 건너뜁니다."
    exit 1
fi
log "커서: deadline=$CURSOR_DEADLINE created_at=$CURSOR_CREATED_AT gp_id=$CURSOR_GPID"

TMP_AFTER="$(mktemp)"
trap 'rm -f "$TMP_AFTER"' EXIT

sed \
    -e "s/__CURSOR_DEADLINE__/$CURSOR_DEADLINE/g" \
    -e "s/__CURSOR_CREATED_AT__/$CURSOR_CREATED_AT/g" \
    -e "s/__CURSOR_GPID__/$CURSOR_GPID/g" \
    "$SCRIPT_DIR/gp-perf-bench-after.sql.template" > "$TMP_AFTER"

log "=== AFTER (개선 후, 2장 채택 해결책) ==="
mysql_run < "$TMP_AFTER"

log "완료. avg/p95(ms)와 EXPLAIN 결과를 docs/group-purchase-list-search-perf-improvement.md 3장 표의 {측정값}에 옮겨 적으세요."
log "정리하려면: ./scripts/gp-perf-bench.sh --cleanup"
