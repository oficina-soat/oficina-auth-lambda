#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

ARTIFACT_PATH="${ARTIFACT_PATH:-${REPO_ROOT}/target/function.zip}"
AWS_REGION="${AWS_REGION:-us-east-1}"
EKS_CLUSTER_NAME="${EKS_CLUSTER_NAME:-}"
DB_INSTANCE_IDENTIFIER="${DB_INSTANCE_IDENTIFIER:-oficina-postgres}"
DB_NAME_OVERRIDE="${DB_NAME:-${QUARKUS_DATASOURCE_DB_NAME:-}}"
DB_SSLMODE="${DB_SSLMODE:-require}"
DB_SECURITY_GROUP_IDS="${DB_SECURITY_GROUP_IDS:-}"
LAMBDA_FUNCTION_NAME="${LAMBDA_FUNCTION_NAME:-}"
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

current_env_file=""
desired_env_file=""
merged_env_file=""

cleanup() {
  rm -f "${current_env_file:-}" "${desired_env_file:-}" "${merged_env_file:-}"
}

trap cleanup EXIT

usage() {
  cat <<EOF
Uso:
  $(basename "$0")

Variaveis obrigatorias:
  AWS_REGION
  EKS_CLUSTER_NAME
  LAMBDA_FUNCTION_NAME
  QUARKUS_DATASOURCE_USERNAME
  QUARKUS_DATASOURCE_PASSWORD
  Uma das opcoes para a chave publica JWT:
    MP_JWT_VERIFY_PUBLICKEY ou MP_JWT_VERIFY_PUBLICKEY_LOCATION
  Uma das opcoes para a chave privada JWT:
    SMALLRYE_JWT_SIGN_KEY ou SMALLRYE_JWT_SIGN_KEY_LOCATION

Variaveis opcionais:
  ARTIFACT_PATH                Zip da Lambda. Default: target/function.zip
  DB_INSTANCE_IDENTIFIER       Identificador do RDS. Default: oficina-postgres
  DB_NAME ou QUARKUS_DATASOURCE_DB_NAME
  DB_SSLMODE                   Default: require
  DB_SECURITY_GROUP_IDS        Lista CSV ou JSON de security groups do RDS
  LAMBDA_ROLE_ARN              Obrigatoria apenas na primeira criacao
  LAMBDA_RUNTIME               Default: provided.al2023
  LAMBDA_ARCHITECTURE          Default: x86_64
  LAMBDA_MEMORY_SIZE           Default: 256
  LAMBDA_TIMEOUT               Default: 15
  LAMBDA_VPC_ID                Override da VPC
  LAMBDA_SUBNET_IDS            Lista CSV ou JSON de subnets
  LAMBDA_SECURITY_GROUP_NAME   Default: <LAMBDA_FUNCTION_NAME>-sg
  QUARKUS_DATASOURCE_JDBC_URL  Override completo do JDBC URL
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

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

require_cmd aws
require_cmd jq
require_non_empty "${AWS_REGION}" "AWS_REGION"
require_non_empty "${EKS_CLUSTER_NAME}" "EKS_CLUSTER_NAME"
require_non_empty "${LAMBDA_FUNCTION_NAME}" "LAMBDA_FUNCTION_NAME"
require_non_empty "${QUARKUS_DATASOURCE_USERNAME}" "QUARKUS_DATASOURCE_USERNAME"
require_non_empty "${QUARKUS_DATASOURCE_PASSWORD}" "QUARKUS_DATASOURCE_PASSWORD"
ensure_jwt_inputs

if [[ ! -f "${ARTIFACT_PATH}" ]]; then
  echo "Artefato nao encontrado em ${ARTIFACT_PATH}" >&2
  exit 1
fi

if [[ -z "${LAMBDA_SECURITY_GROUP_NAME}" ]]; then
  LAMBDA_SECURITY_GROUP_NAME="${LAMBDA_FUNCTION_NAME}-sg"
fi

LAMBDA_SUBNET_IDS="$(normalize_list "${LAMBDA_SUBNET_IDS}")"
DB_SECURITY_GROUP_IDS="$(normalize_list "${DB_SECURITY_GROUP_IDS}")"

if [[ -z "${LAMBDA_VPC_ID}" || -z "${LAMBDA_SUBNET_IDS}" ]]; then
  log "Descobrindo VPC e subnets a partir do cluster EKS ${EKS_CLUSTER_NAME}"
  eks_json="$(aws_json eks describe-cluster --name "${EKS_CLUSTER_NAME}" --query 'cluster.resourcesVpcConfig.{vpcId:vpcId,subnetIds:subnetIds}')"

  if [[ -z "${LAMBDA_VPC_ID}" ]]; then
    LAMBDA_VPC_ID="$(jq -r '.vpcId // empty' <<<"${eks_json}")"
  fi

  if [[ -z "${LAMBDA_SUBNET_IDS}" ]]; then
    LAMBDA_SUBNET_IDS="$(jq -r '.subnetIds | join(",")' <<<"${eks_json}")"
  fi
fi

require_non_empty "${LAMBDA_VPC_ID}" "LAMBDA_VPC_ID"
require_non_empty "${LAMBDA_SUBNET_IDS}" "LAMBDA_SUBNET_IDS"

log "Descobrindo endpoint e security groups do RDS ${DB_INSTANCE_IDENTIFIER}"
db_json="$(aws_json rds describe-db-instances --db-instance-identifier "${DB_INSTANCE_IDENTIFIER}" --query 'DBInstances[0].{endpoint:Endpoint.Address,port:Endpoint.Port,dbName:DBName,securityGroupIds:VpcSecurityGroups[].VpcSecurityGroupId}')"

db_host="$(jq -r '.endpoint // empty' <<<"${db_json}")"
db_port="$(jq -r '.port // empty' <<<"${db_json}")"
db_name="$(jq -r '.dbName // empty' <<<"${db_json}")"

if [[ -n "${DB_NAME_OVERRIDE}" ]]; then
  db_name="${DB_NAME_OVERRIDE}"
fi

if [[ -z "${DB_SECURITY_GROUP_IDS}" ]]; then
  DB_SECURITY_GROUP_IDS="$(jq -r '.securityGroupIds | join(",")' <<<"${db_json}")"
fi

require_non_empty "${db_host}" "db_host"
require_non_empty "${db_port}" "db_port"
require_non_empty "${db_name}" "DB_NAME"
require_non_empty "${DB_SECURITY_GROUP_IDS}" "DB_SECURITY_GROUP_IDS"

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
  } | with_entries(select(.value != ""))' > "${desired_env_file}"

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
  '{Variables: (($current[0] // {}) + ($desired[0] // {}))}' > "${merged_env_file}"

vpc_config="SubnetIds=${LAMBDA_SUBNET_IDS},SecurityGroupIds=${lambda_sg_id}"

if [[ "${function_exists}" == "true" ]]; then
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
    --architectures "${LAMBDA_ARCHITECTURE}" \
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

log "Deploy concluido para ${LAMBDA_FUNCTION_NAME}"
