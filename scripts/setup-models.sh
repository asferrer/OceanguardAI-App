#!/usr/bin/env bash
# setup-models.sh — Download RT-DETRv2 detector models from the latest release.
# Verifies SHA256 against models/CHECKSUMS.txt. Idempotent.
set -euo pipefail

REPO="${OCEANGUARD_REPO:-asferrer/OceanguardAI}"
MODELS_DIR="android/app/src/main/assets/models"
CHECKSUMS_FILE="models/CHECKSUMS.txt"
BASE_URL="https://github.com/${REPO}/releases/latest/download"

MODELS=(
  "rtdetrv2_detector.tflite"
  "rtdetrv2_detector_int8.tflite"
)

mkdir -p "${MODELS_DIR}"

for model in "${MODELS[@]}"; do
  dest="${MODELS_DIR}/${model}"
  if [[ -f "${dest}" ]]; then
    echo "[setup] Skipping (exists): ${model}"
    continue
  fi
  echo "[setup] Downloading: ${model}"
  curl -fL --progress-bar "${BASE_URL}/${model}" -o "${dest}"
done

if [[ ! -f "${CHECKSUMS_FILE}" ]]; then
  echo "[setup] CHECKSUMS.txt not found - skipping integrity check"
  echo "[setup] Models ready. Run: cd android && ./gradlew installDebug"
  exit 0
fi

SHA256_CMD="sha256sum"
command -v sha256sum >/dev/null 2>&1 || SHA256_CMD="shasum -a 256"

echo "[setup] Verifying SHA256 checksums against ${CHECKSUMS_FILE}..."
fail=0
while IFS= read -r line; do
  [[ -z "${line}" ]] && continue
  expected=$(echo "${line}" | awk '{print $1}')
  filename=$(echo "${line}" | awk '{print $NF}')
  filepath="${MODELS_DIR}/${filename}"
  [[ ! -f "${filepath}" ]] && continue
  actual=$(${SHA256_CMD} "${filepath}" | awk '{print $1}')
  if [[ "${actual}" == "${expected}" ]]; then
    echo "[setup] OK: ${filename}"
  else
    echo "[ERROR] Checksum mismatch for ${filename}"
    echo "  expected: ${expected}"
    echo "  actual:   ${actual}"
    echo "  Delete the file and re-run this script."
    fail=1
  fi
done < "${CHECKSUMS_FILE}"

[[ "${fail}" -eq 0 ]] || exit 1

echo "[setup] Models ready. Run: cd android && ./gradlew installDebug"
