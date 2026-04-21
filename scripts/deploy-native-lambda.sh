#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

ARTIFACT_PATH="${ARTIFACT_PATH:-${REPO_ROOT}/target/function.zip}"
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
LAMBDA_FUNCTION_NAME="${LAMBDA_FUNCTION_NAME:-oficina-auth-lambda-lab}"
LAMBDA_RUNTIME="${LAMBDA_RUNTIME:-provided.al2023}"
LAMBDA_ARCHITECTURE="${LAMBDA_ARCHITECTURE:-x86_64}"
LAMBDA_MEMORY_SIZE="${LAMBDA_MEMORY_SIZE:-256}"
LAMBDA_TIMEOUT="${LAMBDA_TIMEOUT:-15}"
LAMBDA_ROLE_ARN="${LAMBDA_ROLE_ARN:-}"
LAMBDA_VPC_ID="${LAMBDA_VPC_ID:-}"
LAMBDA_SUBNET_IDS="${LAMBDA_SUBNET_IDS:-}"
LAMBDA_SECURITY_GROUP_NAME="${LAMBDA_SECURITY_GROUP_NAME:-}"
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
JWT_DIR="${JWT_DIR:-.tmp/jwt}"
REGENERATE_JWT="${REGENERATE_JWT:-false}"
ATTACH_API_GATEWAY="${ATTACH_API_GATEWAY:-true}"
API_GATEWAY_ID="${API_GATEWAY_ID:-}"
API_GATEWAY_NAME="${API_GATEWAY_NAME:-${EKS_CLUSTER_NAME:+${EKS_CLUSTER_NAME}-http-api}}"
API_GATEWAY_ROUTE_KEY="${API_GATEWAY_ROUTE_KEY:-POST /auth}"
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

usage() {
  cat <<EOF
Uso:
  $(basename "$0")

Variaveis obrigatorias:
  AWS_REGION
  LAMBDA_FUNCTION_NAME

Variaveis opcionais:
  ARTIFACT_PATH                Zip da Lambda. Default: target/function.zip
  EKS_CLUSTER_NAME             Fallback opcional para descobrir VPC/subnets
  DB_INSTANCE_IDENTIFIER       Identificador do RDS. Default: oficina-postgres-lab
  DB_NAME ou QUARKUS_DATASOURCE_DB_NAME
  DB_SSLMODE                   Default: require
  DB_SECURITY_GROUP_IDS        Lista CSV ou JSON de security groups do RDS
  BOOTSTRAP_AUTH_DB_USER       Cria/atualiza usuario proprio no RDS. Default: true
  AUTH_DB_USER                 Usuario da Lambda no RDS. Default: oficina_auth_lambda
  AUTH_DB_PASSWORD             Senha da Lambda no RDS. Gerada quando ausente
  AUTH_DB_SECRET_NAME          Secret da credencial da Lambda. Default: oficina/lab/database/auth-lambda
  STORE_AUTH_DB_SECRET_IN_SECRETS_MANAGER true|false. Default: true
  ROTATE_AUTH_DB_PASSWORD      Gera nova senha mesmo com secret existente. Default: false
  MASTER_SECRET_ARN            Secret master do RDS. Default: descoberto no RDS
  AUTO_ALLOW_DEPLOY_RUNNER_CIDR Libera temporariamente o IP do runner no RDS. Default: true
  LAMBDA_ROLE_ARN              Obrigatoria apenas na primeira criacao
  LAMBDA_RUNTIME               Default: provided.al2023
  LAMBDA_ARCHITECTURE          Default: x86_64
  LAMBDA_MEMORY_SIZE           Default: 256
  LAMBDA_TIMEOUT               Default: 15
  LAMBDA_VPC_ID                Override da VPC
  LAMBDA_SUBNET_IDS            Lista CSV ou JSON de subnets
  LAMBDA_SECURITY_GROUP_NAME   Default: <LAMBDA_FUNCTION_NAME>-sg
  QUARKUS_DATASOURCE_JDBC_URL  Override completo do JDBC URL
  JWT_SECRET_SOURCE            aws-secrets-manager|local-files|env-vars. Default: aws-secrets-manager
  JWT_SECRET_NAME              Secret JWT compartilhado. Default: oficina/lab/jwt
  ROTATE_JWT_SECRET            Gera novo par JWT no Secrets Manager. Default: false
  JWT_DIR                      Diretorio de chaves para JWT_SECRET_SOURCE=local-files
  REGENERATE_JWT               Regenera chaves locais. Default: false
  ATTACH_API_GATEWAY           Vincula a Lambda ao HTTP API. Default: true
  API_GATEWAY_ID               ID do HTTP API existente
  API_GATEWAY_NAME             Nome do HTTP API existente. Default: <EKS_CLUSTER_NAME>-http-api
  API_GATEWAY_ROUTE_KEY        Route key. Default: POST /auth
  API_GATEWAY_PAYLOAD_FORMAT_VERSION Default: 2.0
  API_GATEWAY_TIMEOUT_MILLISECONDS   Default: 30000
EOF
}

log() {
  printf '\n[%s] %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*"
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
  local jwt_dir="$1"

  require_cmd openssl
  mkdir -p "${jwt_dir}"
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "${jwt_dir}/privateKey.pem"
  openssl pkey -in "${jwt_dir}/privateKey.pem" -pubout -out "${jwt_dir}/publicKey.pem"
  chmod 600 "${jwt_dir}/privateKey.pem"
  chmod 644 "${jwt_dir}/publicKey.pem"
}

create_or_rotate_aws_jwt_secret() {
  local tmp_dir
  local secret_json_file

  require_cmd openssl
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
    aws --region "${AWS_REGION}" secretsmanager put-secret-value \
      --secret-id "${JWT_SECRET_NAME}" \
      --secret-string "file://${secret_json_file}" >/dev/null
  else
    log "Criando secret JWT no AWS Secrets Manager: ${JWT_SECRET_NAME}"
    if [[ -n "${JWT_SECRET_KMS_KEY_ID}" ]]; then
      aws --region "${AWS_REGION}" secretsmanager create-secret \
        --name "${JWT_SECRET_NAME}" \
        --kms-key-id "${JWT_SECRET_KMS_KEY_ID}" \
        --description "Chaves JWT compartilhadas da Oficina no ambiente lab" \
        --secret-string "file://${secret_json_file}" >/dev/null
    else
      aws --region "${AWS_REGION}" secretsmanager create-secret \
        --name "${JWT_SECRET_NAME}" \
        --description "Chaves JWT compartilhadas da Oficina no ambiente lab" \
        --secret-string "file://${secret_json_file}" >/dev/null
    fi
  fi

  rm -rf "${tmp_dir}"
}

ensure_aws_jwt_secret() {
  require_non_empty "${JWT_SECRET_NAME}" "JWT_SECRET_NAME"

  if [[ "${ROTATE_JWT_SECRET}" == "true" ]]; then
    create_or_rotate_aws_jwt_secret
    return
  fi

  if secret_exists "${JWT_SECRET_NAME}"; then
    log "Usando secret JWT existente no AWS Secrets Manager: ${JWT_SECRET_NAME}"
    return
  fi

  create_or_rotate_aws_jwt_secret
}

load_jwt_from_aws_secret() {
  local secret_json

  ensure_aws_jwt_secret
  secret_json="$(read_secret_json "${JWT_SECRET_NAME}")"
  SMALLRYE_JWT_SIGN_KEY="$(jq -er --arg field "${JWT_SECRET_PRIVATE_KEY_FIELD}" '.[$field]' <<<"${secret_json}")"
  MP_JWT_VERIFY_PUBLICKEY="$(jq -er --arg field "${JWT_SECRET_PUBLIC_KEY_FIELD}" '.[$field]' <<<"${secret_json}")"

  if ! grep -q "BEGIN PRIVATE KEY" <<<"${SMALLRYE_JWT_SIGN_KEY}"; then
    echo "Campo ${JWT_SECRET_PRIVATE_KEY_FIELD} do secret ${JWT_SECRET_NAME} nao contem uma chave privada PEM valida." >&2
    exit 1
  fi

  if ! grep -q "BEGIN PUBLIC KEY" <<<"${MP_JWT_VERIFY_PUBLICKEY}"; then
    echo "Campo ${JWT_SECRET_PUBLIC_KEY_FIELD} do secret ${JWT_SECRET_NAME} nao contem uma chave publica PEM valida." >&2
    exit 1
  fi

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
    --tag-specifications "ResourceType=security-group,Tags=[{Key=Name,Value=${group_name}},{Key=ManagedBy,Value=github-actions},{Key=Component,Value=lambda}]" \
    --query 'GroupId' \
    --output text
}

authorize_db_ingress() {
  local db_group_id="$1"
  local lambda_group_id="$2"
  local db_port="$3"
  local output

  set +e
  output="$(
    aws --region "${AWS_REGION}" ec2 authorize-security-group-ingress \
      --group-id "${db_group_id}" \
      --ip-permissions "IpProtocol=tcp,FromPort=${db_port},ToPort=${db_port},UserIdGroupPairs=[{GroupId=${lambda_group_id}}]" \
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
  local db_port="$3"
  local output

  set +e
  output="$(
    aws --region "${AWS_REGION}" ec2 authorize-security-group-ingress \
      --group-id "${db_group_id}" \
      --ip-permissions "IpProtocol=tcp,FromPort=${db_port},ToPort=${db_port},IpRanges=[{CidrIp=${cidr_block}}]" \
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

upsert_auth_db_secret() {
  local secret_payload="$1"

  require_non_empty "${AUTH_DB_SECRET_NAME}" "AUTH_DB_SECRET_NAME"

  if secret_exists "${AUTH_DB_SECRET_NAME}"; then
    aws --region "${AWS_REGION}" secretsmanager put-secret-value \
      --secret-id "${AUTH_DB_SECRET_NAME}" \
      --secret-string "${secret_payload}" >/dev/null
    return
  fi

  if [[ -n "${AUTH_DB_SECRET_KMS_KEY_ID}" ]]; then
    aws --region "${AWS_REGION}" secretsmanager create-secret \
      --name "${AUTH_DB_SECRET_NAME}" \
      --kms-key-id "${AUTH_DB_SECRET_KMS_KEY_ID}" \
      --secret-string "${secret_payload}" >/dev/null
    return
  fi

  aws --region "${AWS_REGION}" secretsmanager create-secret \
    --name "${AUTH_DB_SECRET_NAME}" \
    --secret-string "${secret_payload}" >/dev/null
}

bootstrap_auth_db_user() {
  require_cmd psql
  require_cmd openssl

  local db_master_secret_arn="$1"
  local db_master_username="$2"
  local master_secret_json=""
  local existing_auth_secret_json=""
  local existing_auth_secret_user=""
  local existing_auth_secret_password=""
  local secret_payload=""

  if [[ -n "${AUTH_DB_SECRET_NAME}" && "${STORE_AUTH_DB_SECRET_IN_SECRETS_MANAGER}" == "true" && "${ROTATE_AUTH_DB_PASSWORD}" != "true" ]] \
    && secret_exists "${AUTH_DB_SECRET_NAME}"; then
    existing_auth_secret_json="$(read_secret_json "${AUTH_DB_SECRET_NAME}")"
    existing_auth_secret_user="$(read_secret_field "${existing_auth_secret_json}" username)"

    if [[ "${existing_auth_secret_user}" == "${AUTH_DB_USER}" && -z "${AUTH_DB_PASSWORD}" ]]; then
      existing_auth_secret_password="$(read_secret_field "${existing_auth_secret_json}" password)"
      if [[ -n "${existing_auth_secret_password}" ]]; then
        AUTH_DB_PASSWORD="${existing_auth_secret_password}"
        log "Reutilizando senha existente do secret ${AUTH_DB_SECRET_NAME}"
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
  'CREATE ROLE %I LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION',
  :'auth_db_user',
  :'auth_db_password'
)
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'auth_db_user') \gexec

SELECT format(
  'ALTER ROLE %I WITH LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION',
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
    secret_payload="$(jq -nc \
      --arg engine "postgres" \
      --arg host "${db_host}" \
      --arg dbname "${db_name}" \
      --arg username "${AUTH_DB_USER}" \
      --arg password "${AUTH_DB_PASSWORD}" \
      --arg port "${db_port}" \
      '{engine: $engine, host: $host, port: $port, dbname: $dbname, username: $username, password: $password}')"
    upsert_auth_db_secret "${secret_payload}"
    log "Secret da credencial do auth-lambda criada/atualizada em ${AUTH_DB_SECRET_NAME}"
  fi
}

ensure_auth_db_credentials() {
  local db_master_secret_arn="$1"
  local db_master_username="$2"

  if [[ "${BOOTSTRAP_AUTH_DB_USER}" == "true" ]]; then
    bootstrap_auth_db_user "${db_master_secret_arn}" "${db_master_username}"
    return
  fi

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

  log "Garantindo rota ${API_GATEWAY_ROUTE_KEY} no API Gateway ${api_id}"
  local integration_id
  integration_id="$(route_integration_id "${api_id}" "${API_GATEWAY_ROUTE_KEY}")"

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
  existing_route_id="$(route_id "${api_id}" "${API_GATEWAY_ROUTE_KEY}")"

  if [[ -n "${existing_route_id}" ]]; then
    aws --region "${AWS_REGION}" apigatewayv2 update-route \
      --api-id "${api_id}" \
      --route-id "${existing_route_id}" \
      --target "integrations/${integration_id}" >/dev/null
  else
    aws --region "${AWS_REGION}" apigatewayv2 create-route \
      --api-id "${api_id}" \
      --route-key "${API_GATEWAY_ROUTE_KEY}" \
      --target "integrations/${integration_id}" >/dev/null
  fi

  local account_id
  account_id="$(
    aws --region "${AWS_REGION}" sts get-caller-identity \
      --query Account \
      --output text
  )"
  local source_arn="arn:aws:execute-api:${AWS_REGION}:${account_id}:${api_id}/*/*"
  local statement_id
  statement_id="AllowExecutionFromApiGateway$(printf '%s' "${api_id}-${API_GATEWAY_ROUTE_KEY}" | md5sum | cut -c1-8)"

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

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

require_cmd aws
require_cmd jq
require_cmd md5sum
require_non_empty "${AWS_REGION}" "AWS_REGION"
require_non_empty "${LAMBDA_FUNCTION_NAME}" "LAMBDA_FUNCTION_NAME"

if [[ ! -f "${ARTIFACT_PATH}" ]]; then
  echo "Artefato nao encontrado em ${ARTIFACT_PATH}" >&2
  exit 1
fi

ensure_jwt_configuration

if [[ -z "${LAMBDA_SECURITY_GROUP_NAME}" ]]; then
  LAMBDA_SECURITY_GROUP_NAME="${LAMBDA_FUNCTION_NAME}-sg"
fi

LAMBDA_SUBNET_IDS="$(normalize_list "${LAMBDA_SUBNET_IDS}")"
DB_SECURITY_GROUP_IDS="$(normalize_list "${DB_SECURITY_GROUP_IDS}")"

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

if [[ -z "${LAMBDA_VPC_ID}" || -z "${LAMBDA_SUBNET_IDS}" ]] && [[ -n "${EKS_CLUSTER_NAME}" ]]; then
  log "VPC/subnets nao encontrados no RDS; tentando fallback pelo cluster EKS ${EKS_CLUSTER_NAME}"
  eks_json="$(aws_json eks describe-cluster --name "${EKS_CLUSTER_NAME}" --query 'cluster.resourcesVpcConfig.{vpcId:vpcId,subnetIds:subnetIds}')"

  if [[ -z "${LAMBDA_VPC_ID}" ]]; then
    LAMBDA_VPC_ID="$(jq -r '.vpcId // empty' <<<"${eks_json}")"
  fi

  if [[ -z "${LAMBDA_SUBNET_IDS}" ]]; then
    LAMBDA_SUBNET_IDS="$(jq -r '(.subnetIds // []) | join(",")' <<<"${eks_json}")"
  fi
fi

require_non_empty "${db_host}" "db_host"
require_non_empty "${db_port}" "db_port"
require_non_empty "${db_name}" "DB_NAME"
require_non_empty "${DB_SECURITY_GROUP_IDS}" "DB_SECURITY_GROUP_IDS"
require_non_empty "${LAMBDA_VPC_ID}" "LAMBDA_VPC_ID"
require_non_empty "${LAMBDA_SUBNET_IDS}" "LAMBDA_SUBNET_IDS"

if [[ -z "${QUARKUS_DATASOURCE_JDBC_URL}" ]]; then
  QUARKUS_DATASOURCE_JDBC_URL="jdbc:postgresql://${db_host}:${db_port}/${db_name}?sslmode=${DB_SSLMODE}"
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

for db_group_id in ${DB_SECURITY_GROUP_IDS//,/ }; do
  log "Garantindo acesso ${lambda_sg_id} -> ${db_group_id}:${db_port}"
  authorize_db_ingress "${db_group_id}" "${lambda_sg_id}" "${db_port}"
done

ensure_auth_db_credentials "${db_master_secret_arn}" "${db_master_username}"

function_exists="false"
if aws --region "${AWS_REGION}" lambda get-function --function-name "${LAMBDA_FUNCTION_NAME}" >/dev/null 2>&1; then
  function_exists="true"
fi

current_env_file="$(mktemp)"
desired_env_file="$(mktemp)"
merged_env_file="$(mktemp)"

jq -n \
  --arg disable_signal_handlers "true" \
  --arg datasource_username "${QUARKUS_DATASOURCE_USERNAME}" \
  --arg datasource_password "${QUARKUS_DATASOURCE_PASSWORD}" \
  --arg datasource_jdbc_url "${QUARKUS_DATASOURCE_JDBC_URL}" \
  --arg jwt_verify_publickey "${MP_JWT_VERIFY_PUBLICKEY}" \
  --arg jwt_verify_publickey_location "${MP_JWT_VERIFY_PUBLICKEY_LOCATION}" \
  --arg jwt_sign_key "${SMALLRYE_JWT_SIGN_KEY}" \
  --arg jwt_sign_key_location "${SMALLRYE_JWT_SIGN_KEY_LOCATION}" \
  '{
    DISABLE_SIGNAL_HANDLERS: $disable_signal_handlers,
    QUARKUS_DATASOURCE_USERNAME: $datasource_username,
    QUARKUS_DATASOURCE_PASSWORD: $datasource_password,
    QUARKUS_DATASOURCE_JDBC_URL: $datasource_jdbc_url,
    MP_JWT_VERIFY_PUBLICKEY: $jwt_verify_publickey,
    MP_JWT_VERIFY_PUBLICKEY_LOCATION: $jwt_verify_publickey_location,
    SMALLRYE_JWT_SIGN_KEY: $jwt_sign_key,
    SMALLRYE_JWT_SIGN_KEY_LOCATION: $jwt_sign_key_location
  }' > "${desired_env_file}"

if [[ "${function_exists}" == "true" ]]; then
  aws --region "${AWS_REGION}" lambda get-function-configuration \
    --function-name "${LAMBDA_FUNCTION_NAME}" \
    --query 'Environment.Variables' \
    --output json > "${current_env_file}"
else
  echo '{}' > "${current_env_file}"
fi

jq -n \
  --slurpfile current "${current_env_file}" \
  --slurpfile desired "${desired_env_file}" \
  '{Variables: (($current[0] // {}) + ($desired[0] // {}) | with_entries(select(.value != "")))}' > "${merged_env_file}"

vpc_config="SubnetIds=${LAMBDA_SUBNET_IDS},SecurityGroupIds=${lambda_sg_id}"

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
  aws --region "${AWS_REGION}" lambda update-function-configuration \
    --function-name "${LAMBDA_FUNCTION_NAME}" \
    --runtime "${LAMBDA_RUNTIME}" \
    --handler not.used.in.provided.runtime \
    --timeout "${LAMBDA_TIMEOUT}" \
    --memory-size "${LAMBDA_MEMORY_SIZE}" \
    --environment "file://${merged_env_file}" \
    --vpc-config "${vpc_config}"

  aws --region "${AWS_REGION}" lambda wait function-updated \
    --function-name "${LAMBDA_FUNCTION_NAME}"
else
  require_non_empty "${LAMBDA_ROLE_ARN}" "LAMBDA_ROLE_ARN"

  log "Criando a Lambda ${LAMBDA_FUNCTION_NAME}"
  set +e
  create_output="$(
    aws --region "${AWS_REGION}" lambda create-function \
      --function-name "${LAMBDA_FUNCTION_NAME}" \
      --package-type Zip \
      --zip-file "fileb://${ARTIFACT_PATH}" \
      --runtime "${LAMBDA_RUNTIME}" \
      --handler not.used.in.provided.runtime \
      --role "${LAMBDA_ROLE_ARN}" \
      --architectures "${LAMBDA_ARCHITECTURE}" \
      --timeout "${LAMBDA_TIMEOUT}" \
      --memory-size "${LAMBDA_MEMORY_SIZE}" \
      --environment "file://${merged_env_file}" \
      --vpc-config "${vpc_config}" 2>&1
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
