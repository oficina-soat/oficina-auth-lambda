#!/usr/bin/env bash
set -euo pipefail

COMMAND="${1:-}"
AWS_REGION="${AWS_REGION:-us-east-1}"
LAMBDA_ARTIFACT_BUCKET="${LAMBDA_ARTIFACT_BUCKET:-${TF_STATE_BUCKET:-}}"
LAMBDA_ARTIFACT_PREFIX="${LAMBDA_ARTIFACT_PREFIX:-oficina/lab/lambda/oficina-auth-lambda}"
LAMBDA_ARTIFACT_VERSION="${LAMBDA_ARTIFACT_VERSION:-${GITHUB_SHA:-}}"
LAMBDA_ARTIFACT_QUALIFIER="${LAMBDA_ARTIFACT_QUALIFIER:-${LAMBDA_ARCHITECTURE:-x86_64}}"
FUNCTION_ARTIFACT_PATH="${FUNCTION_ARTIFACT_PATH:-target/function.zip}"
NAMED_ARTIFACT_PATH="${NAMED_ARTIFACT_PATH:-target/oficina-auth-lambda-native.zip}"

usage() {
  cat <<EOF
Uso:
  $(basename "$0") restore|store

Variaveis:
  AWS_REGION
  LAMBDA_ARTIFACT_BUCKET ou TF_STATE_BUCKET
  LAMBDA_ARTIFACT_PREFIX       Default: oficina/lab/lambda/oficina-auth-lambda
  LAMBDA_ARTIFACT_VERSION      Default: GITHUB_SHA
  LAMBDA_ARTIFACT_QUALIFIER    Default: LAMBDA_ARCHITECTURE ou x86_64
  FUNCTION_ARTIFACT_PATH       Default: target/function.zip
  NAMED_ARTIFACT_PATH          Default: target/oficina-auth-lambda-native.zip
EOF
}

log() {
  printf '[%s] %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*"
}

require_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Comando obrigatorio nao encontrado: $1" >&2
    exit 1
  fi
}

require_non_empty() {
  local value="$1"
  local name="$2"

  if [[ -z "${value}" ]]; then
    echo "Variavel obrigatoria ausente: ${name}" >&2
    exit 1
  fi
}

artifact_key_prefix() {
  local prefix="${LAMBDA_ARTIFACT_PREFIX%/}"
  printf '%s/%s/%s' "${prefix}" "${LAMBDA_ARTIFACT_QUALIFIER}" "${LAMBDA_ARTIFACT_VERSION}"
}

s3_uri() {
  local object_name="$1"
  printf 's3://%s/%s/%s' "${LAMBDA_ARTIFACT_BUCKET}" "$(artifact_key_prefix)" "${object_name}"
}

object_exists() {
  local object_name="$1"
  local output=""

  set +e
  output="$(
    aws --region "${AWS_REGION}" s3api head-object \
      --bucket "${LAMBDA_ARTIFACT_BUCKET}" \
      --key "$(artifact_key_prefix)/${object_name}" 2>&1
  )"
  local status=$?
  set -e

  if [[ ${status} -eq 0 ]]; then
    return 0
  fi

  if grep -Eq "Not Found|NotFound|404" <<<"${output}"; then
    return 1
  fi

  echo "${output}" >&2
  exit "${status}"
}

set_output() {
  local name="$1"
  local value="$2"

  if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
    printf '%s=%s\n' "${name}" "${value}" >> "${GITHUB_OUTPUT}"
  fi
}

restore_artifacts() {
  local function_uri
  local named_uri

  function_uri="$(s3_uri function.zip)"
  named_uri="$(s3_uri oficina-auth-lambda-native.zip)"

  if ! object_exists function.zip; then
    log "Pacote nativo nao encontrado em ${function_uri}"
    set_output restored false
    return
  fi

  if ! object_exists oficina-auth-lambda-native.zip; then
    log "Pacote nativo nomeado nao encontrado em ${named_uri}"
    set_output restored false
    return
  fi

  mkdir -p "$(dirname "${FUNCTION_ARTIFACT_PATH}")" "$(dirname "${NAMED_ARTIFACT_PATH}")"
  aws --region "${AWS_REGION}" s3 cp "${function_uri}" "${FUNCTION_ARTIFACT_PATH}"
  aws --region "${AWS_REGION}" s3 cp "${named_uri}" "${NAMED_ARTIFACT_PATH}"

  log "Pacote nativo restaurado de s3://$(artifact_key_prefix)"
  set_output restored true
}

store_artifacts() {
  require_non_empty "${LAMBDA_ARTIFACT_BUCKET}" "LAMBDA_ARTIFACT_BUCKET"
  require_non_empty "${LAMBDA_ARTIFACT_VERSION}" "LAMBDA_ARTIFACT_VERSION"

  if [[ ! -f "${FUNCTION_ARTIFACT_PATH}" ]]; then
    echo "Artefato nao encontrado: ${FUNCTION_ARTIFACT_PATH}" >&2
    exit 1
  fi

  if [[ ! -f "${NAMED_ARTIFACT_PATH}" ]]; then
    echo "Artefato nao encontrado: ${NAMED_ARTIFACT_PATH}" >&2
    exit 1
  fi

  aws --region "${AWS_REGION}" s3 cp "${FUNCTION_ARTIFACT_PATH}" "$(s3_uri function.zip)"
  aws --region "${AWS_REGION}" s3 cp "${NAMED_ARTIFACT_PATH}" "$(s3_uri oficina-auth-lambda-native.zip)"

  log "Pacote nativo armazenado em s3://$(artifact_key_prefix)"
}

if [[ "${COMMAND}" == "-h" || "${COMMAND}" == "--help" || -z "${COMMAND}" ]]; then
  usage
  exit 0
fi

require_cmd aws
require_non_empty "${AWS_REGION}" "AWS_REGION"
require_non_empty "${LAMBDA_ARTIFACT_BUCKET}" "LAMBDA_ARTIFACT_BUCKET"
require_non_empty "${LAMBDA_ARTIFACT_VERSION}" "LAMBDA_ARTIFACT_VERSION"

case "${COMMAND}" in
  restore)
    restore_artifacts
    ;;
  store)
    store_artifacts
    ;;
  *)
    usage >&2
    exit 1
    ;;
esac
