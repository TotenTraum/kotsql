#!/bin/sh
# Runs the JMH benchmarks in a Linux container sharing the network of a PostgreSQL container, so that the numbers are
# not dominated by Docker Desktop's port forwarding, and writes benchmarks/RESULTS.md. Arguments go to JMH.
#
# The server's IP address rather than localhost: the official image trusts localhost, which would skip authentication.
set -eu
cd "$(dirname "$0")/.."
out=benchmarks
mkdir -p "$out"

trap 'docker compose down -v >/dev/null 2>&1' EXIT
docker compose up -d --wait >/dev/null
pg=$(docker compose ps -q postgres)
ip=$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "$pg")

./gradlew :kotsql-benchmark:installDist -q

docker run --rm --network "container:$pg" -e BENCH_HOST="$ip" -e BENCH_PORT=5432 \
  -v "$PWD/kotsql-benchmark/build/install/kotsql-benchmark/lib:/lib-bench:ro" -v "$PWD/$out:/out" \
  eclipse-temurin:17-jdk java -cp '/lib-bench/*' org.openjdk.jmh.Main \
  -prof gc -rf json -rff /out/results.json "$@"

docker compose exec -T postgres psql -U kotsql -Atc 'SHOW server_version' > "$out/server-version.txt"
python3 tools/benchmark-report.py "$out"
