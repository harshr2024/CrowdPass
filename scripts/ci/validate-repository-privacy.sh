#!/bin/sh
set -eu

repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
cd "$repo_root"

exclude=':(exclude)scripts/ci/validate-repository-privacy.sh'
failed=0

scan() {
  description=$1
  pattern=$2

  set +e
  git grep -I -l -E -e "$pattern" -- . "$exclude"
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
scan 'AWS access key' '(AKIA|ASIA)[A-Z0-9]{16}'
scan 'private key' '-----BEGIN ([A-Z0-9]+ )?PRIVATE KEY-----'
scan 'hardcoded AWS account identifier' 'arn:aws(-[a-z]+)?:[^:[:space:]]+:[^:[:space:]]*:[0-9]{12}:'
scan 'JWT bearer token' 'Bearer[[:space:]]+eyJ[A-Za-z0-9_-]+\.eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+'

emails=$(mktemp)
non_example_emails=$(mktemp)
trap 'rm -f "$emails" "$non_example_emails"' EXIT HUP INT TERM

set +e
git grep -I -n -o -E \
  -e '[a-zA-Z0-9.!#$%&*+/=?^_`{|}~-]+@[a-zA-Z0-9](\.?[a-zA-Z0-9-])*\.[a-zA-Z]{2,}' \
  -- . "$exclude" >"$emails"
email_status=$?
set -e

case "$email_status" in
  0)
    grep -Eiv '@example\.(com|invalid)$' "$emails" | cut -d: -f1 | sort -u >"$non_example_emails"
    if [ -s "$non_example_emails" ]; then
      cat "$non_example_emails"
      printf 'possible non-example email address found in the files listed above\n' >&2
      failed=1
    fi
    ;;
  1) ;;
  *)
    printf 'privacy scan failed while checking for email addresses\n' >&2
    exit "$email_status"
    ;;
esac

if [ "$failed" -ne 0 ]; then
  exit 1
fi

printf 'repository privacy and credential validation passed\n'
