#!/usr/bin/env bash
set -euo pipefail

AWS_REGION="${AWS_REGION:-us-east-1}"
EKS_CLUSTER_NAME="${EKS_CLUSTER_NAME:-eks-lab}"
DB_INSTANCE_IDENTIFIER="${DB_INSTANCE_IDENTIFIER:-oficina-postgres-lab}"
DB_SECURITY_GROUP_IDS="${DB_SECURITY_GROUP_IDS:-}"
DB_PORT="${DB_PORT:-}"
LAMBDA_FUNCTION_NAME="${LAMBDA_FUNCTION_NAME:-oficina-auth-lambda-lab}"
LAMBDA_VPC_ID="${LAMBDA_VPC_ID:-}"
LAMBDA_SECURITY_GROUP_NAME="${LAMBDA_SECURITY_GROUP_NAME:-${LAMBDA_FUNCTION_NAME}-sg}"
LAMBDA_LOG_GROUP_NAME="${LAMBDA_LOG_GROUP_NAME:-/aws/lambda/${LAMBDA_FUNCTION_NAME}}"
NETWORK_INTERFACE_WAIT_SECONDS="${NETWORK_INTERFACE_WAIT_SECONDS:-600}"
NETWORK_INTERFACE_POLL_SECONDS="${NETWORK_INTERFACE_POLL_SECONDS:-15}"

usage() {
  cat <<EOF
Uso:
  $(basename "$0")

Remove apenas recursos operacionais da Lambda:
  - funcao Lambda
  - log group /aws/lambda/<funcao>
  - security group dedicado da Lambda, quando estiver sem ENIs
  - regras de entrada no security group do RDS que apontam para o SG da Lambda

Variaveis principais:
  AWS_REGION
  EKS_CLUSTER_NAME
  DB_INSTANCE_IDENTIFIER
  DB_SECURITY_GROUP_IDS      Lista CSV ou JSON, opcional
  DB_PORT                    Porta do banco, opcional
  LAMBDA_FUNCTION_NAME
  LAMBDA_VPC_ID              Override da VPC, opcional
  LAMBDA_SECURITY_GROUP_NAME Default: <LAMBDA_FUNCTION_NAME>-sg
  LAMBDA_LOG_GROUP_NAME      Default: /aws/lambda/<LAMBDA_FUNCTION_NAME>
EOF
}

log() {
  printf '\n[%s] %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*"
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

aws_json() {
  aws --region "${AWS_REGION}" "$@" --output json
}

function_exists() {
  aws --region "${AWS_REGION}" lambda get-function \
    --function-name "${LAMBDA_FUNCTION_NAME}" >/dev/null 2>&1
}

resolve_vpc_id() {
  if [[ -n "${LAMBDA_VPC_ID}" ]]; then
    printf '%s' "${LAMBDA_VPC_ID}"
    return
  fi

  aws_json eks describe-cluster \
    --name "${EKS_CLUSTER_NAME}" \
    --query 'cluster.resourcesVpcConfig.vpcId' 2>/dev/null |
    jq -r '. // empty'
}

resolve_lambda_security_group_id() {
  local vpc_id="$1"
  local from_function=""

  if function_exists; then
    from_function="$(
      aws_json lambda get-function-configuration \
        --function-name "${LAMBDA_FUNCTION_NAME}" \
        --query 'VpcConfig.SecurityGroupIds' |
        jq -r '.[]?' |
        head -n 1
    )"
  fi

  if [[ -n "${from_function}" ]]; then
    printf '%s' "${from_function}"
    return
  fi

  if [[ -z "${vpc_id}" ]]; then
    return
  fi

  aws --region "${AWS_REGION}" ec2 describe-security-groups \
    --filters "Name=vpc-id,Values=${vpc_id}" "Name=group-name,Values=${LAMBDA_SECURITY_GROUP_NAME}" \
    --query 'SecurityGroups[0].GroupId' \
    --output text 2>/dev/null |
    sed 's/^None$//'
}

resolve_db_security_groups_and_port() {
  DB_SECURITY_GROUP_IDS="$(normalize_list "${DB_SECURITY_GROUP_IDS}")"

  if [[ -n "${DB_SECURITY_GROUP_IDS}" && -n "${DB_PORT}" ]]; then
    return
  fi

  local db_json=""
  if ! db_json="$(aws_json rds describe-db-instances \
    --db-instance-identifier "${DB_INSTANCE_IDENTIFIER}" \
    --query 'DBInstances[0].{port:Endpoint.Port,securityGroupIds:VpcSecurityGroups[].VpcSecurityGroupId}' 2>/dev/null)"; then
    return
  fi

  if [[ -z "${DB_PORT}" ]]; then
    DB_PORT="$(jq -r '.port // empty' <<<"${db_json}")"
  fi

  if [[ -z "${DB_SECURITY_GROUP_IDS}" ]]; then
    DB_SECURITY_GROUP_IDS="$(jq -r '.securityGroupIds | join(",")' <<<"${db_json}")"
  fi
}

revoke_db_ingress_from_lambda() {
  local lambda_sg_id="$1"

  resolve_db_security_groups_and_port

  if [[ -z "${lambda_sg_id}" || -z "${DB_SECURITY_GROUP_IDS}" || -z "${DB_PORT}" ]]; then
    log "Sem dados suficientes para revogar regras do RDS; seguindo"
    return
  fi

  for db_group_id in ${DB_SECURITY_GROUP_IDS//,/ }; do
    log "Revogando acesso ${lambda_sg_id} -> ${db_group_id}:${DB_PORT}"
    set +e
    output="$(
      aws --region "${AWS_REGION}" ec2 revoke-security-group-ingress \
        --group-id "${db_group_id}" \
        --ip-permissions "IpProtocol=tcp,FromPort=${DB_PORT},ToPort=${DB_PORT},UserIdGroupPairs=[{GroupId=${lambda_sg_id}}]" 2>&1
    )"
    status=$?
    set -e

    if [[ ${status} -ne 0 ]] && ! grep -Eq "InvalidPermission.NotFound|InvalidGroup.NotFound" <<<"${output}"; then
      echo "${output}" >&2
      exit "${status}"
    fi
  done
}

delete_lambda_function() {
  if ! function_exists; then
    log "Lambda ${LAMBDA_FUNCTION_NAME} nao existe; seguindo"
    return
  fi

  log "Removendo Lambda ${LAMBDA_FUNCTION_NAME}"
  aws --region "${AWS_REGION}" lambda delete-function \
    --function-name "${LAMBDA_FUNCTION_NAME}"

  while function_exists; do
    log "Aguardando remocao da Lambda ${LAMBDA_FUNCTION_NAME}"
    sleep 5
  done
}

delete_log_group() {
  local log_group_name=""
  log_group_name="$(
    aws --region "${AWS_REGION}" logs describe-log-groups \
      --log-group-name-prefix "${LAMBDA_LOG_GROUP_NAME}" \
      --query "logGroups[?logGroupName==\`${LAMBDA_LOG_GROUP_NAME}\`].logGroupName" \
      --output text 2>/dev/null || true
  )"

  if [[ -z "${log_group_name}" || "${log_group_name}" == "None" ]]; then
    log "Log group ${LAMBDA_LOG_GROUP_NAME} nao encontrado; seguindo"
    return
  fi

  log "Removendo log group ${LAMBDA_LOG_GROUP_NAME}"
  aws --region "${AWS_REGION}" logs delete-log-group \
    --log-group-name "${LAMBDA_LOG_GROUP_NAME}" >/dev/null
}

list_security_group_network_interfaces() {
  local security_group_id="$1"

  aws --region "${AWS_REGION}" ec2 describe-network-interfaces \
    --filters "Name=group-id,Values=${security_group_id}" \
    --query 'NetworkInterfaces[].NetworkInterfaceId' \
    --output text 2>/dev/null || true
}

wait_for_security_group_release() {
  local security_group_id="$1"
  local deadline=$((SECONDS + NETWORK_INTERFACE_WAIT_SECONDS))
  local interface_ids=""

  while true; do
    interface_ids="$(list_security_group_network_interfaces "${security_group_id}")"

    if [[ -z "${interface_ids}" || "${interface_ids}" == "None" ]]; then
      return
    fi

    if (( SECONDS >= deadline )); then
      echo "O security group ${security_group_id} ainda possui ENIs apos ${NETWORK_INTERFACE_WAIT_SECONDS}s: ${interface_ids}" >&2
      exit 1
    fi

    log "Aguardando liberacao de ENIs do security group ${security_group_id}: ${interface_ids}"
    sleep "${NETWORK_INTERFACE_POLL_SECONDS}"
  done
}

delete_lambda_security_group() {
  local security_group_id="$1"

  if [[ -z "${security_group_id}" ]]; then
    log "Security group da Lambda nao encontrado; seguindo"
    return
  fi

  wait_for_security_group_release "${security_group_id}"

  log "Removendo security group ${security_group_id} (${LAMBDA_SECURITY_GROUP_NAME})"
  set +e
  output="$(
    aws --region "${AWS_REGION}" ec2 delete-security-group \
      --group-id "${security_group_id}" 2>&1
  )"
  status=$?
  set -e

  if [[ ${status} -ne 0 ]] && ! grep -Eq "InvalidGroup.NotFound" <<<"${output}"; then
    echo "${output}" >&2
    exit "${status}"
  fi
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

require_cmd aws
require_cmd jq
require_cmd sed
require_non_empty "${AWS_REGION}" "AWS_REGION"
require_non_empty "${LAMBDA_FUNCTION_NAME}" "LAMBDA_FUNCTION_NAME"

aws --region "${AWS_REGION}" sts get-caller-identity >/dev/null

vpc_id="$(resolve_vpc_id)"
lambda_sg_id="$(resolve_lambda_security_group_id "${vpc_id}")"

revoke_db_ingress_from_lambda "${lambda_sg_id}"
delete_lambda_function
delete_log_group
delete_lambda_security_group "${lambda_sg_id}"

log "Cleanup concluido para ${LAMBDA_FUNCTION_NAME}"
