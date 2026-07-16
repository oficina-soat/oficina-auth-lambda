#!/usr/bin/env bash
set -euo pipefail

SOURCE_DATABASE_URL="${SOURCE_DATABASE_URL:-}"
TARGET_DATABASE_URL="${TARGET_DATABASE_URL:-}"
TARGET_DATABASE_USER="${TARGET_DATABASE_USER:-oficina_auth_user}"

usage() {
  cat <<'EOF'
Migra os dados de autenticacao para o banco exclusivo sem remover a origem.

Variaveis obrigatorias:
  SOURCE_DATABASE_URL  URL PostgreSQL administrativa do banco atual (app)
  TARGET_DATABASE_URL  URL PostgreSQL administrativa do banco oficina_auth

Variaveis opcionais:
  TARGET_DATABASE_USER Role proprietaria do destino (default: oficina_auth_user)

O destino deve estar vazio. O script restaura schema e dados, transfere ownership,
valida a quantidade de linhas das tabelas de autenticacao e preserva a origem para rollback.
EOF
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "Comando obrigatorio ausente: $1" >&2
    exit 1
  }
}

if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
  usage
  exit 0
fi

require_cmd pg_dump
require_cmd pg_restore
require_cmd psql
[[ -n "${SOURCE_DATABASE_URL}" ]] || { echo "SOURCE_DATABASE_URL e obrigatoria." >&2; exit 1; }
[[ -n "${TARGET_DATABASE_URL}" ]] || { echo "TARGET_DATABASE_URL e obrigatoria." >&2; exit 1; }
[[ "${SOURCE_DATABASE_URL}" != "${TARGET_DATABASE_URL}" ]] || { echo "Origem e destino devem ser diferentes." >&2; exit 1; }

target_tables="$(psql "${TARGET_DATABASE_URL}" -XAtqc "SELECT count(*) FROM pg_tables WHERE schemaname = 'public'")"
if [[ "${target_tables}" != "0" ]]; then
  echo "Migracao cancelada: o schema public do destino nao esta vazio (${target_tables} tabelas)." >&2
  exit 1
fi

dump_file="$(mktemp --suffix=.dump)"
trap 'rm -f "${dump_file}"' EXIT

auth_relations=(pessoa pessoa_seq papel papel_seq usuario usuario_seq usuario_papel auth_consumed_event auth_activation_token)
dump_args=()
for relation in "${auth_relations[@]}"; do
  relation_exists="$(psql "${SOURCE_DATABASE_URL}" -XAtqc "SELECT to_regclass('public.${relation}') IS NOT NULL")"
  [[ "${relation_exists}" == "t" ]] && dump_args+=(--table="public.${relation}")
done
if [[ "${#dump_args[@]}" == "0" ]]; then
  echo "Migracao cancelada: nenhuma relacao de autenticacao foi encontrada na origem." >&2
  exit 1
fi

pg_dump "${SOURCE_DATABASE_URL}" --format=custom --no-owner --no-acl "${dump_args[@]}" --file="${dump_file}"
pg_restore --dbname="${TARGET_DATABASE_URL}" --no-owner --no-acl --exit-on-error "${dump_file}"

psql "${TARGET_DATABASE_URL}" -Xv ON_ERROR_STOP=1 --set=target_user="${TARGET_DATABASE_USER}" <<'SQL'
SELECT format('ALTER TABLE %I.%I OWNER TO %I', schemaname, tablename, :'target_user')
FROM pg_tables WHERE schemaname = 'public' \gexec
SELECT format('ALTER SEQUENCE %I.%I OWNER TO %I', sequence_schema, sequence_name, :'target_user')
FROM information_schema.sequences WHERE sequence_schema = 'public' \gexec
GRANT USAGE ON SCHEMA public TO :"target_user";
GRANT SELECT, INSERT, UPDATE, DELETE, TRIGGER, REFERENCES ON ALL TABLES IN SCHEMA public TO :"target_user";
GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA public TO :"target_user";
SQL

tables=(pessoa papel usuario usuario_papel auth_consumed_event auth_activation_token)
for table in "${tables[@]}"; do
  source_exists="$(psql "${SOURCE_DATABASE_URL}" -XAtqc "SELECT to_regclass('public.${table}') IS NOT NULL")"
  [[ "${source_exists}" == "t" ]] || continue
  source_count="$(psql "${SOURCE_DATABASE_URL}" -XAtqc "SELECT count(*) FROM public.${table}")"
  target_count="$(psql "${TARGET_DATABASE_URL}" -XAtqc "SELECT count(*) FROM public.${table}")"
  if [[ "${source_count}" != "${target_count}" ]]; then
    echo "Validacao falhou em ${table}: origem=${source_count}, destino=${target_count}." >&2
    exit 1
  fi
  printf 'Validado %-24s origem=%s destino=%s\n' "${table}" "${source_count}" "${target_count}"
done

echo "Migracao concluida. A origem foi preservada para rollback."
