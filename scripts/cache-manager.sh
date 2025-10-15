#!/usr/bin/env bash
set -e
TYPE=$1      # core or build
ACTION=$2    # restore or save
PREFIX="wk${TYPE}"
CACHE_PATH="/usr/local/lib/android/sdk"

echo "♻️ Restoring ${TYPE} cache for key prefix '${PREFIX}-'..."

if [ "$ACTION" = "restore" ]; then
  echo "🔎 Searching for latest cache..."
  LIST=$(gh cache list --key "${PREFIX}-" --json id,key,sizeInBytes,createdAt --limit 1 --sort createdAt --order DESC 2>/dev/null || echo "[]")
  if [[ "$LIST" == "[]" ]]; then
    echo "⚠️ No existing cache found for ${PREFIX}"
    exit 0
  fi
  KEY=$(echo "$LIST" | jq -r '.[0].key')
  SIZE=$(echo "$LIST" | jq -r '.[0].sizeInBytes')
  DATE=$(echo "$LIST" | jq -r '.[0].createdAt')
  echo "📦 Found cache:"
  echo "• Key: $KEY"
  echo "• Size: $((SIZE / 1048576)) MB"
  echo "• Created: $DATE"
  echo "restore_key=$KEY" >> "$GITHUB_OUTPUT"
  exit 0
fi

if [ "$ACTION" = "save" ]; then
  echo "🔍 Calculating content hash for ${TYPE}..."
  HASH=$(find $CACHE_PATH -maxdepth 3 -type f -exec sha1sum {} + 2>/dev/null \
    | sort | sha1sum | cut -d' ' -f1 || echo "none")
  NEW_KEY="${PREFIX}-${HASH}"

  echo "💾 Checking ${TYPE} cache changes..."
  EXIST=$(gh cache list --key "${PREFIX}-" --json key --limit 1 | jq -r '.[0].key' 2>/dev/null || echo "")

  if [ "$EXIST" != "$NEW_KEY" ]; then
    echo "🧠 Change detected → deleting old caches..."
    OLD_LIST=$(gh cache list --key "${PREFIX}-" --json id,key,createdAt --limit 50 2>/dev/null || echo "[]")
    echo "$OLD_LIST" | jq -r '.[].id' | while read -r CID; do
      [ -n "$CID" ] && gh cache.delete "$CID" --yes || true
    done
  else
    echo "✅ No change. Skipping save."
    exit 0
  fi

  echo "💾 Saving new cache: $NEW_KEY"
  echo "save_key=$NEW_KEY" >> "$GITHUB_OUTPUT"
fi
