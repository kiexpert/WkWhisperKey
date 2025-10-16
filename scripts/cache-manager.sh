#!/usr/bin/env bash
set -euo pipefail

TYPE="$1"       # core | build
ACTION="$2"     # restore | save
GITHUB_OUTPUT="${3:-/dev/null}"
PREFIX="wk${TYPE}"
CACHE_PATHS=""

# 📁 캐시 경로 설정
if [ "$TYPE" = "core" ]; then
  CACHE_PATHS="/usr/local/lib/android/sdk/cmake"
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

# 🔍 최신 캐시 조회
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

# step output
echo "restore_key=${LATEST_KEY}" >> "$GITHUB_OUTPUT"

# 🔢 해시 계산 함수 (경로+크기, depth 제한)
calc_hash() {
  local p="$1"
  find $p -maxdepth 2 -type f -printf "%p %s\n" 2>/dev/null | sort | sha256sum | cut -d ' ' -f1
}

# ♻️ 복원 모드
if [ "$ACTION" = "restore" ]; then
  echo "✅ Restore mode complete."
  exit 0
fi

# 💾 저장 모드
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

# 👇 여기 추가
if [ "$TYPE" = "core" ]; then
  echo "🔧 Fixing CMake permissions before save..."
  chmod -R 755 /usr/local/lib/android/sdk/cmake || true
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
