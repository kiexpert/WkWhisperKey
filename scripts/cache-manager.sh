#!/usr/bin/env bash
set -euo pipefail

TYPE="$1"       # core | build
ACTION="$2"     # restore | save
GITHUB_OUTPUT="${3:-/dev/null}"
PREFIX="wk${TYPE}"
CACHE_PATHS=""

# ────────────────────────────────────────────────
# 🧱 1. SDK 환경 자동 세팅
# ────────────────────────────────────────────────
if [ "$TYPE" = "core" ]; then
  SDK_ROOT="$HOME/.android-sdk"
  mkdir -p "$SDK_ROOT"
  echo "📁 SDK root ensured at $SDK_ROOT"
  echo "ANDROID_SDK_ROOT=$SDK_ROOT" >> "$GITHUB_ENV"
  echo "ANDROID_HOME=$SDK_ROOT" >> "$GITHUB_ENV"

  # 기존 시스템 라이선스 복사
  mkdir -p "$SDK_ROOT/licenses"
  if [ -d /usr/local/lib/android/sdk/licenses ]; then
    cp -r /usr/local/lib/android/sdk/licenses/* "$SDK_ROOT/licenses/" 2>/dev/null || true
    echo "📜 Copied SDK licenses from system SDK."
  fi

  CACHE_PATHS="$SDK_ROOT"
else
  CACHE_PATHS="$HOME/.gradle/caches
android/app/build/intermediates
android/app/.cxx"
fi

# 🔊 경로 출력 (멀티라인)
echo "cache_path<<EOF" >> "$GITHUB_OUTPUT"
echo "$CACHE_PATHS" >> "$GITHUB_OUTPUT"
echo "EOF" >> "$GITHUB_OUTPUT"

echo "♻️  ${ACTION^} ${TYPE} cache for prefix '${PREFIX}-'"

# ────────────────────────────────────────────────
# 🔍 2. 최신 캐시 검색
# ────────────────────────────────────────────────
CACHE_INFO=$(gh cache list --limit 10 --order desc --json id,key,sizeInBytes,createdAt 2>/dev/null \
  | jq -r ".[] | select(.key|startswith(\"${PREFIX}-\")) | \"\(.key)|\(.id)|\(.sizeInBytes)|\(.createdAt)\"" \
  | head -n 1 || true)

LATEST_KEY=""
if [ -n "$CACHE_INFO" ]; then
  IFS="|" read -r LATEST_KEY LATEST_ID LATEST_SIZE LATEST_TIME <<< "$CACHE_INFO"
  SIZE_MB=$(awk "BEGIN {printf \"%.1f\", ${LATEST_SIZE}/1048576}")
  echo "📦  Found cache: ${LATEST_KEY} (${SIZE_MB} MB, ${LATEST_TIME})"
else
  echo "⚠️  No cache found for ${PREFIX}"
fi
echo "restore_key=${LATEST_KEY}" >> "$GITHUB_OUTPUT"

# ────────────────────────────────────────────────
# 🔢 3. 해시 계산 함수
# ────────────────────────────────────────────────
calc_hash() {
  local p="$1"
  find $p -maxdepth 3 -type f -printf "%p %s\n" 2>/dev/null | sort | sha256sum | cut -d ' ' -f1
}

# ♻️ 4. 복원 모드
if [ "$ACTION" = "restore" ]; then
  if [ "$TYPE" = "core" ]; then
    echo "🔧 Ensuring SDK write permissions..."
    chmod -R 755 "$HOME/.android-sdk" || true
  fi
  echo "✅ Restore mode complete."
  exit 0
fi

# ────────────────────────────────────────────────
# 💾 5. 저장 모드
# ────────────────────────────────────────────────
echo "🔍 Calculating hash for ${TYPE}..."
NEW_HASH=$(calc_hash "$CACHE_PATHS" || echo "0")
NEW_KEY="${PREFIX}-${NEW_HASH:0:12}"
OLD_HASH="${LATEST_KEY#${PREFIX}-}"

if [ "$OLD_HASH" = "${NEW_HASH:0:12}" ] && [ -n "$OLD_HASH" ]; then
  echo "✅ No cache changes for ${TYPE}"
  exit 0
fi

echo "🧠 Change detected → new key: ${NEW_KEY}"
echo "save_key=${NEW_KEY}" >> "$GITHUB_OUTPUT"

# core 캐시 저장 전 라이선스 및 권한 정리
if [ "$TYPE" = "core" ]; then
  echo "📜 Refreshing SDK licenses..."
  yes | "$HOME/.android-sdk/cmdline-tools/latest/bin/sdkmanager" --licenses >/dev/null 2>&1 || true
  echo "🔧 Fixing permissions before save..."
  chmod -R 755 "$HOME/.android-sdk" || true
fi

# 오래된 캐시 정리
if [ -n "$LATEST_KEY" ]; then
  gh cache list --json id,key 2>/dev/null | jq -r '.[] | "\(.id) \(.key)"' | while read -r ID KEY; do
    if [[ "$KEY" == ${PREFIX}-* && "$KEY" != "$LATEST_KEY" ]]; then
      echo "🗑  Deleting old cache $KEY"
      gh cache delete "$ID" || true
    fi
  done
fi

echo "💾 Ready to upload new cache: ${NEW_KEY}"
