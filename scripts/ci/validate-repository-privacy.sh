#!/bin/sh
set -eu

repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
cd "$repo_root"

exclude='!scripts/ci/validate-repository-privacy.sh'
failed=0

scan() {
  description=$1
  pattern=$2

  set +e
  rg -l -I --hidden \
    --glob '!.git/**' \
    --glob '!target/**' \
    --glob "$exclude" \
    --pcre2 -- "$pattern" .
  status=$?
  set -e

  case "$status" in
    0)
      printf 'possible %s found in the files listed above\n' "$description" >&2
      failed=1
      ;;
    1) ;;
    *)
      printf 'privacy scan failed while checking for %s\n' "$description" >&2
      exit "$status"
      ;;
  esac
}

# Report filenames only so a real credential can never be echoed into CI logs.
scan 'AWS access key' '(?:AKIA|ASIA)[A-Z0-9]{16}'
scan 'private key' '-----BEGIN (?:[A-Z0-9]+ )?PRIVATE KEY-----'
scan 'hardcoded AWS account identifier' 'arn:aws(?:-[a-z]+)?:[^:\s]+:[^:\s]*:[0-9]{12}:'
scan 'JWT bearer token' 'Bearer[[:space:]]+eyJ[A-Za-z0-9_-]+\.eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+'
scan 'non-example email address' '(?i)[a-z0-9.!#$%&*+/=?^_`{|}~-]+@(?!example\.(?:com|invalid)\b)[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?\.[a-z]{2,}'

if [ "$failed" -ne 0 ]; then
  exit 1
fi

printf 'repository privacy and credential validation passed\n'
