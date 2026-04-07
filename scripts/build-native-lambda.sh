#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

./mvnw clean package -Pnative-aws "$@"

ARTIFACT="target/function.zip"
OUTPUT="target/oficina-auth-lambda-native.zip"

if [[ ! -f "$ARTIFACT" ]]; then
  echo "Artefato nativo nao encontrado em $ARTIFACT" >&2
  exit 1
fi

cp "$ARTIFACT" "$OUTPUT"
echo "Pacote nativo gerado em $OUTPUT"
