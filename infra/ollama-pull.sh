#!/usr/bin/env bash
# Pull the local model into the running Ollama container.
#
#   docker compose up -d ollama
#   ./ollama-pull.sh
#
# Reads OLLAMA_MODEL from .env so there is one place to change the model.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="${SCRIPT_DIR}/.env"

if [[ ! -f "${ENV_FILE}" ]]; then
  echo "error: ${ENV_FILE} not found. Run: cp .env.example .env" >&2
  exit 1
fi

set -a
# shellcheck disable=SC1090
source "${ENV_FILE}"
set +a

MODEL="${OLLAMA_MODEL:-llama3.1:8b}"
CONTAINER="${OLLAMA_CONTAINER:-mc-ollama}"

echo "Pulling ${MODEL} into ${CONTAINER}. This is several GB on first run."
docker exec -it "${CONTAINER}" ollama pull "${MODEL}"

echo
echo "Installed models:"
curl -fsS http://localhost:11434/api/tags
