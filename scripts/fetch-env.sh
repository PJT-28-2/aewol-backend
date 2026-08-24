#!/usr/bin/env bash
# SSM Parameter Store의 운영 설정을 .env.prod로 내려받는다.
#
# docker-compose.prod.yml이 env_file로 .env.prod를 읽으므로 배포 전에 이 파일을 만들어야
# 한다. 시크릿을 리포지토리나 GitHub Secrets에 두지 않고 SSM에만 두기 위한 구조로,
# 워크플로는 배포 명령만 전달하고 값 자체는 EC2가 직접 받아 간다.
#
#   사용:
#     APP_IMAGE=<계정>.dkr.ecr.ap-northeast-2.amazonaws.com/aewol-backend:<sha> \
#     OCR_IMAGE=<계정>.dkr.ecr.ap-northeast-2.amazonaws.com/aewol-ocr:<sha> \
#       /opt/aewol/scripts/fetch-env.sh
#
#   필요 권한: EC2 인스턴스 프로파일에 ssm:GetParametersByPath + kms:Decrypt
set -euo pipefail

PARAM_PATH="${PARAM_PATH:-/aewol/prod}"
REGION="${AWS_REGION:-ap-northeast-2}"
OUT="${OUT:-/opt/aewol/.env.prod}"

# 이미지 태그는 배포마다 달라지므로 SSM에 두지 않고 호출 시점에 받는다.
: "${APP_IMAGE:?APP_IMAGE가 필요합니다}"
: "${OCR_IMAGE:?OCR_IMAGE가 필요합니다}"

TMP="$(mktemp)"
# 시크릿이 담기는 파일이라 만들자마자 권한을 좁힌다. mktemp 기본값에 의존하지 않는다.
chmod 600 "$TMP"
trap 'rm -f "$TMP"' EXIT

{
    echo "APP_IMAGE=${APP_IMAGE}"
    echo "OCR_IMAGE=${OCR_IMAGE}"
} > "$TMP"

# --output text는 "이름<TAB>값" 형태로 준다. 경로 접두사를 떼고 탭을 =로 바꿔 .env 형식을
# 만든다. jq에 의존하지 않기 위한 선택이다(EC2에 기본 설치되어 있지 않다).
aws ssm get-parameters-by-path \
        --path "$PARAM_PATH" --recursive --with-decryption \
        --region "$REGION" \
        --query 'Parameters[].[Name,Value]' --output text \
    | sed -E "s#^${PARAM_PATH}/##" \
    | tr '\t' '=' >> "$TMP"

# 권한 부족이나 경로 오타로 빈 결과가 와도 aws cli는 0으로 끝난다. 그대로 두면 환경변수가
# 거의 없는 .env.prod가 만들어지고, 컨테이너가 기동 실패를 반복하며 원인을 찾기 어려워진다.
COUNT="$(grep -c '=' "$TMP")"
if [ "$COUNT" -lt 10 ]; then
    echo "SSM에서 받은 항목이 너무 적습니다(${COUNT}개). 경로(${PARAM_PATH})와 IAM 권한을 확인하세요." >&2
    exit 1
fi

# SMS는 값이 비어도 서버가 기동되고 요청 시점에만 503이 난다. 배포 전에 필수값을 막는다.
require_env_value() {
    local key="$1"
    local line
    line="$(grep -E "^${key}=" "$TMP" || true)"
    if [ -z "$line" ] || [ "${line#*=}" = "" ]; then
        echo "필수 SSM 파라미터 ${key}가 없거나 비어 있습니다. 경로(${PARAM_PATH})를 확인하세요." >&2
        exit 1
    fi
}

require_env_value SOLAPI_API_KEY
require_env_value SOLAPI_API_SECRET
require_env_value SOLAPI_SENDER

mv "$TMP" "$OUT"
chmod 600 "$OUT"
echo "생성 완료: ${OUT} (${COUNT}개 항목)"
