#!/usr/bin/env bash
set -euo pipefail

# Usage:
#   bash scripts/cache-manager.sh <core|build> <restore|save>

TYPE="${1:?Missing type (core|build)}"
ACTION="${2:?Missing action (restore|save)}"
GH_TOKEN="${GH_TOKEN:-${GITHUB_TOKEN:-}}"

if [ "$TYPE" = "core" ]; then
  CACHE_PATHS=(
    "/usr/local/lib/android/sdk/ndk"
    "/usr/local/lib/android/sdk/cmake"
  )
  PREFIX="wkcore"
else
  CACHE_PATHS=(
    "$HOME/.gradle/caches"
    "android/app/build"
    "android/app/.cxx"
  )
  PREFIX="wkbuild-${GITHUB_REF_NAME}"
fi

echo "🔍 Calculating content hash for ${TYPE}..."
TMP_LIST=$(mktemp)
for d in "${CACHE_PATHS[@]}"; do
  [ -d "$d" ] && find "$d" -type f -printf '%P\t%s\t%T@\n'
done | LC_ALL=C sort > "$TMP_LIST" || true

HASH=$( [ -s "$TMP_LIST" ] && sha256sum "$TMP_LIST" | cut -d ' ' -f1 || echo "none" )
rm -f "$TMP_LIST"
NEW_KEY="${PREFIX}-${HASH:0:12}"

if [ "$ACTION" = "restore" ]; then
  echo "♻️ Restoring ${TYPE} cache for key prefix '${PREFIX}-'..."
  echo "restore_key=${NEW_KEY}" >> "$GITHUB_OUTPUT"
  exit 0
fi

if [ "$ACTION" = "save" ]; then
  echo "💾 Checking ${TYPE} cache changes..."
  PREV_KEYS=$(gh cache list --json id,key | jq -r ".[].key" | grep "^${PREFIX}-" || true)
  LATEST_KEY=$(echo "$PREV_KEYS" | sort | tail -1 || true)

  if [ "$LATEST_KEY" != "$NEW_KEY" ]; then
    echo "🧠 Change detected → deleting old caches..."
    IDS=$(gh cache list --json id,key | jq -r --arg p "${PREFIX}-" '.[] | select(.key|startswith($p)) | .id')
    for id in $IDS; do
      gh cache delete "$id" || true
    done
    echo "💾 Saving new cache: $NEW_KEY"
    echo "key=$NEW_KEY" >> "$GITHUB_OUTPUT"
  else
    echo "✅ No change detected for ${TYPE} cache."
  fi
fi
