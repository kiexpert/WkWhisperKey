#!/usr/bin/env bash
set -e

TYPE="$1"        # core or build
ACTION="$2"      # save or restore
PREFIX="wk${TYPE}"
CACHE_PATH_CORE="/usr/local/lib/android/sdk/ndk /usr/local/lib/android/sdk/cmake"
CACHE_PATH_BUILD="$HOME/.gradle/caches android/app/build/intermediates android/app/.cxx"

# 기본 경로 선택
if [ "$TYPE" = "core" ]; then
  CACHE_PATH="$CACHE_PATH_CORE"
else
  CACHE_PATH="$CACHE_PATH_BUILD"
fi

echo "🔍 Calculating content hash for ${TYPE}..."
HASH=$(find $CACHE_PATH -type f -exec sha1sum {} + 2>/dev/null | sort | sha1sum | cut -d' ' -f1 || echo "none")
NEW_KEY="${PREFIX}-${HASH:0:12}"

if [ "$ACTION" = "restore" ]; then
  echo "♻️ Restoring ${TYPE} cache for key prefix '${PREFIX}-'..."
  echo "restore_key=${NEW_KEY}" >> "$GITHUB_OUTPUT"

  # 실제 복구 수행
  echo "🔎 Searching for latest cache..."
  RESTORE_JSON=$(gh cache list --key "${PREFIX}-" --limit 1 --json id,key,createdAt,sizeInBytes 2>/dev/null || echo "[]")

  if [[ "$RESTORE_JSON" == "[]" || -z "$RESTORE_JSON" ]]; then
    echo "⚠️ No existing cache found for ${PREFIX}"
    exit 0
  fi

  RESTORE_ID=$(echo "$RESTORE_JSON" | jq -r '.[0].id')
  RESTORE_KEY=$(echo "$RESTORE_JSON" | jq -r '.[0].key')
  RESTORE_SIZE=$(echo "$RESTORE_JSON" | jq -r '.[0].sizeInBytes')
  RESTORE_TIME=$(echo "$RESTORE_JSON" | jq -r '.[0].createdAt')

  echo "📦 Found cache:"
  echo "• Key: $RESTORE_KEY"
  echo "• ID: $RESTORE_ID"
  echo "• Size: $((RESTORE_SIZE / 1048576)) MB"
  echo "• Created: $RESTORE_TIME"

  echo "⬇️ Downloading cache from GitHub..."
  gh cache restore "$RESTORE_ID" --repo "$GITHUB_REPOSITORY" --dir "$(pwd)" || {
    echo "⚠️ Cache restore failed or partial. Continuing..."
  }

  echo "✅ Cache restored successfully from $RESTORE_KEY"
  exit 0
fi

if [ "$ACTION" = "save" ]; then
  echo "💾 Checking ${TYPE} cache changes..."
  OLD_KEY_FILE=".cache_${TYPE}_key"
  OLD_KEY=$(cat "$OLD_KEY_FILE" 2>/dev/null || echo "none")

  if [ "$OLD_KEY" != "$NEW_KEY" ]; then
    echo "🧠 Change detected → deleting old caches..."
    gh cache delete --all --succeed-on-no-caches --repo "$GITHUB_REPOSITORY" --key "${PREFIX}-" || true

    echo "💾 Saving new cache: ${NEW_KEY}"
    echo "$NEW_KEY" > "$OLD_KEY_FILE"
    tar -cf cache.tzst --use-compress-program zstdmt -P -C "$(pwd)" --files-from <(find $CACHE_PATH -type f)
    gh cache upload --repo "$GITHUB_REPOSITORY" --key "$NEW_KEY" cache.tzst
  else
    echo "🟢 No changes detected for ${TYPE}, skipping save."
  fi
fi
