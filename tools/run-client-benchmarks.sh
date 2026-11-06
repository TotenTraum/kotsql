#!/bin/sh
# Measures the Kotlin client with the same code on the JVM and as a native release binary, both in Linux containers beside
# the server, and writes benchmarks/RESULTS-native.md.
#
# Usage: run-client-benchmarks.sh [warmup iterations seconds rounds [scenario...]]
set -eu
cd "$(dirname "$0")/.."
warmup=${1:-3}; iterations=${2:-5}; seconds=${3:-2}; rounds=${4:-2}
[ $# -ge 4 ] && shift 4 || shift $#
case "$(uname -m)" in
  arm64|aarch64) target=LinuxArm64; dir=linuxArm64; platform=linux/arm64 ;;
  *) target=LinuxX64; dir=linuxX64; platform=linux/amd64 ;;
esac
out=benchmarks/client-results.txt
mkdir -p benchmarks

trap 'docker compose down -v >/dev/null 2>&1' EXIT
docker compose up -d --wait >/dev/null
pg=$(docker compose ps -q postgres)
# Not localhost, which the official image trusts without a password.
ip=$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "$pg")

./gradlew ":ksql-benchmark-client:linkReleaseExecutable$target" :ksql-benchmark-client:installJvm -q
binary=ksql-benchmark-client/build/bin/$dir/releaseExecutable
env="--network container:$pg -e BENCH_HOST=$ip -e BENCH_PORT=5432"

native() {
  docker run --rm --platform "$platform" $env -v "$PWD/$binary:/b:ro" debian:stable-slim \
    /b/ksql-benchmark-client.kexe "$@" | grep RESULT
}
jvm() {
  docker run --rm $env -v "$PWD/ksql-benchmark-client/build/install/jvm:/lib-bench:ro" eclipse-temurin:17-jdk \
    java -Xmx1g -cp '/lib-bench/*' dev.ksql.benchmark.client.MainKt "$@" | grep RESULT
}

: > "$out"
echo "RESULT size native binaryBytes $(wc -c < "$binary/ksql-benchmark-client.kexe")" >> "$out"
round=0
while [ "$round" -lt "$rounds" ]; do
  round=$((round + 1))
  native epoll disable "$warmup" "$iterations" "$seconds" "$@" | tee -a "$out"
  native ktor disable "$warmup" "$iterations" "$seconds" "$@" | tee -a "$out"
  jvm ktor disable "$warmup" "$iterations" "$seconds" "$@" | tee -a "$out"
  jvm async disable "$warmup" "$iterations" "$seconds" "$@" | tee -a "$out"
  native epoll require "$warmup" "$iterations" "$seconds" selectOne select1000Rows connectPerQuery | tee -a "$out"
  jvm ktor require "$warmup" "$iterations" "$seconds" selectOne select1000Rows connectPerQuery | tee -a "$out"
  jvm async require "$warmup" "$iterations" "$seconds" selectOne select1000Rows connectPerQuery | tee -a "$out"
done
python3 tools/benchmark-client-report.py "$out" benchmarks/RESULTS-native.md
