#!/bin/sh
# Runs ksql-transport-ktor's client and TLS tests on the JVM, as a native binary in a Linux container and, on a Mac with Xcode,
# natively on macOS.
set -eu
cd "$(dirname "$0")/.."
case "$(uname -m)" in
  arm64|aarch64) target=LinuxArm64; dir=linuxArm64; platform=linux/arm64 ;;
  *) target=LinuxX64; dir=linuxX64; platform=linux/amd64 ;;
esac

certs=$(mktemp -d)
trap 'docker compose --profile mysql down -v >/dev/null 2>&1; rm -rf "$certs"' EXIT
docker compose --profile mysql up -d --wait >/dev/null
pg=$(docker compose ps -q postgres)
mysql=$(docker compose --profile mysql ps -q mysql)
mysql_ip=$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "$mysql")
docker cp -q "$pg:/etc/ssl/certs/ssl-cert-snakeoil.pem" "$certs/root.pem"
docker compose exec -T postgres openssl req -x509 -newkey rsa:2048 -nodes -keyout /dev/null -subj /CN=unrelated -days 1 2>/dev/null > "$certs/other.pem"

# Roles with md5 secrets, reached through md5 from anywhere: the entry goes first, ahead of the image's trust for localhost.
docker compose exec -T -u postgres postgres sh -c \
  'sed -i "1i host all ksql_md5,ksql_мд5 all md5" "$PGDATA/pg_hba.conf"'
docker compose exec -T -e PGCLIENTENCODING=UTF8 postgres psql -q -U ksql -d ksql -v ON_ERROR_STOP=1 >/dev/null <<'SQL'
SET password_encryption = 'md5';
CREATE ROLE ksql_md5 LOGIN PASSWORD 'ksql';
CREATE ROLE "ksql_мд5" LOGIN PASSWORD 'пароль';
SELECT pg_reload_conf();
SQL

echo "== JVM"
KSQL_TEST_URL='postgresql://ksql:ksql@localhost:55432/ksql?sslmode=disable' \
KSQL_TEST_SCRAM_URL='postgresql://ksql:ksql@localhost:55432/ksql' KSQL_TEST_MD5_ADDRESS=localhost:55432 KSQL_TEST_MYSQL_HOST_PORT=localhost:53306 \
KSQL_TEST_TLS_PORT=55432 KSQL_TEST_TLS_ROOT="$certs/root.pem" KSQL_TEST_TLS_OTHER_ROOT="$certs/other.pem" \
  ./gradlew :ksql-transport-ktor:cleanJvmTest :ksql-transport-ktor:jvmTest -q

# Directly on this machine, where a full Xcode is there to link it.
if [ "$(uname -s)" = Darwin ] && [ "$(uname -m)" = arm64 ] && xcodebuild -version >/dev/null 2>&1; then
  echo "== Native (macosArm64)"
  ./gradlew :ksql-transport-ktor:linkDebugTestMacosArm64 -q
  KSQL_TEST_URL='postgresql://ksql:ksql@localhost:55432/ksql?sslmode=disable' \
  KSQL_TEST_SCRAM_URL='postgresql://ksql:ksql@localhost:55432/ksql' KSQL_TEST_MD5_ADDRESS=localhost:55432 KSQL_TEST_MYSQL_HOST_PORT=localhost:53306 \
  KSQL_TEST_TLS_PORT=55432 KSQL_TEST_TLS_ROOT="$certs/root.pem" KSQL_TEST_TLS_OTHER_ROOT="$certs/other.pem" \
    ksql-transport-ktor/build/bin/macosArm64/debugTest/test.kexe "$@"
fi

echo "== Native ($dir)"
./gradlew ":ksql-transport-ktor:linkDebugTest$target" -q
# Sharing the server's network makes it reachable as localhost, the one name in its certificate. The server trusts
# localhost without a password, so the SCRAM tests go through its IP address.
ip=$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "$pg")
docker run --rm --platform "$platform" --network "container:$pg" \
  -e KSQL_TEST_URL='postgresql://ksql:ksql@localhost:5432/ksql?sslmode=disable' \
  -e KSQL_TEST_SCRAM_URL="postgresql://ksql:ksql@$ip:5432/ksql" -e KSQL_TEST_MD5_ADDRESS=localhost:5432 -e KSQL_TEST_MYSQL_HOST_PORT="$mysql_ip:3306" \
  -e KSQL_TEST_TLS_PORT=5432 -e KSQL_TEST_TLS_ROOT=/certs/root.pem -e KSQL_TEST_TLS_OTHER_ROOT=/certs/other.pem \
  -e KSQL_TEST_TLS_OTHER_HOST="$(docker inspect -f '{{.Config.Hostname}}' "$pg")" \
  -v "$certs:/certs:ro" -v "$PWD/ksql-transport-ktor/build/bin/$dir/debugTest:/t:ro" \
  debian:stable-slim /t/test.kexe "$@"
