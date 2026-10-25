#!/usr/bin/env bash
# Only the preflight reports missing variable names. Never print credential values.
set -euo pipefail

case "${1:-}" in
  check)
    names=(
      FACTOTUM_KEYSTORE_BASE64
      FACTOTUM_KEYSTORE_PASSWORD
      FACTOTUM_KEY_ALIAS
      FACTOTUM_KEY_PASSWORD
      FACTOTUM_GMAIL_CLIENT_ID
      FACTOTUM_GMAIL_CLIENT_SECRET
    )
    missing=0
    for name in "${names[@]}"; do
      if [ -z "${!name:-}" ]; then
        echo "::error::缺少 release environment secret $name"
        missing=1
      fi
    done
    exit "$missing"
    ;;
  restore)
    umask 077
    keystore=$(mktemp "${RUNNER_TEMP:?}/release.XXXXXX.jks")
    if ! printf '%s' "${FACTOTUM_KEYSTORE_BASE64:-}" | base64 --decode > "$keystore" || [ ! -s "$keystore" ]; then
      rm -f "$keystore"
      echo '::error::发布 keystore 不是有效的非空 base64 文件'
      exit 1
    fi
    printf 'FACTOTUM_KEYSTORE_FILE=%s\nTAPPET_KEYSTORE_FILE=%s\n' "$keystore" "$keystore" >> "${GITHUB_ENV:?}"
    ;;
  assemble)
    # Alias only at build time; never rewrite the target tag's Gradle or applicationId.
    for suffix in KEYSTORE_FILE KEYSTORE_PASSWORD KEY_ALIAS KEY_PASSWORD GMAIL_CLIENT_ID GMAIL_CLIENT_SECRET; do
      name="FACTOTUM_$suffix"
      export "TAPPET_$suffix=${!name}"
    done
    exec ./gradlew :app:assembleRelease --console=plain
    ;;
  *) echo 'usage: release-secrets.sh check|restore|assemble' >&2; exit 2 ;;
esac
