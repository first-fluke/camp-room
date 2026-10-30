#!/usr/bin/env bash
set -euo pipefail

task_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
release_env="${CAMP_RELEASE_ENV_FILE:-$task_root/.env.release.local}"
if [[ -f "$release_env" ]]; then
  set -a
  source "$release_env"
  set +a
fi

for required in CAMP_RELEASE_STORE_FILE CAMP_RELEASE_STORE_PASSWORD CAMP_RELEASE_KEY_ALIAS CAMP_RELEASE_KEY_PASSWORD CAMP_API_BASE_URL; do
  if [[ -z "${!required:-}" ]]; then
    echo "Missing $required. Configure .env.release.local before building a release APK." >&2
    exit 1
  fi
done
if [[ ! -f "$CAMP_RELEASE_STORE_FILE" ]]; then
  echo "The configured release keystore does not exist." >&2
  exit 1
fi
case "$CAMP_API_BASE_URL" in
  *example.invalid*|*YOUR_*|*your-api*)
    echo "Configure a real CAMP_API_BASE_URL for the release APK." >&2
    exit 1
    ;;
  https://*) ;;
  *) echo "CAMP_API_BASE_URL must use HTTPS; local HTTP is supported only by the debug variant." >&2; exit 1 ;;
esac

export ORG_GRADLE_PROJECT_campApiBaseUrl="$CAMP_API_BASE_URL"
cd "$task_root"
if [[ -n "${CAMP_GRADLE_BIN:-}" ]]; then
  exec "$CAMP_GRADLE_BIN" -p apps/pleos :app:assemblePleosRelease "$@"
fi
exec bash apps/pleos/gradlew -p apps/pleos :app:assemblePleosRelease "$@"
