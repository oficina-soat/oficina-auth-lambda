#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JWT_DIR="$ROOT_DIR/src/main/resources/jwt"
PRIVATE_KEY="$JWT_DIR/privateKey.pem"
PUBLIC_KEY="$JWT_DIR/publicKey.pem"

mkdir -p "$JWT_DIR"

openssl genpkey -algorithm RSA -out "$PRIVATE_KEY" -pkeyopt rsa_keygen_bits:2048
openssl pkey -in "$PRIVATE_KEY" -pubout -out "$PUBLIC_KEY"

chmod 600 "$PRIVATE_KEY"

echo "Chaves JWT de desenvolvimento geradas em $JWT_DIR"
