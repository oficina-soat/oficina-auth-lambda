#!/usr/bin/env bash

normalize_lambda_module() {
  case "${1:-}" in
    auth|auth-lambda)
      printf 'auth-lambda'
      ;;
    notificacao|notificacao-lambda)
      printf 'notificacao-lambda'
      ;;
    *)
      return 1
      ;;
  esac
}

all_lambda_modules() {
  printf '%s\n' auth-lambda notificacao-lambda
}

load_lambda_module() {
  local module
  module="$(normalize_lambda_module "${1:-}")" || return 1

  case "${module}" in
    auth-lambda)
      LAMBDA_MODULE="${module}"
      LAMBDA_MODULE_DIR="auth-lambda"
      LAMBDA_RELEASE_BASENAME="oficina-auth-lambda"
      LAMBDA_FUNCTION_NAME_DEFAULT="oficina-auth-lambda-lab"
      LAMBDA_ARTIFACT_PREFIX_DEFAULT="oficina/lab/lambda/oficina-auth-lambda"
      LAMBDA_EXTRA_ENV_JSON_DEFAULT="{}"
      LAMBDA_API_GATEWAY_ROUTE_KEY_DEFAULT="POST /auth"
      LAMBDA_API_GATEWAY_ROUTE_KEYS_DEFAULT="POST /auth;POST /auth/token;GET /.well-known/openid-configuration;GET /.well-known/jwks.json"
      LAMBDA_ENV_PREFIX="AUTH"
      LAMBDA_USES_DATABASE="true"
      LAMBDA_USES_JWT="true"
      LAMBDA_ATTACH_VPC_DEFAULT="true"
      ;;
    notificacao-lambda)
      LAMBDA_MODULE="${module}"
      LAMBDA_MODULE_DIR="notificacao-lambda"
      LAMBDA_RELEASE_BASENAME="oficina-notificacao-lambda"
      LAMBDA_FUNCTION_NAME_DEFAULT="oficina-notificacao-lambda-lab"
      LAMBDA_ARTIFACT_PREFIX_DEFAULT="oficina/lab/lambda/oficina-notificacao-lambda"
      LAMBDA_EXTRA_ENV_JSON_DEFAULT='{"QUARKUS_MAILER_FROM":"noreply@oficina.local","QUARKUS_MAILER_MOCK":"true"}'
      LAMBDA_API_GATEWAY_ROUTE_KEY_DEFAULT="POST /notificacoes/email"
      LAMBDA_API_GATEWAY_ROUTE_KEYS_DEFAULT="POST /notificacoes/email"
      LAMBDA_ENV_PREFIX="NOTIFICACAO"
      LAMBDA_USES_DATABASE="false"
      LAMBDA_USES_JWT="false"
      LAMBDA_ATTACH_VPC_DEFAULT="false"
      ;;
  esac

  LAMBDA_BUILD_DIR="${LAMBDA_MODULE_DIR}/target"
  LAMBDA_NAMED_ARTIFACT_FILENAME="${LAMBDA_RELEASE_BASENAME}-native.zip"
}

lambda_release_asset_name() {
  local module="$1"
  local version="$2"
  local architecture="$3"

  load_lambda_module "${module}" || return 1
  printf '%s-%s-%s.zip' "${LAMBDA_RELEASE_BASENAME}" "${version}" "${architecture}"
}
