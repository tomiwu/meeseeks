#!/usr/bin/env bash
# setup.sh — one-step local setup for Meeseeks.
# Run once after cloning: ./setup.sh
# Then start the server: sbt "runtime/run"
#
# Options:
#   --check   Validate required tools and config; make no changes.
set -euo pipefail

CERTS_DIR="./certs"
JOURNAL_DIR="./data/journal"
CERT_PASSWORD="meeseeks-dev"

# --check mode: validate tools and config, make no changes.
if [[ "${1:-}" == "--check" ]]; then
  echo "setup.sh --check: validating tools and config"
  ok=true

  for cmd in docker keytool; do
    if command -v "$cmd" > /dev/null 2>&1; then
      echo "  found: $cmd"
    else
      echo "  ERROR: $cmd not found on PATH"
      ok=false
    fi
  done

  if [[ ! -f .env.example ]]; then
    echo "  ERROR: .env.example not found"
    ok=false
  else
    echo "  found: .env.example"
  fi

  if docker compose config > /dev/null 2>&1; then
    echo "  docker compose config: OK"
  else
    echo "  ERROR: docker compose config failed"
    ok=false
  fi

  if [[ "$ok" == "true" ]]; then
    echo "setup.sh --check: OK"
    exit 0
  else
    echo "setup.sh --check: FAILED"
    exit 1
  fi
fi

# 1. Copy .env.example to .env when .env is missing. Never overwrite.
if [[ ! -f .env ]]; then
  cp .env.example .env
  echo "setup: created .env from .env.example — fill in MEESEEKS_TELEGRAM_BOT_TOKEN and MEESEEKS_OWNER_ID"
else
  echo "setup: .env already exists, skipping copy"
fi

# 2. Create journal directory with restricted permissions.
mkdir -p "$JOURNAL_DIR"
chmod 700 "$JOURNAL_DIR"
echo "setup: journal directory ready at $JOURNAL_DIR"

# 3. Generate mTLS certs into certs/ using keytool.
# Skips generation when server.p12 already exists (idempotent).
if [[ -f "$CERTS_DIR/server.p12" ]]; then
  echo "setup: certs already exist in $CERTS_DIR, skipping generation"
else
  mkdir -p "$CERTS_DIR"
  echo "setup: generating mTLS certs in $CERTS_DIR"

  for alias in server worker; do
    keytool -genkeypair -alias "$alias" -keyalg RSA -keysize 2048 -validity 3650 \
      -dname "CN=$alias" \
      -keystore "$CERTS_DIR/$alias.p12" -storetype PKCS12 \
      -storepass "$CERT_PASSWORD" -keypass "$CERT_PASSWORD" -noprompt
  done

  for alias in server worker; do
    keytool -exportcert -alias "$alias" \
      -keystore "$CERTS_DIR/$alias.p12" -storepass "$CERT_PASSWORD" \
      -file "$CERTS_DIR/$alias.crt" -rfc
  done

  # Server truststore: trusts server and worker certs.
  for alias in server worker; do
    keytool -importcert -noprompt -alias "$alias" \
      -keystore "$CERTS_DIR/server-trust.p12" -storetype PKCS12 \
      -storepass "$CERT_PASSWORD" -file "$CERTS_DIR/$alias.crt"
  done

  # Worker truststore: trusts server cert only.
  keytool -importcert -noprompt -alias server \
    -keystore "$CERTS_DIR/worker-trust.p12" -storetype PKCS12 \
    -storepass "$CERT_PASSWORD" -file "$CERTS_DIR/server.crt"

  echo "setup: certs generated in $CERTS_DIR"
fi

# 4. Start Docker Compose services (journal-init and cert-helper).
echo "setup: starting compose services"
docker compose up -d
echo "setup: compose services started"

echo ""
echo "Setup complete. Next step:"
echo "  sbt \"runtime/run\""
