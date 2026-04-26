#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
cd "${REPO_ROOT}"

source "${SCRIPT_DIR}/lambda-modules.sh"

BASE_REF="${1:-}"
HEAD_REF="${2:-HEAD}"

set_output() {
  local name="$1"
  local value="$2"

  if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
    printf '%s=%s\n' "${name}" "${value}" >> "${GITHUB_OUTPUT}"
  fi
}

json_array_from_csv() {
  local csv="$1"
  jq -cn --arg csv "${csv}" '($csv | split(",") | map(select(length > 0)))'
}

resolve_base_ref() {
  if [[ -n "${BASE_REF}" ]] && git rev-parse --verify "${BASE_REF}^{commit}" >/dev/null 2>&1; then
    printf '%s' "${BASE_REF}"
    return
  fi

  if git rev-parse --verify "${HEAD_REF}^" >/dev/null 2>&1; then
    git rev-parse "${HEAD_REF}^"
    return
  fi

  printf ''
}

common_path_impacts_both() {
  case "$1" in
    pom.xml|mvnw|mvnw.cmd)
      return 0
      ;;
    .mvn/*|scripts/*|.github/workflows/*)
      return 0
      ;;
    *)
      return 1
      ;;
  esac
}

base_commit="$(resolve_base_ref)"
auth_impacted="false"
notificacao_impacted="false"

if [[ -z "${base_commit}" ]]; then
  auth_impacted="true"
  notificacao_impacted="true"
  changed_files="<sem-base>"
else
  changed_files="$(git diff --name-only "${base_commit}" "${HEAD_REF}")"

  while IFS= read -r changed_file; do
    [[ -n "${changed_file}" ]] || continue

    case "${changed_file}" in
      auth-lambda/*)
        auth_impacted="true"
        ;;
      notificacao-lambda/*)
        notificacao_impacted="true"
        ;;
    esac

    if common_path_impacts_both "${changed_file}"; then
      auth_impacted="true"
      notificacao_impacted="true"
    fi
  done <<< "${changed_files}"
fi

impacted_modules=()
if [[ "${auth_impacted}" == "true" ]]; then
  impacted_modules+=("auth-lambda")
fi
if [[ "${notificacao_impacted}" == "true" ]]; then
  impacted_modules+=("notificacao-lambda")
fi

impacted_modules_csv=""
if [[ ${#impacted_modules[@]} -gt 0 ]]; then
  impacted_modules_csv="$(IFS=,; printf '%s' "${impacted_modules[*]}")"
fi
impacted_modules_json="$(json_array_from_csv "${impacted_modules_csv}")"
any_impacted="false"
if [[ ${#impacted_modules[@]} -gt 0 ]]; then
  any_impacted="true"
fi

set_output auth_lambda_impacted "${auth_impacted}"
set_output notificacao_lambda_impacted "${notificacao_impacted}"
set_output impacted_modules_csv "${impacted_modules_csv}"
set_output impacted_modules_json "${impacted_modules_json}"
set_output any_impacted "${any_impacted}"
set_output base_ref "${base_commit}"

printf 'Base ref: %s\n' "${base_commit:-<indisponivel>}"
printf 'Arquivos alterados:\n%s\n' "${changed_files:-<nenhum>}"
printf 'Impacto auth-lambda: %s\n' "${auth_impacted}"
printf 'Impacto notificacao-lambda: %s\n' "${notificacao_impacted}"
printf 'Modulos impactados: %s\n' "${impacted_modules_csv:-<nenhum>}"
