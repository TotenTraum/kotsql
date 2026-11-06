#!/bin/sh
# Runs SoakTest for the given number of seconds per database, on the JVM and as a native binary on this machine's OS.
#   tools/run-soak.sh 3600
set -eu
cd "$(dirname "$0")/.."
seconds=${1:-300}

# Runs a command showing only the results, once each. A failure must fail this script, which a pipe into the filter
# would hide.
results() {
  log=$(mktemp)
  status=0
  "$@" > "$log" 2>&1 || status=$?
  grep -E 'operations,|FAILED|PASSED|Failed:' "$log" | awk '!seen[$0]++' | head -20 || true
  [ "$status" -eq 0 ] || tail -20 "$log"
  rm -f "$log"
  return "$status"
}

trap 'docker compose --profile mysql down -v >/dev/null 2>&1' EXIT
docker compose --profile mysql up -d --wait >/dev/null
export KOTSQL_SOAK_SECONDS="$seconds" KOTSQL_TEST_MYSQL_HOST_PORT=localhost:53306
export KOTSQL_TEST_URL='postgresql://kotsql:kotsql@localhost:55432/kotsql?sslmode=require'

echo "== JVM"
results ./gradlew :kotsql-transport-ktor:jvmTest --tests '*SoakTest*' --rerun -i

if [ "$(uname -s)" = Darwin ] && [ "$(uname -m)" = arm64 ] && xcodebuild -version >/dev/null 2>&1; then
  echo "== Native (macosArm64)"
  ./gradlew :kotsql-transport-ktor:linkDebugTestMacosArm64 -q
  results kotsql-transport-ktor/build/bin/macosArm64/debugTest/test.kexe --ktest_filter='*SoakTest*'
fi

case "$(uname -m)" in
  arm64|aarch64) target=LinuxArm64; dir=linuxArm64; platform=linux/arm64 ;;
  *) target=LinuxX64; dir=linuxX64; platform=linux/amd64 ;;
esac
echo "== Native ($dir)"
./gradlew ":kotsql-transport-ktor:linkDebugTest$target" -q
pg=$(docker compose ps -q postgres)
mysql_ip=$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "$(docker compose --profile mysql ps -q mysql)")
results docker run --rm --platform "$platform" --network "container:$pg" \
  -e KOTSQL_SOAK_SECONDS="$seconds" -e KOTSQL_TEST_MYSQL_HOST_PORT="$mysql_ip:3306" \
  -e KOTSQL_TEST_URL='postgresql://kotsql:kotsql@localhost:5432/kotsql?sslmode=require' \
  -v "$PWD/kotsql-transport-ktor/build/bin/$dir/debugTest:/t:ro" \
  debian:stable-slim /t/test.kexe --ktest_filter='*SoakTest*'
