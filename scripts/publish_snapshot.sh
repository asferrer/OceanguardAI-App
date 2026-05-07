#!/usr/bin/env bash
# publish_snapshot.sh — Public snapshot for Kaggle Gemma 4 Good Hackathon submission.
#
# Produces a clean single-commit snapshot of OceanGuard AI in a temp directory
# and force-pushes it to `main` of the public repo. The landing page must be
# moved to the `gh-pages` branch BEFORE running this script (manual one-off).
#
# Usage:
#   bash scripts/publish_snapshot.sh --dry-run   # preview without pushing
#   bash scripts/publish_snapshot.sh             # real push
set -euo pipefail

SNAPSHOT_AUTHOR_NAME="Alejandro Sanchez Ferrer"
SNAPSHOT_AUTHOR_EMAIL="60944749+asferrer@users.noreply.github.com"
PUBLIC_REMOTE="https://github.com/asferrer/OceanguardAI.git"
TARGET_BRANCH="main"
PRIVATE_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TIMESTAMP="$(date +%Y%m%d-%H%M%S)"
TMP_DIR="${TMPDIR:-/tmp}/oceanguard-snapshot-${TIMESTAMP}"
DRY_RUN=false

for arg in "$@"; do
  [[ "$arg" == "--dry-run" ]] && DRY_RUN=true
done

echo "[snapshot] DRY_RUN=${DRY_RUN}"
echo "[snapshot] Source: ${PRIVATE_ROOT}"
echo "[snapshot] Target: ${PUBLIC_REMOTE} -> branch ${TARGET_BRANCH}"
echo "[snapshot] Temp dir: ${TMP_DIR}"

cd "${PRIVATE_ROOT}"

if [[ -n "$(git status --porcelain --untracked-files=no)" ]]; then
  echo "[ERROR] Working tree has uncommitted tracked changes. Commit or stash first."
  exit 1
fi

CURRENT_BRANCH="$(git rev-parse --abbrev-ref HEAD)"
if [[ "${CURRENT_BRANCH}" != "main" ]]; then
  echo "[ERROR] Must run on main (currently: ${CURRENT_BRANCH})"
  exit 1
fi

HEAD_SHA="$(git rev-parse HEAD)"
echo "[snapshot] Source HEAD: ${HEAD_SHA}"

mkdir -p "${TMP_DIR}"

rsync -a \
  --exclude='.git/' \
  --exclude='.gitmodules' \
  --exclude='.github/' \
  --exclude='.claude/' \
  --exclude='CLAUDE.md' \
  --exclude='.idea/' \
  --exclude='.kotlin/' \
  --exclude='.cxx/' \
  --exclude='.gradle/' \
  --exclude='build/' \
  --exclude='app/build/' \
  --exclude='*.jks' \
  --exclude='*.keystore' \
  --exclude='local.properties' \
  --exclude='gradle.properties' \
  --exclude='.env*' \
  --exclude='*.tflite' \
  --exclude='*.litertlm' \
  --exclude='*.gguf' \
  --exclude='*.onnx' \
  --exclude='*.pt' \
  --exclude='*.pth' \
  --exclude='*.bin' \
  --exclude='*.task' \
  --exclude='*.param' \
  --exclude='*.safetensors' \
  --exclude='*.apk' \
  --exclude='*.aab' \
  --exclude='*.so' \
  --exclude='*.hprof' \
  --exclude='*.log' \
  --exclude='logcat*.txt' \
  --exclude='__pycache__/' \
  --exclude='*.pyc' \
  --exclude='OceanguardAI/' \
  --exclude='alex.html' \
  --exclude='dumpstate-*.zip' \
  --exclude='graphify-out/' \
  --exclude='docs/DOCKER_SETUP.md' \
  --exclude='docs/HYBRID_SETUP.md' \
  --exclude='docs/VERSIONS_UPDATE.md' \
  --exclude='docs/NEXT_STEPS.md' \
  --exclude='docs/gh-pages/' \
  --exclude='docs/QUICK_START_WINDOWS.md' \
  --exclude='dumpstate*.zip' \
  --exclude='node_modules/' \
  "${PRIVATE_ROOT}/" \
  "${TMP_DIR}/"

# Placeholder for the models/ assets dir (downloaded via setup-models.sh).
mkdir -p "${TMP_DIR}/android/app/src/main/assets/models"
cat > "${TMP_DIR}/android/app/src/main/assets/models/README.md" <<EOF
# Model weights placeholder

The RT-DETRv2 detection models (\`rtdetrv2_detector.tflite\` ~83MB and
\`rtdetrv2_detector_int8.tflite\` ~43MB) are not committed to this repository
to keep clones small. They are published as release assets.

Run from the repository root:

\`\`\`bash
bash setup-models.sh        # Linux / macOS / WSL
# or
./setup-models.ps1          # Windows PowerShell
\`\`\`

This downloads the models from the latest GitHub Release and verifies their
SHA256 against \`models/CHECKSUMS.txt\`.
EOF
touch "${TMP_DIR}/android/app/src/main/assets/models/.gitkeep"

# Generate CHECKSUMS.txt for the .tflite files (read from the private repo).
mkdir -p "${TMP_DIR}/models"
SHA256_CMD="sha256sum"
command -v sha256sum >/dev/null 2>&1 || SHA256_CMD="shasum -a 256"

(
  cd "${PRIVATE_ROOT}/android/app/src/main/assets/models"
  ${SHA256_CMD} rtdetrv2_detector.tflite rtdetrv2_detector_int8.tflite \
    | awk '{print $1"  "$NF}'
) > "${TMP_DIR}/models/CHECKSUMS.txt"

echo "[snapshot] models/CHECKSUMS.txt:"
cat "${TMP_DIR}/models/CHECKSUMS.txt"

# Bring the setup scripts up to the snapshot root for easy access.
cp "${PRIVATE_ROOT}/scripts/setup-models.sh"  "${TMP_DIR}/setup-models.sh"  2>/dev/null || true
cp "${PRIVATE_ROOT}/scripts/setup-models.ps1" "${TMP_DIR}/setup-models.ps1" 2>/dev/null || true
chmod +x "${TMP_DIR}/setup-models.sh" 2>/dev/null || true

cd "${TMP_DIR}"
git init --quiet
git checkout -q -b "${TARGET_BRANCH}"
git config user.name  "${SNAPSHOT_AUTHOR_NAME}"
git config user.email "${SNAPSHOT_AUTHOR_EMAIL}"

git add --all

COMMIT_MSG=$(cat <<EOF
[SNAPSHOT] OceanGuard AI — Kaggle Gemma 4 Good Hackathon submission

On-device marine debris detection for Android using a dual ML pipeline:
RT-DETRv2 (fast object detection, TFLite, CPU/NNAPI) and Gemma 4 E2B via
LiteRT-LM (deep analysis and structured reports with native tool calling).
100% offline, no cloud dependency, Kotlin / Jetpack Compose, 6 languages.

Trained on CleanSea + Ocean_garbage + Neural_Ocean datasets (8 classes:
Bottle, Can, Fishing_Net, Glove, Mask, Metal_Debris, Plastic_Debris, Tire).

Pre-compiled APK and model weights (.tflite) are published as release assets.
Run \`bash setup-models.sh\` (Linux/macOS) or \`./setup-models.ps1\` (Windows)
to download and verify the model files before building from source.

Source HEAD at snapshot time: ${HEAD_SHA}
EOF
)

GIT_AUTHOR_NAME="${SNAPSHOT_AUTHOR_NAME}" \
GIT_AUTHOR_EMAIL="${SNAPSHOT_AUTHOR_EMAIL}" \
GIT_COMMITTER_NAME="${SNAPSHOT_AUTHOR_NAME}" \
GIT_COMMITTER_EMAIL="${SNAPSHOT_AUTHOR_EMAIL}" \
git commit --quiet -m "${COMMIT_MSG}"

echo "[snapshot] Committed:"
git log --oneline -1
git show --stat HEAD | tail -3

git remote add origin "${PUBLIC_REMOTE}"

# Step 1: Preserve current public `main` as `gh-pages` (only if gh-pages missing).
# This protects the landing page that currently lives on `main` of the public repo.
echo "[snapshot] Checking for existing gh-pages branch on remote..."
if git ls-remote --heads origin gh-pages | grep -q "refs/heads/gh-pages"; then
  echo "[snapshot] gh-pages already exists on remote — skipping landing preservation."
else
  echo "[snapshot] gh-pages NOT found on remote — preserving current main as gh-pages."
  # Fetch current remote main into a local ref we can re-push.
  git fetch --quiet origin "refs/heads/main:refs/remotes/origin/main" || {
    echo "[ERROR] Could not fetch remote main from ${PUBLIC_REMOTE}. Abort."
    exit 1
  }
  if [[ "${DRY_RUN}" == "true" ]]; then
    echo "[DRY-RUN] Would push remote main -> remote gh-pages to preserve landing."
  else
    git push origin "refs/remotes/origin/main:refs/heads/gh-pages"
    echo "[snapshot] Landing preserved at refs/heads/gh-pages."
  fi
fi

if [[ "${DRY_RUN}" == "true" ]]; then
  echo "[DRY-RUN] Would force-push to ${PUBLIC_REMOTE} (${TARGET_BRANCH})"
  echo "[DRY-RUN] Inspect ${TMP_DIR} before running again without --dry-run"
  exit 0
fi

echo "[snapshot] Force-pushing to ${PUBLIC_REMOTE} branch ${TARGET_BRANCH}..."
git push --force-with-lease origin "${TARGET_BRANCH}"

echo "[snapshot] Verifying remote tip..."
git ls-remote origin "${TARGET_BRANCH}"

echo "[snapshot] Cleaning up ${TMP_DIR}..."
cd "${PRIVATE_ROOT}"
rm -rf "${TMP_DIR}"

echo "[snapshot] DONE. Snapshot published."
