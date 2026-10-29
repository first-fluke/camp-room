#!/usr/bin/env bash
# Pre-push entry. hk.pkl lists the gates.
#
#   scripts/pre-push.sh
#   scripts/pre-push.sh gate api
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

run_gate() {
  case "$1" in
    branch)
      branch=$(git symbolic-ref --quiet --short HEAD) || {
        echo "pre-push: detached HEAD; cannot validate branch name" >&2
        return 1
      }
      case "$branch" in
        main|develop|feature/?*|fix/?*|hotfix/?*|release/?*) ;;
        *) echo "pre-push: invalid branch name: $branch" >&2; return 1 ;;
      esac
      ;;
    api)
      mise run //apps/api:test
      ;;
    admin)
      mise run //apps/admin:typecheck
      mise run //apps/admin:test
      ;;
    worker)
      mise run //apps/worker:test
      ;;
    pleos)
      mise run //apps/pleos:lint
      mise exec -- uv run --no-sync --no-python-downloads python apps/pleos/verify_contracts.py
      ;;
    *)
      echo "pre-push: unknown gate $1" >&2
      exit 2
      ;;
  esac
}

if [ "${1:-}" = "gate" ]; then
  run_gate "${2:?gate name}"
  exit
fi

mise exec -- hk run pre-push
