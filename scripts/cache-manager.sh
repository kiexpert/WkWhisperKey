#!/usr/bin/env bash
set -e

MODE=$1        # restore | save
TYPE=$2        # core | build
BRANCH=$3      # optional for build
GH_TOKEN=$4

CACHE_PATH_CORE="/usr/local/lib/android/sdk"
CACHE_PATH_BUILD="$HOME/.gradle/caches android/app/build/intermediates android/app/.cxx"
HASHFILE_DIR=".cachehash"
mkdir -p "$HASHFILE_DIR"

case "$TYPE" in
  core)
    KEY_PREFIX="wkcore"
    CACHE_PATH="$CACHE_PATH_CORE"
    ;;
  build)
    KEY_PREFIX="wkbuild-${BRANCH}"
    CACHE_PATH="$CACHE_PATH_BUILD"
    ;;
  *)
    echo "❌ Unknown cache type: $TYPE"; exit 1;;
esac

HASHFILE="$HASHFILE_DIR/${KEY_PREFIX}-nameshash.txt"
TMPHASH="${HASHFILE}.new"

# 🔹 Generate current hash
find $CACHE_PATH -type f -printf "%P\n" | sort | sha256sum | cut -d ' ' -f1 > "$TMPHASH"

if [ "$MODE" = "restore" ]; then
  echo "🔍 Searching latest cache for $KEY_PREFIX..."
  gh cache list --limit 1000 --json id,key,lastAccessedAt |
    jq -r --arg prefix "$KEY_PREFIX" '.[] | select(.key | startswith($prefix)) | [.id,.key,.lastAccessedAt] | @tsv' |
    sort -k3 -r | tee ${HASHFILE_DIR}/${KEY_PREFIX}-all.txt | head -n1 > ${HASHFILE_DIR}/${KEY_PREFIX}-latest.txt

  if [ -s ${HASHFILE_DIR}/${KEY_PREFIX}-latest.txt ]; then
    LATEST_KEY=$(cut -f2 ${HASHFILE_DIR}/${KEY_PREFIX}-latest.txt)
    echo "✅ Restoring cache: $LATEST_KEY"
    echo "restore_key=$LATEST_KEY" >> $GITHUB_OUTPUT
  else
    echo "⚠️ No existing cache found for $KEY_PREFIX."
  fi
fi

if [ "$MODE" = "save" ]; then
  NEW_HASH=$(cat "$TMPHASH")
  OLD_HASH=$(cat "$HASHFILE" 2>/dev/null || echo "none")

  if [ "$NEW_HASH" != "$OLD_HASH" ]; then
    echo "🧠 Change detected. Old=$OLD_HASH New=$NEW_HASH"
    echo "$NEW_HASH" > "$HASHFILE"

    echo "🧹 Deleting old caches for $KEY_PREFIX..."
    gh cache list --json id,key | jq -r --arg prefix "$KEY_PREFIX" \
      '.[] | select(.key | startswith($prefix)) | .id' | while read id; do
        gh cache delete "$id" --confirm || true
      done

    echo "💾 Saving new cache..."
    CACHE_KEY="${KEY_PREFIX}-${GITHUB_RUN_ID}"
    tar --posix -cf cache.tzst -P -C "$PWD" $CACHE_PATH --use-compress-program zstdmt
    gh cache upload "$CACHE_KEY" cache.tzst
  else
    echo "✅ No changes detected, skip saving."
  fi
fi
