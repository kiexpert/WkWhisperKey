#!/usr/bin/env bash
set -e

TYPE="$1"      # core | build
ACTION="$2"    # restore | save
PREFIX="wk${TYPE}"
BASE_DIR=$(pwd)
CACHE_PATHS=""
HASH_FILE=".cachehash-${TYPE}.txt"

# 📁 캐시 경로 정의
if [ "$TYPE" = "core" ]; then
  CACHE_PATHS="/usr/local/lib/android/sdk/ndk /usr/local/lib/android/sdk/cmake"
else
  CACHE_PATHS="~/.gradle/caches android/app/build/intermediates android/app/.cxx"
fi

echo "♻️  ${ACTION^} ${TYPE} cache for key prefix '${PREFIX}-'..."

# 🔍 최신 캐시 정보 탐색 (키, ID, 크기, 생성시간)
CACHE_INFO=$(gh cache list --limit 1 --order desc --json id,key,sizeInBytes,createdAt \
  | jq -r ".[] | select(.key|startswith(\"${PREFIX}-\")) | \"\(.key)|\(.id)|\(.sizeInBytes)|\(.createdAt)\"" || true)

if [ -n "$CACHE_INFO" ]; then
  IFS="|" read -r LATEST_KEY LATEST_ID LATEST_SIZE LATEST_TIME <<< "$CACHE_INFO"
  SIZE_MB=$(awk "BEGIN {printf \"%.1f\", ${LATEST_SIZE}/1024/1024}")
  echo "📦  Latest cache found:"
  echo "    • Key: ${LATEST_KEY}"
  echo "    • ID: ${LATEST_ID}"
  echo "    • Size: ${SIZE_MB} MB"
  echo "    • Created: ${LATEST_TIME}"
else
  echo "⚠️  No existing cache found for ${PREFIX}"
  LATEST_KEY=""
fi

echo "LATEST_KEY=${LATEST_KEY}" >> "$GITHUB_ENV"

# 🔢 해시 계산 함수 (경로 + 크기)
calc_hash() {
  local paths="$1"
  find $paths -type f -printf "%p %s\n" 2>/dev/null | sort | sha256sum | cut -d ' ' -f1
}

# 🧮 새 해시 계산 (restore 모드는 스킵)
if [ "$ACTION" != "restore" ]; then
  echo "🔍 Calculating content hash for ${TYPE}..."
  NEW_HASH=$(calc_hash "$CACHE_PATHS")
  echo "$NEW_HASH" > "$HASH_FILE"
  echo "NEW_HASH=$NEW_HASH" >> "$GITHUB_ENV"
fi

# ♻️ RESTORE 모드
if [ "$ACTION" = "restore" ]; then
  echo "RESTORE_KEY=${LATEST_KEY}" >> "$GITHUB_ENV"
  echo "🔎 Restore mode complete — no recompression."
  exit 0
fi

# 💾 SAVE 모드
echo "💾 Checking ${TYPE} cache changes..."
OLD_HASH=$(cat "$HASH_FILE" 2>/dev/null || echo "none")

if [ "$OLD_HASH" != "$NEW_HASH" ]; then
  echo "🧠 Change detected → deleting old caches (except latest)..."

  gh cache list --json id,key | jq -r '.[] | "\(.id) \(.key)"' | while read -r ID KEY; do
    if [[ "$KEY" == ${PREFIX}-* && "$KEY" != "$LATEST_KEY" ]]; then
      echo "🗑  Deleting cache ID $ID ($KEY)..."
      yes | gh cache delete "$ID" || true
    fi
  done
else
  echo "✅ No cache change detected for ${TYPE}."
  exit 0
fi

# 🧠 새 키 생성
NEW_KEY="${PREFIX}-${NEW_HASH:0:12}"
echo "💾 Saving new cache: ${NEW_KEY}"

# ✅ 실제 저장
gh cache upload "$NEW_KEY" $CACHE_PATHS || true

echo "✅ ${TYPE^} cache saved successfully."
