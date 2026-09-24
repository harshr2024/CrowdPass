#!/bin/sh
set -eu

repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/../../.." && pwd)
image_tag=${1:-crowdpass-api:phase10b-arm64-local}

docker buildx build \
  --platform linux/arm64 \
  --load \
  --tag "$image_tag" \
  --build-arg APP_VERSION=phase10b-local \
  --build-arg VCS_REVISION="$(git -C "$repo_root" rev-parse HEAD)" \
  "$repo_root"

architecture=$(docker image inspect "$image_tag" --format '{{.Architecture}}')
os=$(docker image inspect "$image_tag" --format '{{.Os}}')
user=$(docker image inspect "$image_tag" --format '{{.Config.User}}')

test "$architecture" = "arm64"
test "$os" = "linux"
test "$user" = "10001:10001"

printf 'validated image=%s os=%s architecture=%s user=%s\n' "$image_tag" "$os" "$architecture" "$user"
printf 'local image only; this script never pushes to a registry\n'
