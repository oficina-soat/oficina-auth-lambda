#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

source "${SCRIPT_DIR}/lambda-modules.sh"

MODULE="${1:-${LAMBDA_MODULE:-}}"
if [[ -z "${MODULE}" ]]; then
  echo "Uso: $(basename "$0") <auth-lambda|notificacao-lambda> [args-do-maven...]" >&2
  exit 1
fi
shift || true

load_lambda_module "${MODULE}" || {
  echo "Modulo de Lambda invalido: ${MODULE}" >&2
  exit 1
}

cd "${REPO_ROOT}"

./mvnw -pl "${LAMBDA_MODULE_DIR}" -am clean package -Pnative-aws "$@"

artifact_path="${REPO_ROOT}/${LAMBDA_BUILD_DIR}/function.zip"
named_output="${REPO_ROOT}/${LAMBDA_BUILD_DIR}/${LAMBDA_NAMED_ARTIFACT_FILENAME}"

if [[ ! -f "${artifact_path}" ]]; then
  echo "Artefato nativo nao encontrado em ${artifact_path}" >&2
  exit 1
fi

cp "${artifact_path}" "${named_output}"
echo "Pacote nativo gerado em ${named_output}"
