#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

source "${SCRIPT_DIR}/lambda-modules.sh"

MODULE="${1:-${LAMBDA_MODULE:-}}"
if [[ -z "${MODULE}" ]]; then
  echo "Uso: $(basename "$0") <auth-lambda|notificacao-lambda>" >&2
  exit 1
fi

load_lambda_module "${MODULE}" || {
  echo "Modulo de Lambda invalido: ${MODULE}" >&2
  exit 1
}

pick_module_var() {
  local suffix="$1"
  local generic_name="$2"
  local default_value="$3"
  local module_var_name="${LAMBDA_ENV_PREFIX}_${suffix}"
  local value="${!module_var_name:-}"

  if [[ -z "${value}" && -n "${generic_name}" ]]; then
    value="${!generic_name:-}"
  fi

  if [[ -n "${value}" ]]; then
    printf '%s' "${value}"
    return
  fi

  printf '%s' "${default_value}"
}

ARTIFACT_PATH="${ARTIFACT_PATH:-${REPO_ROOT}/${LAMBDA_BUILD_DIR}/function.zip}"
AWS_REGION="${AWS_REGION:-us-east-1}"
EKS_CLUSTER_NAME="${EKS_CLUSTER_NAME:-}"
DB_INSTANCE_IDENTIFIER="${DB_INSTANCE_IDENTIFIER:-oficina-postgres-lab}"
DB_NAME_OVERRIDE="${DB_NAME:-${QUARKUS_DATASOURCE_DB_NAME:-}}"
DB_SSLMODE="${DB_SSLMODE:-require}"
DB_SECURITY_GROUP_IDS="${DB_SECURITY_GROUP_IDS:-}"
MASTER_SECRET_ARN="${MASTER_SECRET_ARN:-}"
MASTER_DB_USER="${MASTER_DB_USER:-}"
MASTER_DB_PASSWORD="${MASTER_DB_PASSWORD:-}"
BOOTSTRAP_AUTH_DB_USER="${BOOTSTRAP_AUTH_DB_USER:-true}"
AUTH_DB_USER="${AUTH_DB_USER:-oficina_auth_lambda}"
AUTH_DB_PASSWORD="${AUTH_DB_PASSWORD:-}"
AUTH_DB_ALLOW_SCHEMA_CHANGES="${AUTH_DB_ALLOW_SCHEMA_CHANGES:-false}"
STORE_AUTH_DB_SECRET_IN_SECRETS_MANAGER="${STORE_AUTH_DB_SECRET_IN_SECRETS_MANAGER:-true}"
AUTH_DB_SECRET_NAME="${AUTH_DB_SECRET_NAME:-oficina/lab/database/auth-lambda}"
AUTH_DB_SECRET_KMS_KEY_ID="${AUTH_DB_SECRET_KMS_KEY_ID:-}"
ROTATE_AUTH_DB_PASSWORD="${ROTATE_AUTH_DB_PASSWORD:-false}"
AUTO_ALLOW_DEPLOY_RUNNER_CIDR="${AUTO_ALLOW_DEPLOY_RUNNER_CIDR:-true}"
CI_RUNNER_PUBLIC_IP_URL="${CI_RUNNER_PUBLIC_IP_URL:-https://checkip.amazonaws.com}"
LAMBDA_FUNCTION_NAME="$(pick_module_var LAMBDA_FUNCTION_NAME LAMBDA_FUNCTION_NAME "${LAMBDA_FUNCTION_NAME_DEFAULT}")"
LAMBDA_RUNTIME="$(pick_module_var LAMBDA_RUNTIME LAMBDA_RUNTIME provided.al2023)"
LAMBDA_ARCHITECTURE="$(pick_module_var LAMBDA_ARCHITECTURE LAMBDA_ARCHITECTURE x86_64)"
LAMBDA_MEMORY_SIZE="$(pick_module_var LAMBDA_MEMORY_SIZE LAMBDA_MEMORY_SIZE 256)"
LAMBDA_TIMEOUT="$(pick_module_var LAMBDA_TIMEOUT LAMBDA_TIMEOUT 15)"
LAMBDA_ROLE_ARN="$(pick_module_var LAMBDA_ROLE_ARN LAMBDA_ROLE_ARN "")"
LAMBDA_ATTACH_VPC="$(pick_module_var LAMBDA_ATTACH_VPC "" "${LAMBDA_ATTACH_VPC_DEFAULT}")"
LAMBDA_VPC_ID="$(pick_module_var LAMBDA_VPC_ID LAMBDA_VPC_ID "")"
LAMBDA_SUBNET_IDS="$(pick_module_var LAMBDA_SUBNET_IDS LAMBDA_SUBNET_IDS "")"
LAMBDA_SECURITY_GROUP_NAME="$(pick_module_var LAMBDA_SECURITY_GROUP_NAME LAMBDA_SECURITY_GROUP_NAME "")"
LAMBDA_EXTRA_ENV_JSON="$(pick_module_var LAMBDA_EXTRA_ENV_JSON LAMBDA_EXTRA_ENV_JSON "{}")"
QUARKUS_DATASOURCE_USERNAME="${QUARKUS_DATASOURCE_USERNAME:-}"
QUARKUS_DATASOURCE_PASSWORD="${QUARKUS_DATASOURCE_PASSWORD:-}"
QUARKUS_DATASOURCE_JDBC_URL="${QUARKUS_DATASOURCE_JDBC_URL:-}"
MP_JWT_VERIFY_PUBLICKEY="${MP_JWT_VERIFY_PUBLICKEY:-}"
MP_JWT_VERIFY_PUBLICKEY_LOCATION="${MP_JWT_VERIFY_PUBLICKEY_LOCATION:-}"
SMALLRYE_JWT_SIGN_KEY="${SMALLRYE_JWT_SIGN_KEY:-}"
SMALLRYE_JWT_SIGN_KEY_LOCATION="${SMALLRYE_JWT_SIGN_KEY_LOCATION:-}"
JWT_SECRET_SOURCE="${JWT_SECRET_SOURCE:-aws-secrets-manager}"
JWT_SECRET_NAME="${JWT_SECRET_NAME:-oficina/lab/jwt}"
JWT_SECRET_PRIVATE_KEY_FIELD="${JWT_SECRET_PRIVATE_KEY_FIELD:-privateKeyPem}"
JWT_SECRET_PUBLIC_KEY_FIELD="${JWT_SECRET_PUBLIC_KEY_FIELD:-publicKeyPem}"
JWT_SECRET_KMS_KEY_ID="${JWT_SECRET_KMS_KEY_ID:-}"
ROTATE_JWT_SECRET="${ROTATE_JWT_SECRET:-false}"
JWT_DIR="${JWT_DIR:-${LAMBDA_MODULE_DIR}/src/main/resources/jwt}"
REGENERATE_JWT="${REGENERATE_JWT:-false}"
LAMBDA_SECRET_INJECTION_MODE="${LAMBDA_SECRET_INJECTION_MODE:-env-vars}"
OFICINA_AUTH_ISSUER="${OFICINA_AUTH_ISSUER:-}"
OFICINA_AUTH_AUDIENCE="${OFICINA_AUTH_AUDIENCE:-oficina-app}"
OFICINA_AUTH_SCOPE="${OFICINA_AUTH_SCOPE:-oficina-app}"
OFICINA_AUTH_KEY_ID="${OFICINA_AUTH_KEY_ID:-oficina-lab-rsa}"
ATTACH_API_GATEWAY="$(pick_module_var ATTACH_API_GATEWAY ATTACH_API_GATEWAY true)"
API_GATEWAY_ID="$(pick_module_var API_GATEWAY_ID API_GATEWAY_ID "")"
API_GATEWAY_NAME="$(pick_module_var API_GATEWAY_NAME API_GATEWAY_NAME "${EKS_CLUSTER_NAME:+${EKS_CLUSTER_NAME}-http-api}")"
API_GATEWAY_ROUTE_KEY="$(pick_module_var API_GATEWAY_ROUTE_KEY API_GATEWAY_ROUTE_KEY "${LAMBDA_API_GATEWAY_ROUTE_KEY_DEFAULT}")"
API_GATEWAY_ROUTE_KEYS="$(pick_module_var API_GATEWAY_ROUTE_KEYS API_GATEWAY_ROUTE_KEYS "${LAMBDA_API_GATEWAY_ROUTE_KEYS_DEFAULT}")"
API_GATEWAY_PAYLOAD_FORMAT_VERSION="${API_GATEWAY_PAYLOAD_FORMAT_VERSION:-2.0}"
API_GATEWAY_TIMEOUT_MILLISECONDS="${API_GATEWAY_TIMEOUT_MILLISECONDS:-30000}"

current_env_file=""
desired_env_file=""
merged_env_file=""
jwt_tmp_dir=""
DEPLOY_RUNNER_CIDR=""
declare -a TEMP_DB_CIDR_GROUP_IDS=()

cleanup() {
  rm -f "${current_env_file:-}" "${desired_env_file:-}" "${merged_env_file:-}"
  rm -rf "${jwt_tmp_dir:-}"

  if [[ -n "${DEPLOY_RUNNER_CIDR}" && -n "${db_port:-}" && ${#TEMP_DB_CIDR_GROUP_IDS[@]} -gt 0 ]]; then
    for db_group_id in "${TEMP_DB_CIDR_GROUP_IDS[@]}"; do
      aws --region "${AWS_REGION}" ec2 revoke-security-group-ingress \
        --group-id "${db_group_id}" \
        --ip-permissions "IpProtocol=tcp,FromPort=${db_port},ToPort=${db_port},IpRanges=[{CidrIp=${DEPLOY_RUNNER_CIDR}}]" >/dev/null 2>&1 || true
    done
  fi
}

trap cleanup EXIT

log() {
  printf '\n[%s] [%s] %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "${LAMBDA_MODULE}" "$*"
}

fail_with_context() {
  local status="$1"
  local output="$2"
  local hint="${3:-}"

  echo "${output}" >&2
  if [[ -n "${hint}" ]]; then
    echo "${hint}" >&2
  fi
  exit "${status}"
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

normalize_list() {
  local value="$1"

  if [[ -z "${value}" ]]; then
    echo ""
    return
  fi

  if jq -er 'type == "array"' >/dev/null 2>&1 <<<"${value}"; then
    jq -r 'join(",")' <<<"${value}"
    return
  fi

  printf '%s' "${value}" | tr -d '[:space:]'
}

trim() {
  local value="$1"
  value="${value#"${value%%[![:space:]]*}"}"
  value="${value%"${value##*[![:space:]]}"}"
  printf '%s' "${value}"
}

normalize_url_like_value() {
  local value
  value="$(trim "$1")"
  while [[ -n "${value}" && "${value}" == */ ]]; do
    value="${value%/}"
  done
  printf '%s' "${value}"
}

require_valid_secret_id() {
  local value="$1"
  local name="$2"

  require_non_empty "${value}" "${name}"

  if [[ "${value}" == *[[:space:]]* || "${value}" == *\"* || "${value}" == *"'"* ]]; then
    echo "${name} invalido: contem espaco, quebra de linha ou aspas." >&2
    exit 1
  fi

  if [[ ! "${value}" =~ ^arn:[A-Za-z0-9_+=,.@:/!-]+$ && ! "${value}" =~ ^[A-Za-z0-9/_+=.@!-]+$ ]]; then
    echo "${name} invalido: informe um ARN ou nome de secret do AWS Secrets Manager." >&2
    exit 1
  fi
}

join_secret_path() {
  local base_name="$1"
  local suffix="$2"

  require_valid_secret_id "${base_name}" "base_name"
  require_valid_secret_id "${suffix}" "suffix"
  printf '%s/%s' "${base_name%/}" "${suffix#/}"
}

jwt_legacy_private_key_secret_name() {
  join_secret_path "${JWT_SECRET_NAME}" "${JWT_SECRET_PRIVATE_KEY_FIELD}"
}

jwt_legacy_public_key_secret_name() {
  join_secret_path "${JWT_SECRET_NAME}" "${JWT_SECRET_PUBLIC_KEY_FIELD}"
}

auth_db_secret_field_name() {
  local field_name="$1"

  join_secret_path "${AUTH_DB_SECRET_NAME}" "${field_name}"
}

ensure_jwt_inputs() {
  if [[ -z "${MP_JWT_VERIFY_PUBLICKEY}" && -z "${MP_JWT_VERIFY_PUBLICKEY_LOCATION}" ]]; then
    echo "Informe MP_JWT_VERIFY_PUBLICKEY ou MP_JWT_VERIFY_PUBLICKEY_LOCATION." >&2
    exit 1
  fi

  if [[ -z "${SMALLRYE_JWT_SIGN_KEY}" && -z "${SMALLRYE_JWT_SIGN_KEY_LOCATION}" ]]; then
    echo "Informe SMALLRYE_JWT_SIGN_KEY ou SMALLRYE_JWT_SIGN_KEY_LOCATION." >&2
    exit 1
  fi
}

require_valid_lambda_secret_injection_mode() {
  case "${LAMBDA_SECRET_INJECTION_MODE}" in
    env-vars|runtime-secrets-manager)
      ;;
    *)
      echo "LAMBDA_SECRET_INJECTION_MODE invalido: ${LAMBDA_SECRET_INJECTION_MODE}. Use env-vars ou runtime-secrets-manager." >&2
      exit 1
      ;;
  esac
}

aws_json() {
  aws --region "${AWS_REGION}" "$@" --output json
}

secret_exists() {
  local secret_name="$1"
  local error_file

  require_valid_secret_id "${secret_name}" "secret_name"

  error_file="$(mktemp)"
  if aws --region "${AWS_REGION}" secretsmanager describe-secret \
    --secret-id "${secret_name}" >/dev/null 2>"${error_file}"; then
    rm -f "${error_file}"
    return 0
  fi

  if grep -q "ResourceNotFoundException" "${error_file}"; then
    rm -f "${error_file}"
    return 1
  fi

  cat "${error_file}" >&2
  rm -f "${error_file}"
  exit 1
}

read_secret_json() {
  local secret_name="$1"

  require_valid_secret_id "${secret_name}" "secret_name"
  aws --region "${AWS_REGION}" secretsmanager get-secret-value \
    --secret-id "${secret_name}" \
    --query SecretString \
    --output text
}

read_secret_string() {
  local secret_name="$1"

  require_valid_secret_id "${secret_name}" "secret_name"
  aws --region "${AWS_REGION}" secretsmanager get-secret-value \
    --secret-id "${secret_name}" \
    --query SecretString \
    --output text
}

read_secret_field() {
  local secret_json="$1"
  local field_name="$2"

  jq -er --arg field_name "${field_name}" '.[$field_name] // empty' <<<"${secret_json}" 2>/dev/null || true
}

generate_password() {
  require_cmd openssl
  openssl rand -base64 48 | tr -d '\n' | tr '/+' '_-' | cut -c1-32
}

generate_jwt_keypair() {
  local target_jwt_dir="$1"

  require_cmd openssl
  mkdir -p "${target_jwt_dir}"
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "${target_jwt_dir}/privateKey.pem"
  openssl pkey -in "${target_jwt_dir}/privateKey.pem" -pubout -out "${target_jwt_dir}/publicKey.pem"
  chmod 600 "${target_jwt_dir}/privateKey.pem"
  chmod 644 "${target_jwt_dir}/publicKey.pem"
}

upsert_secret_string() {
  local secret_name="$1"
  local secret_file="$2"
  local kms_key_id="$3"
  local description="$4"

  require_non_empty "${secret_name}" "secret_name"
  require_non_empty "${secret_file}" "secret_file"

  if secret_exists "${secret_name}"; then
    aws --region "${AWS_REGION}" secretsmanager put-secret-value \
      --secret-id "${secret_name}" \
      --secret-string "file://${secret_file}" >/dev/null
    return
  fi

  if [[ -n "${kms_key_id}" ]]; then
    aws --region "${AWS_REGION}" secretsmanager create-secret \
      --name "${secret_name}" \
      --kms-key-id "${kms_key_id}" \
      --description "${description}" \
      --secret-string "file://${secret_file}" >/dev/null
    return
  fi

  aws --region "${AWS_REGION}" secretsmanager create-secret \
    --name "${secret_name}" \
    --description "${description}" \
    --secret-string "file://${secret_file}" >/dev/null
}

create_or_rotate_aws_jwt_secret() {
  local tmp_dir
  local secret_json_file

  require_cmd openssl
  require_cmd jq
  require_non_empty "${JWT_SECRET_NAME}" "JWT_SECRET_NAME"
  require_non_empty "${JWT_SECRET_PRIVATE_KEY_FIELD}" "JWT_SECRET_PRIVATE_KEY_FIELD"
  require_non_empty "${JWT_SECRET_PUBLIC_KEY_FIELD}" "JWT_SECRET_PUBLIC_KEY_FIELD"

  tmp_dir="$(mktemp -d)"
  secret_json_file="${tmp_dir}/jwt-secret.json"

  generate_jwt_keypair "${tmp_dir}"

  jq -n \
    --rawfile privateKeyPem "${tmp_dir}/privateKey.pem" \
    --rawfile publicKeyPem "${tmp_dir}/publicKey.pem" \
    --arg privateKeyField "${JWT_SECRET_PRIVATE_KEY_FIELD}" \
    --arg publicKeyField "${JWT_SECRET_PUBLIC_KEY_FIELD}" \
    '{($privateKeyField): $privateKeyPem, ($publicKeyField): $publicKeyPem}' \
    > "${secret_json_file}"

  if secret_exists "${JWT_SECRET_NAME}"; then
    log "Rotacionando secret JWT no AWS Secrets Manager: ${JWT_SECRET_NAME}"
  else
    log "Criando secret JWT no AWS Secrets Manager: ${JWT_SECRET_NAME}"
  fi

  upsert_secret_string "${JWT_SECRET_NAME}" "${secret_json_file}" "${JWT_SECRET_KMS_KEY_ID}" \
    "Chaves JWT compartilhadas da Oficina no ambiente lab"

  rm -rf "${tmp_dir}"
}

ensure_aws_jwt_secret() {
  local legacy_private_key_secret_name
  local legacy_public_key_secret_name
  local tmp_dir
  local secret_json_file
  local jwt_private_key
  local jwt_public_key

  require_non_empty "${JWT_SECRET_NAME}" "JWT_SECRET_NAME"
  legacy_private_key_secret_name="$(jwt_legacy_private_key_secret_name)"
  legacy_public_key_secret_name="$(jwt_legacy_public_key_secret_name)"

  if [[ "${ROTATE_JWT_SECRET}" == "true" ]]; then
    create_or_rotate_aws_jwt_secret
    return
  fi

  if secret_exists "${JWT_SECRET_NAME}"; then
    log "Usando secret JWT existente no AWS Secrets Manager: ${JWT_SECRET_NAME}"
    return
  fi

  if secret_exists "${legacy_private_key_secret_name}" && secret_exists "${legacy_public_key_secret_name}"; then
    require_cmd jq
    tmp_dir="$(mktemp -d)"
    secret_json_file="${tmp_dir}/jwt-secret.json"
    jwt_private_key="$(read_secret_string "${legacy_private_key_secret_name}")"
    jwt_public_key="$(read_secret_string "${legacy_public_key_secret_name}")"

    jq -n \
      --arg privateKeyField "${JWT_SECRET_PRIVATE_KEY_FIELD}" \
      --arg publicKeyField "${JWT_SECRET_PUBLIC_KEY_FIELD}" \
      --arg privateKeyPem "${jwt_private_key}" \
      --arg publicKeyPem "${jwt_public_key}" \
      '{($privateKeyField): $privateKeyPem, ($publicKeyField): $publicKeyPem}' \
      > "${secret_json_file}"

    log "Migrando legacy JWT sub-secrets para o secret compartilhado ${JWT_SECRET_NAME}"
    upsert_secret_string "${JWT_SECRET_NAME}" "${secret_json_file}" "${JWT_SECRET_KMS_KEY_ID}" \
      "Chaves JWT compartilhadas da Oficina no ambiente lab"
    rm -rf "${tmp_dir}"
    return
  fi

  create_or_rotate_aws_jwt_secret
}

load_jwt_from_aws_secret() {
  ensure_aws_jwt_secret
  require_cmd jq
  local jwt_secret_json
  local jwt_private_key
  local jwt_public_key
  jwt_secret_json="$(read_secret_json "${JWT_SECRET_NAME}")"
  jwt_private_key="$(read_secret_field "${jwt_secret_json}" "${JWT_SECRET_PRIVATE_KEY_FIELD}")"
  jwt_public_key="$(read_secret_field "${jwt_secret_json}" "${JWT_SECRET_PUBLIC_KEY_FIELD}")"

  if ! grep -q "BEGIN PRIVATE KEY" <<<"${jwt_private_key}"; then
    echo "Campo ${JWT_SECRET_PRIVATE_KEY_FIELD} do secret ${JWT_SECRET_NAME} nao contem uma chave privada PEM valida." >&2
    exit 1
  fi

  if ! grep -q "BEGIN PUBLIC KEY" <<<"${jwt_public_key}"; then
    echo "Campo ${JWT_SECRET_PUBLIC_KEY_FIELD} do secret ${JWT_SECRET_NAME} nao contem uma chave publica PEM valida." >&2
    exit 1
  fi

  if [[ "${LAMBDA_SECRET_INJECTION_MODE}" == "env-vars" ]]; then
    SMALLRYE_JWT_SIGN_KEY="${jwt_private_key}"
    MP_JWT_VERIFY_PUBLICKEY="${jwt_public_key}"
    SMALLRYE_JWT_SIGN_KEY_LOCATION=""
    MP_JWT_VERIFY_PUBLICKEY_LOCATION=""
    return
  fi

  SMALLRYE_JWT_SIGN_KEY=""
  MP_JWT_VERIFY_PUBLICKEY=""
  SMALLRYE_JWT_SIGN_KEY_LOCATION=""
  MP_JWT_VERIFY_PUBLICKEY_LOCATION=""
}

load_jwt_from_local_files() {
  if [[ "${REGENERATE_JWT}" == "true" || ! -f "${JWT_DIR}/privateKey.pem" || ! -f "${JWT_DIR}/publicKey.pem" ]]; then
    log "Gerando par de chaves JWT em ${JWT_DIR}"
    generate_jwt_keypair "${JWT_DIR}"
  fi

  if [[ ! -f "${JWT_DIR}/privateKey.pem" || ! -f "${JWT_DIR}/publicKey.pem" ]]; then
    echo "Arquivos JWT nao encontrados em ${JWT_DIR}. Ajuste REGENERATE_JWT=true ou forneca as chaves." >&2
    exit 1
  fi

  SMALLRYE_JWT_SIGN_KEY="$(<"${JWT_DIR}/privateKey.pem")"
  MP_JWT_VERIFY_PUBLICKEY="$(<"${JWT_DIR}/publicKey.pem")"
  SMALLRYE_JWT_SIGN_KEY_LOCATION=""
  MP_JWT_VERIFY_PUBLICKEY_LOCATION=""
}

ensure_jwt_configuration() {
  case "${JWT_SECRET_SOURCE}" in
    aws-secrets-manager)
      load_jwt_from_aws_secret
      ;;
    local-files)
      load_jwt_from_local_files
      ;;
    env-vars)
      ensure_jwt_inputs
      ;;
    *)
      echo "JWT_SECRET_SOURCE invalido: ${JWT_SECRET_SOURCE}. Use aws-secrets-manager, local-files ou env-vars." >&2
      exit 1
      ;;
  esac
}

create_security_group() {
  local group_name="$1"
  local vpc_id="$2"

  aws --region "${AWS_REGION}" ec2 create-security-group \
    --group-name "${group_name}" \
    --description "Security group da Lambda ${LAMBDA_FUNCTION_NAME}" \
    --vpc-id "${vpc_id}" \
    --tag-specifications "ResourceType=security-group,Tags=[{Key=Name,Value=${group_name}},{Key=ManagedBy,Value=github-actions},{Key=Component,Value=${LAMBDA_MODULE}}]" \
    --query 'GroupId' \
    --output text
}

authorize_db_ingress() {
  local db_group_id="$1"
  local lambda_group_id="$2"
  local target_db_port="$3"
  local output

  set +e
  output="$(
    aws --region "${AWS_REGION}" ec2 authorize-security-group-ingress \
      --group-id "${db_group_id}" \
      --ip-permissions "IpProtocol=tcp,FromPort=${target_db_port},ToPort=${target_db_port},UserIdGroupPairs=[{GroupId=${lambda_group_id}}]" \
      2>&1
  )"
  local status=$?
  set -e

  if [[ ${status} -eq 0 ]]; then
    return
  fi

  if grep -q "InvalidPermission.Duplicate" <<<"${output}"; then
    return
  fi

  echo "${output}" >&2
  exit "${status}"
}

authorize_db_cidr_ingress() {
  local db_group_id="$1"
  local cidr_block="$2"
  local target_db_port="$3"
  local output

  set +e
  output="$(
    aws --region "${AWS_REGION}" ec2 authorize-security-group-ingress \
      --group-id "${db_group_id}" \
      --ip-permissions "IpProtocol=tcp,FromPort=${target_db_port},ToPort=${target_db_port},IpRanges=[{CidrIp=${cidr_block}}]" \
      2>&1
  )"
  local status=$?
  set -e

  if [[ ${status} -eq 0 ]]; then
    TEMP_DB_CIDR_GROUP_IDS+=("${db_group_id}")
    return
  fi

  if grep -q "InvalidPermission.Duplicate" <<<"${output}"; then
    return
  fi

  echo "${output}" >&2
  exit "${status}"
}

configure_deploy_runner_db_access() {
  local runner_ip

  if [[ "${BOOTSTRAP_AUTH_DB_USER}" != "true" || "${AUTO_ALLOW_DEPLOY_RUNNER_CIDR}" != "true" ]]; then
    return
  fi

  require_cmd curl
  runner_ip="$(curl -fsSL --max-time 10 "${CI_RUNNER_PUBLIC_IP_URL}" | tr -d '[:space:]')"

  if [[ ! "${runner_ip}" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}$ ]]; then
    echo "Nao foi possivel descobrir um IPv4 publico valido para liberar acesso do runner ao RDS: ${runner_ip}" >&2
    exit 1
  fi

  DEPLOY_RUNNER_CIDR="${runner_ip}/32"

  for db_group_id in ${DB_SECURITY_GROUP_IDS//,/ }; do
    log "Liberando temporariamente acesso ${DEPLOY_RUNNER_CIDR} -> ${db_group_id}:${db_port} para bootstrap do usuario"
    authorize_db_cidr_ingress "${db_group_id}" "${DEPLOY_RUNNER_CIDR}" "${db_port}"
  done
}

bootstrap_auth_db_user() {
  require_cmd psql
  require_cmd openssl

  local db_master_secret_arn="$1"
  local db_master_username="$2"
  local master_secret_json=""
  local existing_auth_secret_user=""
  local existing_auth_secret_password=""
  local auth_db_username_secret_name=""
  local auth_db_password_secret_name=""
  local auth_db_engine_secret_name=""
  local auth_db_host_secret_name=""
  local auth_db_port_secret_name=""
  local auth_db_name_secret_name=""
  local secret_tmp_dir=""

  if [[ -n "${AUTH_DB_SECRET_NAME}" && "${STORE_AUTH_DB_SECRET_IN_SECRETS_MANAGER}" == "true" ]]; then
    auth_db_username_secret_name="$(auth_db_secret_field_name username)"
    auth_db_password_secret_name="$(auth_db_secret_field_name password)"
    auth_db_engine_secret_name="$(auth_db_secret_field_name engine)"
    auth_db_host_secret_name="$(auth_db_secret_field_name host)"
    auth_db_port_secret_name="$(auth_db_secret_field_name port)"
    auth_db_name_secret_name="$(auth_db_secret_field_name dbname)"
  fi

  if [[ -n "${auth_db_username_secret_name}" && -n "${auth_db_password_secret_name}" && "${ROTATE_AUTH_DB_PASSWORD}" != "true" ]] \
    && secret_exists "${auth_db_username_secret_name}" && secret_exists "${auth_db_password_secret_name}"; then
    existing_auth_secret_user="$(read_secret_string "${auth_db_username_secret_name}")"
    if [[ "${existing_auth_secret_user}" == "${AUTH_DB_USER}" && -z "${AUTH_DB_PASSWORD}" ]]; then
      existing_auth_secret_password="$(read_secret_string "${auth_db_password_secret_name}")"
      if [[ -n "${existing_auth_secret_password}" ]]; then
        AUTH_DB_PASSWORD="${existing_auth_secret_password}"
        log "Reutilizando senha existente do secret ${auth_db_password_secret_name}"
      fi
    fi
  fi

  if [[ -z "${AUTH_DB_PASSWORD}" ]]; then
    AUTH_DB_PASSWORD="$(generate_password)"
  fi

  if [[ -z "${MASTER_SECRET_ARN}" ]]; then
    MASTER_SECRET_ARN="${db_master_secret_arn}"
  fi

  if [[ -n "${MASTER_SECRET_ARN}" ]]; then
    master_secret_json="$(read_secret_json "${MASTER_SECRET_ARN}")"

    if [[ -z "${MASTER_DB_USER}" ]]; then
      MASTER_DB_USER="$(read_secret_field "${master_secret_json}" username)"
    fi

    if [[ -z "${MASTER_DB_PASSWORD}" ]]; then
      MASTER_DB_PASSWORD="$(read_secret_field "${master_secret_json}" password)"
    fi
  fi

  if [[ -z "${MASTER_DB_USER}" ]]; then
    MASTER_DB_USER="${db_master_username}"
  fi

  require_non_empty "${MASTER_DB_USER}" "MASTER_DB_USER"
  require_non_empty "${MASTER_DB_PASSWORD}" "MASTER_DB_PASSWORD"
  require_non_empty "${AUTH_DB_USER}" "AUTH_DB_USER"
  require_non_empty "${AUTH_DB_PASSWORD}" "AUTH_DB_PASSWORD"

  configure_deploy_runner_db_access

  log "Criando ou atualizando o usuario ${AUTH_DB_USER} em ${db_host}:${db_port}/${db_name}"
  PGPASSWORD="${MASTER_DB_PASSWORD}" psql \
    "host=${db_host} port=${db_port} dbname=${db_name} user=${MASTER_DB_USER} sslmode=${DB_SSLMODE}" \
    -v ON_ERROR_STOP=1 \
    --set=auth_db_user="${AUTH_DB_USER}" \
    --set=auth_db_password="${AUTH_DB_PASSWORD}" \
    --set=auth_db_allow_schema_changes="${AUTH_DB_ALLOW_SCHEMA_CHANGES}" \
    <<'SQL'
SELECT format(
  'CREATE ROLE %I LOGIN PASSWORD %L',
  :'auth_db_user',
  :'auth_db_password'
)
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'auth_db_user') \gexec

SELECT format(
  'ALTER ROLE %I WITH LOGIN PASSWORD %L',
  :'auth_db_user',
  :'auth_db_password'
) \gexec

SELECT format('GRANT CONNECT ON DATABASE %I TO %I', current_database(), :'auth_db_user') \gexec
SELECT format('GRANT USAGE ON SCHEMA public TO %I', :'auth_db_user') \gexec
SELECT format(
  'GRANT SELECT, INSERT, UPDATE, DELETE, TRIGGER, REFERENCES ON ALL TABLES IN SCHEMA public TO %I',
  :'auth_db_user'
) \gexec
SELECT format(
  'GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA public TO %I',
  :'auth_db_user'
) \gexec
SELECT format(
  'ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE, TRIGGER, REFERENCES ON TABLES TO %I',
  :'auth_db_user'
) \gexec
SELECT format(
  'ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO %I',
  :'auth_db_user'
) \gexec

\if :auth_db_allow_schema_changes
SELECT format('GRANT CREATE ON SCHEMA public TO %I', :'auth_db_user') \gexec
\endif
SQL

  QUARKUS_DATASOURCE_USERNAME="${AUTH_DB_USER}"
  QUARKUS_DATASOURCE_PASSWORD="${AUTH_DB_PASSWORD}"

  if [[ "${STORE_AUTH_DB_SECRET_IN_SECRETS_MANAGER}" == "true" ]]; then
    secret_tmp_dir="$(mktemp -d)"
    printf '%s' "postgres" > "${secret_tmp_dir}/engine"
    printf '%s' "${db_host}" > "${secret_tmp_dir}/host"
    printf '%s' "${db_port}" > "${secret_tmp_dir}/port"
    printf '%s' "${db_name}" > "${secret_tmp_dir}/dbname"
    printf '%s' "${AUTH_DB_USER}" > "${secret_tmp_dir}/username"
    printf '%s' "${AUTH_DB_PASSWORD}" > "${secret_tmp_dir}/password"

    upsert_secret_string "${auth_db_engine_secret_name}" "${secret_tmp_dir}/engine" "${AUTH_DB_SECRET_KMS_KEY_ID}" \
      "Engine da credencial do auth-lambda no ambiente lab"
    upsert_secret_string "${auth_db_host_secret_name}" "${secret_tmp_dir}/host" "${AUTH_DB_SECRET_KMS_KEY_ID}" \
      "Host da credencial do auth-lambda no ambiente lab"
    upsert_secret_string "${auth_db_port_secret_name}" "${secret_tmp_dir}/port" "${AUTH_DB_SECRET_KMS_KEY_ID}" \
      "Porta da credencial do auth-lambda no ambiente lab"
    upsert_secret_string "${auth_db_name_secret_name}" "${secret_tmp_dir}/dbname" "${AUTH_DB_SECRET_KMS_KEY_ID}" \
      "Database da credencial do auth-lambda no ambiente lab"
    upsert_secret_string "${auth_db_username_secret_name}" "${secret_tmp_dir}/username" "${AUTH_DB_SECRET_KMS_KEY_ID}" \
      "Usuario da credencial do auth-lambda no ambiente lab"
    upsert_secret_string "${auth_db_password_secret_name}" "${secret_tmp_dir}/password" "${AUTH_DB_SECRET_KMS_KEY_ID}" \
      "Senha da credencial do auth-lambda no ambiente lab"
    rm -rf "${secret_tmp_dir}"
    log "Secrets da credencial do auth-lambda criados/atualizados sob ${AUTH_DB_SECRET_NAME}/"
  fi
}

load_auth_db_credentials_from_secret() {
  local auth_db_username_secret_name
  local auth_db_password_secret_name

  if [[ -z "${AUTH_DB_SECRET_NAME}" || "${STORE_AUTH_DB_SECRET_IN_SECRETS_MANAGER}" != "true" ]]; then
    return
  fi

  auth_db_username_secret_name="$(auth_db_secret_field_name username)"
  auth_db_password_secret_name="$(auth_db_secret_field_name password)"

  if [[ -z "${QUARKUS_DATASOURCE_USERNAME}" ]] && secret_exists "${auth_db_username_secret_name}"; then
    QUARKUS_DATASOURCE_USERNAME="$(read_secret_string "${auth_db_username_secret_name}")"
  fi

  if [[ -z "${QUARKUS_DATASOURCE_PASSWORD}" ]] && secret_exists "${auth_db_password_secret_name}"; then
    QUARKUS_DATASOURCE_PASSWORD="$(read_secret_string "${auth_db_password_secret_name}")"
  fi
}

ensure_auth_db_credentials() {
  local db_master_secret_arn="$1"
  local db_master_username="$2"

  if [[ "${BOOTSTRAP_AUTH_DB_USER}" == "true" ]]; then
    bootstrap_auth_db_user "${db_master_secret_arn}" "${db_master_username}"
    return
  fi

  load_auth_db_credentials_from_secret
  require_non_empty "${QUARKUS_DATASOURCE_USERNAME}" "QUARKUS_DATASOURCE_USERNAME"
  require_non_empty "${QUARKUS_DATASOURCE_PASSWORD}" "QUARKUS_DATASOURCE_PASSWORD"
}

resolve_api_gateway_id() {
  if [[ -n "${API_GATEWAY_ID}" ]]; then
    printf '%s' "${API_GATEWAY_ID}"
    return
  fi

  require_non_empty "${API_GATEWAY_NAME}" "API_GATEWAY_NAME"

  aws_json apigatewayv2 get-apis \
    --query 'Items[].{ApiId:ApiId,Name:Name}' |
    jq -r --arg name "${API_GATEWAY_NAME}" '[.[] | select(.Name == $name) | .ApiId][0] // empty'
}

api_gateway_endpoint() {
  local api_id="$1"

  aws --region "${AWS_REGION}" apigatewayv2 get-api \
    --api-id "${api_id}" \
    --query 'ApiEndpoint' \
    --output text
}

route_integration_id() {
  local api_id="$1"
  local route_key="$2"

  aws_json apigatewayv2 get-routes --api-id "${api_id}" \
    --query 'Items[].{RouteId:RouteId,RouteKey:RouteKey,Target:Target}' |
    jq -r --arg route_key "${route_key}" '
      [.[] | select(.RouteKey == $route_key) | .Target][0] // "" |
      sub("^integrations/"; "")
    '
}

route_id() {
  local api_id="$1"
  local route_key="$2"

  aws_json apigatewayv2 get-routes --api-id "${api_id}" \
    --query 'Items[].{RouteId:RouteId,RouteKey:RouteKey}' |
    jq -r --arg route_key "${route_key}" '[.[] | select(.RouteKey == $route_key) | .RouteId][0] // empty'
}

create_api_gateway_integration() {
  local api_id="$1"
  local function_arn="$2"

  aws --region "${AWS_REGION}" apigatewayv2 create-integration \
    --api-id "${api_id}" \
    --integration-type AWS_PROXY \
    --integration-method POST \
    --integration-uri "${function_arn}" \
    --payload-format-version "${API_GATEWAY_PAYLOAD_FORMAT_VERSION}" \
    --timeout-in-millis "${API_GATEWAY_TIMEOUT_MILLISECONDS}" \
    --query 'IntegrationId' \
    --output text
}

ensure_api_gateway_route() {
  local api_id="$1"
  local function_arn="$2"
  local route_key="$3"

  log "Garantindo rota ${route_key} no API Gateway ${api_id}"
  local integration_id
  integration_id="$(route_integration_id "${api_id}" "${route_key}")"

  if [[ -n "${integration_id}" ]]; then
    aws --region "${AWS_REGION}" apigatewayv2 update-integration \
      --api-id "${api_id}" \
      --integration-id "${integration_id}" \
      --integration-type AWS_PROXY \
      --integration-method POST \
      --integration-uri "${function_arn}" \
      --payload-format-version "${API_GATEWAY_PAYLOAD_FORMAT_VERSION}" \
      --timeout-in-millis "${API_GATEWAY_TIMEOUT_MILLISECONDS}" >/dev/null
  else
    integration_id="$(create_api_gateway_integration "${api_id}" "${function_arn}")"
  fi

  local existing_route_id
  existing_route_id="$(route_id "${api_id}" "${route_key}")"

  if [[ -n "${existing_route_id}" ]]; then
    aws --region "${AWS_REGION}" apigatewayv2 update-route \
      --api-id "${api_id}" \
      --route-id "${existing_route_id}" \
      --authorization-type NONE \
      --target "integrations/${integration_id}" >/dev/null
  else
    aws --region "${AWS_REGION}" apigatewayv2 create-route \
      --api-id "${api_id}" \
      --route-key "${route_key}" \
      --authorization-type NONE \
      --target "integrations/${integration_id}" >/dev/null
  fi
}

ensure_api_gateway_integration() {
  if [[ "${ATTACH_API_GATEWAY}" != "true" ]]; then
    log "Vinculo com API Gateway desabilitado por ATTACH_API_GATEWAY=${ATTACH_API_GATEWAY}"
    return
  fi

  local api_id
  api_id="$(resolve_api_gateway_id)"
  require_non_empty "${api_id}" "API_GATEWAY_ID"

  local function_arn
  function_arn="$(
    aws --region "${AWS_REGION}" lambda get-function \
      --function-name "${LAMBDA_FUNCTION_NAME}" \
      --query 'Configuration.FunctionArn' \
      --output text
  )"
  require_non_empty "${function_arn}" "function_arn"

  local route_key
  IFS=';' read -r -a route_keys <<<"${API_GATEWAY_ROUTE_KEYS}"
  for route_key in "${route_keys[@]}"; do
    route_key="$(trim "${route_key}")"
    if [[ -n "${route_key}" ]]; then
      ensure_api_gateway_route "${api_id}" "${function_arn}" "${route_key}"
    fi
  done

  local account_id
  account_id="$(
    aws --region "${AWS_REGION}" sts get-caller-identity \
      --query Account \
      --output text
  )"
  local source_arn="arn:aws:execute-api:${AWS_REGION}:${account_id}:${api_id}/*/*"
  local statement_id
  statement_id="AllowExecutionFromApiGateway$(printf '%s' "${api_id}-${LAMBDA_MODULE}" | md5sum | cut -c1-8)"

  set +e
  permission_output="$(
    aws --region "${AWS_REGION}" lambda add-permission \
      --function-name "${LAMBDA_FUNCTION_NAME}" \
      --statement-id "${statement_id}" \
      --action lambda:InvokeFunction \
      --principal apigateway.amazonaws.com \
      --source-arn "${source_arn}" 2>&1
  )"
  permission_status=$?
  set -e

  if [[ ${permission_status} -ne 0 ]] && ! grep -q "ResourceConflictException" <<<"${permission_output}"; then
    fail_with_context "${permission_status}" "${permission_output}"
  fi
}

validate_json_object() {
  local json_value="$1"
  local name="$2"

  if ! jq -e 'type == "object"' >/dev/null 2>&1 <<<"${json_value}"; then
    echo "${name} invalido: informe um JSON do tipo objeto." >&2
    exit 1
  fi
}

ensure_network_from_eks_if_needed() {
  if [[ -z "${LAMBDA_VPC_ID}" || -z "${LAMBDA_SUBNET_IDS}" ]] && [[ -n "${EKS_CLUSTER_NAME}" ]]; then
    log "VPC/subnets nao encontrados; tentando fallback pelo cluster EKS ${EKS_CLUSTER_NAME}"
    local eks_json
    eks_json="$(aws_json eks describe-cluster --name "${EKS_CLUSTER_NAME}" --query 'cluster.resourcesVpcConfig.{vpcId:vpcId,subnetIds:subnetIds}')"

    if [[ -z "${LAMBDA_VPC_ID}" ]]; then
      LAMBDA_VPC_ID="$(jq -r '.vpcId // empty' <<<"${eks_json}")"
    fi

    if [[ -z "${LAMBDA_SUBNET_IDS}" ]]; then
      LAMBDA_SUBNET_IDS="$(jq -r '(.subnetIds // []) | join(",")' <<<"${eks_json}")"
    fi
  fi
}

require_cmd aws
require_cmd jq
require_cmd md5sum
require_non_empty "${AWS_REGION}" "AWS_REGION"
require_non_empty "${LAMBDA_FUNCTION_NAME}" "LAMBDA_FUNCTION_NAME"

validate_json_object "${LAMBDA_EXTRA_ENV_JSON}" "LAMBDA_EXTRA_ENV_JSON"

if [[ "${LAMBDA_USES_JWT}" == "true" ]]; then
  require_valid_lambda_secret_injection_mode
fi

if [[ ! -f "${ARTIFACT_PATH}" ]]; then
  echo "Artefato nao encontrado em ${ARTIFACT_PATH}" >&2
  exit 1
fi

if [[ "${LAMBDA_USES_JWT}" == "true" ]]; then
  ensure_jwt_configuration
fi

if [[ -z "${LAMBDA_SECURITY_GROUP_NAME}" ]]; then
  LAMBDA_SECURITY_GROUP_NAME="${LAMBDA_FUNCTION_NAME}-sg"
fi

LAMBDA_SUBNET_IDS="$(normalize_list "${LAMBDA_SUBNET_IDS}")"
DB_SECURITY_GROUP_IDS="$(normalize_list "${DB_SECURITY_GROUP_IDS}")"

lambda_sg_id=""
vpc_config=""

if [[ "${LAMBDA_ATTACH_VPC}" == "true" ]]; then
  if [[ "${LAMBDA_USES_DATABASE}" == "true" ]]; then
    log "Descobrindo endpoint, VPC, subnets, security groups e secret master do RDS ${DB_INSTANCE_IDENTIFIER}"
    db_json="$(aws_json rds describe-db-instances --db-instance-identifier "${DB_INSTANCE_IDENTIFIER}" --query 'DBInstances[0].{endpoint:Endpoint.Address,port:Endpoint.Port,dbName:DBName,masterSecretArn:MasterUserSecret.SecretArn,masterUsername:MasterUsername,securityGroupIds:VpcSecurityGroups[].VpcSecurityGroupId,vpcId:DBSubnetGroup.VpcId,subnetIds:DBSubnetGroup.Subnets[].SubnetIdentifier}')"

    db_host="$(jq -r '.endpoint // empty' <<<"${db_json}")"
    db_port="$(jq -r '.port // empty' <<<"${db_json}")"
    db_name="$(jq -r '.dbName // empty' <<<"${db_json}")"
    db_master_secret_arn="$(jq -r '.masterSecretArn // empty' <<<"${db_json}")"
    db_master_username="$(jq -r '.masterUsername // empty' <<<"${db_json}")"

    if [[ -n "${DB_NAME_OVERRIDE}" ]]; then
      db_name="${DB_NAME_OVERRIDE}"
    fi

    if [[ -z "${DB_SECURITY_GROUP_IDS}" ]]; then
      DB_SECURITY_GROUP_IDS="$(jq -r '(.securityGroupIds // []) | join(",")' <<<"${db_json}")"
    fi

    if [[ -z "${LAMBDA_VPC_ID}" ]]; then
      LAMBDA_VPC_ID="$(jq -r '.vpcId // empty' <<<"${db_json}")"
    fi

    if [[ -z "${LAMBDA_SUBNET_IDS}" ]]; then
      LAMBDA_SUBNET_IDS="$(jq -r '(.subnetIds // []) | join(",")' <<<"${db_json}")"
    fi

    ensure_network_from_eks_if_needed

    require_non_empty "${db_host}" "db_host"
    require_non_empty "${db_port}" "db_port"
    require_non_empty "${db_name}" "DB_NAME"
    require_non_empty "${DB_SECURITY_GROUP_IDS}" "DB_SECURITY_GROUP_IDS"
    require_non_empty "${LAMBDA_VPC_ID}" "LAMBDA_VPC_ID"
    require_non_empty "${LAMBDA_SUBNET_IDS}" "LAMBDA_SUBNET_IDS"

    if [[ -z "${QUARKUS_DATASOURCE_JDBC_URL}" ]]; then
      QUARKUS_DATASOURCE_JDBC_URL="jdbc:postgresql://${db_host}:${db_port}/${db_name}?sslmode=${DB_SSLMODE}"
    fi
  else
    ensure_network_from_eks_if_needed
    require_non_empty "${LAMBDA_VPC_ID}" "LAMBDA_VPC_ID"
    require_non_empty "${LAMBDA_SUBNET_IDS}" "LAMBDA_SUBNET_IDS"
  fi

  log "Garantindo o security group da Lambda na VPC ${LAMBDA_VPC_ID}"
  lambda_sg_id="$(
    aws --region "${AWS_REGION}" ec2 describe-security-groups \
      --filters "Name=vpc-id,Values=${LAMBDA_VPC_ID}" "Name=group-name,Values=${LAMBDA_SECURITY_GROUP_NAME}" \
      --query 'SecurityGroups[0].GroupId' \
      --output text
  )"

  if [[ "${lambda_sg_id}" == "None" || -z "${lambda_sg_id}" ]]; then
    lambda_sg_id="$(create_security_group "${LAMBDA_SECURITY_GROUP_NAME}" "${LAMBDA_VPC_ID}")"
  fi

  if [[ "${LAMBDA_USES_DATABASE}" == "true" ]]; then
    for db_group_id in ${DB_SECURITY_GROUP_IDS//,/ }; do
      log "Garantindo acesso ${lambda_sg_id} -> ${db_group_id}:${db_port}"
      authorize_db_ingress "${db_group_id}" "${lambda_sg_id}" "${db_port}"
    done

    ensure_auth_db_credentials "${db_master_secret_arn}" "${db_master_username}"
  fi

  vpc_config="SubnetIds=${LAMBDA_SUBNET_IDS},SecurityGroupIds=${lambda_sg_id}"
fi

function_exists="false"
if aws --region "${AWS_REGION}" lambda get-function --function-name "${LAMBDA_FUNCTION_NAME}" >/dev/null 2>&1; then
  function_exists="true"
fi

if [[ "${LAMBDA_USES_JWT}" == "true" && -z "${OFICINA_AUTH_ISSUER}" && "${ATTACH_API_GATEWAY}" == "true" ]]; then
  auth_api_id="$(resolve_api_gateway_id)"
  require_non_empty "${auth_api_id}" "API_GATEWAY_ID"
  OFICINA_AUTH_ISSUER="$(api_gateway_endpoint "${auth_api_id}")"
fi

OFICINA_AUTH_ISSUER="$(normalize_url_like_value "${OFICINA_AUTH_ISSUER}")"

current_env_file="$(mktemp)"
desired_env_file="$(mktemp)"
merged_env_file="$(mktemp)"

if [[ "${function_exists}" == "true" ]]; then
  aws --region "${AWS_REGION}" lambda get-function-configuration \
    --function-name "${LAMBDA_FUNCTION_NAME}" \
    --query 'Environment.Variables' \
    --output json > "${current_env_file}"
else
  echo '{}' > "${current_env_file}"
fi

lambda_datasource_username="${QUARKUS_DATASOURCE_USERNAME}"
lambda_datasource_password="${QUARKUS_DATASOURCE_PASSWORD}"
lambda_auth_db_secret_name=""
lambda_auth_db_username_secret_name=""
lambda_auth_db_password_secret_name=""
lambda_jwt_secret_name=""
lambda_secrets_manager_config_enabled="false"

if [[ "${LAMBDA_USES_DATABASE}" == "true" && "${LAMBDA_SECRET_INJECTION_MODE}" == "runtime-secrets-manager" ]] \
  && [[ -n "${AUTH_DB_SECRET_NAME}" && "${STORE_AUTH_DB_SECRET_IN_SECRETS_MANAGER}" == "true" ]]; then
  candidate_auth_db_username_secret_name="$(auth_db_secret_field_name username)"
  candidate_auth_db_password_secret_name="$(auth_db_secret_field_name password)"
  if [[ "${BOOTSTRAP_AUTH_DB_USER}" == "true" ]] \
    || { secret_exists "${candidate_auth_db_username_secret_name}" && secret_exists "${candidate_auth_db_password_secret_name}"; }; then
    lambda_datasource_username=""
    lambda_datasource_password=""
    lambda_auth_db_secret_name="${AUTH_DB_SECRET_NAME}"
    lambda_auth_db_username_secret_name="${candidate_auth_db_username_secret_name}"
    lambda_auth_db_password_secret_name="${candidate_auth_db_password_secret_name}"
    lambda_secrets_manager_config_enabled="true"
  fi
fi

lambda_jwt_verify_publickey="${MP_JWT_VERIFY_PUBLICKEY}"
lambda_jwt_verify_publickey_location="${MP_JWT_VERIFY_PUBLICKEY_LOCATION}"
lambda_jwt_sign_key="${SMALLRYE_JWT_SIGN_KEY}"
lambda_jwt_sign_key_location="${SMALLRYE_JWT_SIGN_KEY_LOCATION}"

if [[ "${LAMBDA_USES_JWT}" == "true" && "${LAMBDA_SECRET_INJECTION_MODE}" == "runtime-secrets-manager" ]] \
  && [[ "${JWT_SECRET_SOURCE}" == "aws-secrets-manager" ]]; then
  lambda_jwt_verify_publickey=""
  lambda_jwt_verify_publickey_location=""
  lambda_jwt_sign_key=""
  lambda_jwt_sign_key_location=""
  lambda_jwt_secret_name="${JWT_SECRET_NAME}"
  lambda_secrets_manager_config_enabled="true"
fi

extra_env_keys_csv="$(jq -r 'keys | join(",")' <<<"${LAMBDA_EXTRA_ENV_JSON}")"

if [[ "${LAMBDA_USES_JWT}" == "true" ]]; then
  jq -n \
    --arg disable_signal_handlers "true" \
    --arg managed_extra_env_keys "${extra_env_keys_csv}" \
    --arg secrets_manager_config_enabled "${lambda_secrets_manager_config_enabled}" \
    --arg datasource_username "${lambda_datasource_username}" \
    --arg datasource_password "${lambda_datasource_password}" \
    --arg datasource_jdbc_url "${QUARKUS_DATASOURCE_JDBC_URL}" \
    --arg auth_db_secret_name "${lambda_auth_db_secret_name}" \
    --arg auth_db_username_secret_name "${lambda_auth_db_username_secret_name}" \
    --arg auth_db_password_secret_name "${lambda_auth_db_password_secret_name}" \
    --arg jwt_secret_source "${JWT_SECRET_SOURCE}" \
    --arg jwt_secret_name "${JWT_SECRET_NAME}" \
    --arg jwt_secret_private_key_field "${JWT_SECRET_PRIVATE_KEY_FIELD}" \
    --arg jwt_secret_public_key_field "${JWT_SECRET_PUBLIC_KEY_FIELD}" \
    --arg jwt_secret_json_name "${lambda_jwt_secret_name}" \
    --arg jwt_verify_publickey "${lambda_jwt_verify_publickey}" \
    --arg jwt_verify_publickey_location "${lambda_jwt_verify_publickey_location}" \
    --arg jwt_sign_key "${lambda_jwt_sign_key}" \
    --arg jwt_sign_key_location "${lambda_jwt_sign_key_location}" \
    --arg oficina_auth_issuer "${OFICINA_AUTH_ISSUER}" \
    --arg oficina_auth_audience "${OFICINA_AUTH_AUDIENCE}" \
    --arg oficina_auth_scope "${OFICINA_AUTH_SCOPE}" \
    --arg oficina_auth_key_id "${OFICINA_AUTH_KEY_ID}" \
    '{
      DISABLE_SIGNAL_HANDLERS: $disable_signal_handlers,
      OFICINA_LAMBDA_MANAGED_EXTRA_ENV_KEYS: $managed_extra_env_keys,
      SECRETS_MANAGER_CONFIG_ENABLED: $secrets_manager_config_enabled,
      QUARKUS_DATASOURCE_USERNAME: $datasource_username,
      QUARKUS_DATASOURCE_PASSWORD: $datasource_password,
      QUARKUS_DATASOURCE_JDBC_URL: $datasource_jdbc_url,
      AUTH_DB_SECRET_NAME: $auth_db_secret_name,
      QUARKUS_SECRETSMANAGER_CONFIG_SECRETS__QUARKUS_DATASOURCE_USERNAME_: $auth_db_username_secret_name,
      QUARKUS_SECRETSMANAGER_CONFIG_SECRETS__QUARKUS_DATASOURCE_PASSWORD_: $auth_db_password_secret_name,
      JWT_SECRET_SOURCE: $jwt_secret_source,
      JWT_SECRET_NAME: $jwt_secret_name,
      JWT_SECRET_PRIVATE_KEY_FIELD: $jwt_secret_private_key_field,
      JWT_SECRET_PUBLIC_KEY_FIELD: $jwt_secret_public_key_field,
      QUARKUS_SECRETSMANAGER_CONFIG_SECRETS__SMALLRYE_JWT_SIGN_KEY_: $jwt_secret_json_name,
      QUARKUS_SECRETSMANAGER_CONFIG_SECRETS__MP_JWT_VERIFY_PUBLICKEY_: $jwt_secret_json_name,
      MP_JWT_VERIFY_PUBLICKEY: $jwt_verify_publickey,
      MP_JWT_VERIFY_PUBLICKEY_LOCATION: $jwt_verify_publickey_location,
      SMALLRYE_JWT_SIGN_KEY: $jwt_sign_key,
      SMALLRYE_JWT_SIGN_KEY_LOCATION: $jwt_sign_key_location,
      OFICINA_AUTH_ISSUER: $oficina_auth_issuer,
      OFICINA_AUTH_AUDIENCE: $oficina_auth_audience,
      OFICINA_AUTH_SCOPE: $oficina_auth_scope,
      OFICINA_AUTH_KEY_ID: $oficina_auth_key_id
    }' > "${desired_env_file}"
else
  jq -n \
    --arg disable_signal_handlers "true" \
    --arg managed_extra_env_keys "${extra_env_keys_csv}" \
    '{
      DISABLE_SIGNAL_HANDLERS: $disable_signal_handlers,
      OFICINA_LAMBDA_MANAGED_EXTRA_ENV_KEYS: $managed_extra_env_keys
    }' > "${desired_env_file}"
fi

jq -n \
  --slurpfile desired "${desired_env_file}" \
  --argjson extra "${LAMBDA_EXTRA_ENV_JSON}" \
  '($desired[0] // {}) + $extra' > "${merged_env_file}"
mv "${merged_env_file}" "${desired_env_file}"
merged_env_file="$(mktemp)"

if [[ "${LAMBDA_USES_JWT}" == "true" ]]; then
  builtin_managed_keys='[
    "DISABLE_SIGNAL_HANDLERS",
    "OFICINA_LAMBDA_MANAGED_EXTRA_ENV_KEYS",
    "SECRETS_MANAGER_CONFIG_ENABLED",
    "QUARKUS_DATASOURCE_USERNAME",
    "QUARKUS_DATASOURCE_PASSWORD",
    "QUARKUS_DATASOURCE_JDBC_URL",
    "AUTH_DB_SECRET_NAME",
    "QUARKUS_SECRETSMANAGER_CONFIG_SECRETS__QUARKUS_DATASOURCE_USERNAME_",
    "QUARKUS_SECRETSMANAGER_CONFIG_SECRETS__QUARKUS_DATASOURCE_PASSWORD_",
    "JWT_SECRET_SOURCE",
    "JWT_SECRET_NAME",
    "JWT_SECRET_PRIVATE_KEY_FIELD",
    "JWT_SECRET_PUBLIC_KEY_FIELD",
    "QUARKUS_SECRETSMANAGER_CONFIG_SECRETS__SMALLRYE_JWT_SIGN_KEY_",
    "QUARKUS_SECRETSMANAGER_CONFIG_SECRETS__MP_JWT_VERIFY_PUBLICKEY_",
    "MP_JWT_VERIFY_PUBLICKEY",
    "MP_JWT_VERIFY_PUBLICKEY_LOCATION",
    "SMALLRYE_JWT_SIGN_KEY",
    "SMALLRYE_JWT_SIGN_KEY_LOCATION",
    "OFICINA_AUTH_ISSUER",
    "OFICINA_AUTH_AUDIENCE",
    "OFICINA_AUTH_SCOPE",
    "OFICINA_AUTH_KEY_ID"
  ]'
else
  builtin_managed_keys='[
    "DISABLE_SIGNAL_HANDLERS",
    "OFICINA_LAMBDA_MANAGED_EXTRA_ENV_KEYS"
  ]'
fi

previous_extra_keys_csv="$(jq -r '.OFICINA_LAMBDA_MANAGED_EXTRA_ENV_KEYS // ""' "${current_env_file}")"
new_extra_keys_json="$(jq -c 'keys' <<<"${LAMBDA_EXTRA_ENV_JSON}")"
managed_keys_json="$(
  jq -cn \
    --argjson builtin "${builtin_managed_keys}" \
    --arg previous "${previous_extra_keys_csv}" \
    --argjson current "${new_extra_keys_json}" '
      def split_csv($csv):
        if $csv == "" then [] else ($csv | split(",") | map(select(length > 0))) end;
      ($builtin + split_csv($previous) + $current) | unique
    '
)"

jq -n \
  --argjson managed "${managed_keys_json}" \
  --slurpfile current "${current_env_file}" \
  --slurpfile desired "${desired_env_file}" \
  '
  {
    Variables: (
      (($current[0] // {})
      | with_entries(select((.key as $key | $managed | index($key)) | not)))
      + ($desired[0] // {})
      | with_entries(select(.value != ""))
    )
  }' > "${merged_env_file}"

if [[ "${function_exists}" == "true" ]]; then
  current_architecture="$(
    aws --region "${AWS_REGION}" lambda get-function \
      --function-name "${LAMBDA_FUNCTION_NAME}" \
      --query 'Configuration.Architectures[0]' \
      --output text
  )"

  if [[ -n "${current_architecture}" && "${current_architecture}" != "None" && "${current_architecture}" != "${LAMBDA_ARCHITECTURE}" ]]; then
    echo "A Lambda ${LAMBDA_FUNCTION_NAME} ja existe com arquitetura ${current_architecture}, mas o deploy solicitou ${LAMBDA_ARCHITECTURE}. Atualize a arquitetura manualmente ou recrie a funcao antes de seguir." >&2
    exit 1
  fi

  log "Atualizando codigo da Lambda ${LAMBDA_FUNCTION_NAME}"
  aws --region "${AWS_REGION}" lambda update-function-code \
    --function-name "${LAMBDA_FUNCTION_NAME}" \
    --zip-file "fileb://${ARTIFACT_PATH}"

  aws --region "${AWS_REGION}" lambda wait function-updated \
    --function-name "${LAMBDA_FUNCTION_NAME}"

  log "Atualizando configuracao da Lambda ${LAMBDA_FUNCTION_NAME}"
  update_args=(
    --function-name "${LAMBDA_FUNCTION_NAME}"
    --runtime "${LAMBDA_RUNTIME}"
    --handler not.used.in.provided.runtime
    --timeout "${LAMBDA_TIMEOUT}"
    --memory-size "${LAMBDA_MEMORY_SIZE}"
    --environment "file://${merged_env_file}"
  )
  if [[ "${LAMBDA_ATTACH_VPC}" == "true" ]]; then
    update_args+=(--vpc-config "${vpc_config}")
  fi

  aws --region "${AWS_REGION}" lambda update-function-configuration "${update_args[@]}"

  aws --region "${AWS_REGION}" lambda wait function-updated \
    --function-name "${LAMBDA_FUNCTION_NAME}"
else
  require_non_empty "${LAMBDA_ROLE_ARN}" "LAMBDA_ROLE_ARN"

  log "Criando a Lambda ${LAMBDA_FUNCTION_NAME}"
  create_args=(
    --function-name "${LAMBDA_FUNCTION_NAME}"
    --package-type Zip
    --zip-file "fileb://${ARTIFACT_PATH}"
    --runtime "${LAMBDA_RUNTIME}"
    --handler not.used.in.provided.runtime
    --role "${LAMBDA_ROLE_ARN}"
    --architectures "${LAMBDA_ARCHITECTURE}"
    --timeout "${LAMBDA_TIMEOUT}"
    --memory-size "${LAMBDA_MEMORY_SIZE}"
    --environment "file://${merged_env_file}"
  )
  if [[ "${LAMBDA_ATTACH_VPC}" == "true" ]]; then
    create_args+=(--vpc-config "${vpc_config}")
  fi

  set +e
  create_output="$(
    aws --region "${AWS_REGION}" lambda create-function "${create_args[@]}" 2>&1
  )"
  create_status=$?
  set -e

  if [[ ${create_status} -ne 0 ]]; then
    if grep -q "iam:PassRole" <<<"${create_output}"; then
      fail_with_context \
        "${create_status}" \
        "${create_output}" \
        "A identidade usada no deploy nao pode executar iam:PassRole na role ${LAMBDA_ROLE_ARN}. Conceda iam:PassRole para essa role ou faca o primeiro provisionamento da Lambda com uma identidade que tenha essa permissao."
    fi

    fail_with_context "${create_status}" "${create_output}"
  fi
fi

aws --region "${AWS_REGION}" lambda wait function-active \
  --function-name "${LAMBDA_FUNCTION_NAME}"

ensure_api_gateway_integration

log "Deploy concluido para ${LAMBDA_FUNCTION_NAME}"
