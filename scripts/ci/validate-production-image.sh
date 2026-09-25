#!/bin/sh
set -eu

if [ "$#" -ne 2 ]; then
  printf 'usage: %s IMAGE_TAG EXPECTED_REVISION\n' "$0" >&2
  exit 2
fi

image_tag=$1
expected_revision=$2

architecture=$(docker image inspect "$image_tag" --format '{{.Architecture}}')
os=$(docker image inspect "$image_tag" --format '{{.Os}}')
user=$(docker image inspect "$image_tag" --format '{{.Config.User}}')
revision=$(docker image inspect "$image_tag" --format '{{index .Config.Labels "org.opencontainers.image.revision"}}')
entrypoint=$(docker image inspect "$image_tag" --format '{{json .Config.Entrypoint}}')
healthcheck=$(docker image inspect "$image_tag" --format '{{json .Config.Healthcheck.Test}}')

test "$architecture" = "arm64"
test "$os" = "linux"
test "$user" = "10001:10001"
test "$revision" = "$expected_revision"
test "$entrypoint" = '["java","-jar","application.jar"]'

case "$healthcheck" in
  *http://127.0.0.1:8080/readyz*) ;;
  *)
    printf 'production health check does not target /readyz\n' >&2
    exit 1
    ;;
esac

docker run --rm --entrypoint sh "$image_tag" -c '
  test "$(id -u):$(id -g)" = "10001:10001"
  command -v java >/dev/null
  ! command -v mvn >/dev/null
  ! command -v javac >/dev/null
  ! command -v jar >/dev/null
'

printf 'validated image=%s os=%s architecture=%s user=%s revision=%s\n' \
  "$image_tag" "$os" "$architecture" "$user" "$revision"
printf 'native ARM64 image was validated locally and was not pushed\n'
