#!/bin/sh
# Runs kotsql-transport-ktor's client and TLS tests on the JVM, as a native binary in a Linux container and, on a Mac with Xcode,
# natively on macOS.
set -eu
cd "$(dirname "$0")/.."
case "$(uname -m)" in
  arm64|aarch64) target=LinuxArm64; dir=linuxArm64; platform=linux/arm64 ;;
  *) target=LinuxX64; dir=linuxX64; platform=linux/amd64 ;;
esac

certs=$(mktemp -d)
trap 'docker compose --profile mysql --profile replica down -v >/dev/null 2>&1; rm -rf "$certs"' EXIT
docker compose --profile mysql up -d --wait >/dev/null
pg=$(docker compose ps -q postgres)
mysql=$(docker compose --profile mysql ps -q mysql)
mysql_ip=$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "$mysql")
docker cp -q "$pg:/etc/ssl/certs/ssl-cert-snakeoil.pem" "$certs/root.pem"
docker compose exec -T postgres openssl req -x509 -newkey rsa:2048 -nodes -keyout /dev/null -subj /CN=unrelated -days 1 2>/dev/null > "$certs/other.pem"

# Roles with md5 secrets, reached through md5 from anywhere: the entry goes first, ahead of the image's trust for localhost.
docker compose exec -T -u postgres postgres sh -c \
  'sed -i "1i host all kotsql_md5,kotsql_мд5 all md5" "$PGDATA/pg_hba.conf"'
docker compose exec -T -e PGCLIENTENCODING=UTF8 postgres psql -q -U kotsql -d kotsql -v ON_ERROR_STOP=1 >/dev/null <<'SQL'
-- As typed on something else than what the tests use: a no-break space and a soft hyphen, and a decomposed letter.
CREATE ROLE kotsql_prep LOGIN PASSWORD U&'pa\00ADss\00A0word';
CREATE ROLE kotsql_nfkc LOGIN PASSWORD U&'caf\00E9';
SET password_encryption = 'md5';
CREATE ROLE kotsql_md5 LOGIN PASSWORD 'kotsql';
CREATE ROLE "kotsql_мд5" LOGIN PASSWORD 'пароль';
SELECT pg_reload_conf();
SQL

docker compose exec -T -u postgres postgres sh -c \
  'sed -i "1i host replication all all scram-sha-256" "$PGDATA/pg_hba.conf"'

# A CA of its own for client certificates, one with an RSA key and one with an EC key.
docker compose exec -T -u postgres postgres sh -euc '
  mkdir -p /tmp/pki && cd /tmp/pki
  openssl req -x509 -newkey rsa:2048 -nodes -keyout ca.key -out ca.crt -subj /CN=kotsql-test-ca -days 2 2>/dev/null
  openssl req -newkey rsa:2048 -nodes -keyout rsa.key -out rsa.csr -subj /CN=kotsql_cert 2>/dev/null
  openssl x509 -req -in rsa.csr -CA ca.crt -CAkey ca.key -CAcreateserial -out rsa.crt -days 2 2>/dev/null
  openssl req -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes -keyout ec.key -out ec.csr -subj /CN=kotsql_cert 2>/dev/null
  openssl x509 -req -in ec.csr -CA ca.crt -CAkey ca.key -CAcreateserial -out ec.crt -days 2 2>/dev/null
  sed -i "1i hostssl all kotsql_cert all cert" "$PGDATA/pg_hba.conf"
'
for f in rsa.crt rsa.key ec.crt ec.key; do docker cp -q "$pg:/tmp/pki/$f" "$certs/$f"; done
docker cp -q "$mysql:/var/lib/mysql/client-cert.pem" "$certs/mysql.crt"
docker cp -q "$mysql:/var/lib/mysql/client-key.pem" "$certs/mysql.key"
chmod 644 "$certs"/*
docker compose exec -T postgres psql -q -U kotsql -d kotsql -v ON_ERROR_STOP=1 >/dev/null <<'SQL'
CREATE ROLE kotsql_cert LOGIN;
ALTER SYSTEM SET ssl_ca_file = '/tmp/pki/ca.crt';
SQL
docker compose --profile mysql exec -T mysql mysql -uroot -pkotsql -e \
  "CREATE USER kotsql_x509 IDENTIFIED BY 'kotsql' REQUIRE X509; GRANT ALL ON kotsql.* TO kotsql_x509" 2>/dev/null
docker compose exec -T postgres psql -q -U kotsql -d kotsql -c 'SELECT pg_reload_conf()' >/dev/null

# The failover test promotes the standby, so each platform gets a new one.
new_standby() {
  docker compose --profile replica rm -sf postgres-standby >/dev/null 2>&1
  docker compose --profile replica up -d --wait postgres-standby >/dev/null 2>&1
}

new_standby
echo "== Module boundaries"
./gradlew checkModuleBoundaries -q

echo "== SQLite"
./gradlew :kotsql-sqlite:jvmTest -q
if [ "$(uname -s)" = Darwin ] && [ "$(uname -m)" = arm64 ] && xcodebuild -version >/dev/null 2>&1; then
  ./gradlew :kotsql-sqlite:macosArm64Test -q
fi
./gradlew ":kotsql-sqlite:linkDebugTest$target" -q
docker run --rm --platform "$platform" -v "$PWD/kotsql-sqlite/build/bin/$dir/debugTest:/t:ro" debian:stable-slim /t/test.kexe | tail -1

echo "== JVM"
KOTSQL_TEST_URL='postgresql://kotsql:kotsql@localhost:55432/kotsql?sslmode=disable' \
KOTSQL_TEST_SCRAM_URL='postgresql://kotsql:kotsql@localhost:55432/kotsql' KOTSQL_TEST_MD5_ADDRESS=localhost:55432 KOTSQL_TEST_MYSQL_HOST_PORT=localhost:53306 \
KOTSQL_TEST_PRIMARY=localhost:55432 KOTSQL_TEST_STANDBY=localhost:55433 KOTSQL_TEST_CERTS="$certs" \
KOTSQL_TEST_NFKC=1 KOTSQL_TEST_TLS_PORT=55432 KOTSQL_TEST_TLS_ROOT="$certs/root.pem" KOTSQL_TEST_TLS_OTHER_ROOT="$certs/other.pem" \
  ./gradlew :kotsql-transport-ktor:cleanJvmTest :kotsql-transport-ktor:jvmTest -q

# Directly on this machine, where a full Xcode is there to link it.
if [ "$(uname -s)" = Darwin ] && [ "$(uname -m)" = arm64 ] && xcodebuild -version >/dev/null 2>&1; then
  echo "== Native (macosArm64)"
  ./gradlew :kotsql-transport-ktor:linkDebugTestMacosArm64 -q
  new_standby
  KOTSQL_TEST_URL='postgresql://kotsql:kotsql@localhost:55432/kotsql?sslmode=disable' \
  KOTSQL_TEST_SCRAM_URL='postgresql://kotsql:kotsql@localhost:55432/kotsql' KOTSQL_TEST_MD5_ADDRESS=localhost:55432 KOTSQL_TEST_MYSQL_HOST_PORT=localhost:53306 \
  KOTSQL_TEST_PRIMARY=localhost:55432 KOTSQL_TEST_STANDBY=localhost:55433 KOTSQL_TEST_CERTS="$certs" \
  KOTSQL_TEST_NFKC=1 KOTSQL_TEST_TLS_PORT=55432 KOTSQL_TEST_TLS_ROOT="$certs/root.pem" KOTSQL_TEST_TLS_OTHER_ROOT="$certs/other.pem" \
    kotsql-transport-ktor/build/bin/macosArm64/debugTest/test.kexe "$@"
fi

echo "== Native ($dir)"
./gradlew ":kotsql-transport-ktor:linkDebugTest$target" -q
# Sharing the server's network makes it reachable as localhost, the one name in its certificate. The server trusts
# localhost without a password, so the SCRAM tests go through its IP address.
ip=$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "$pg")
new_standby
standby_ip=$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "$(docker compose --profile replica ps -q postgres-standby)")
docker run --rm --platform "$platform" --network "container:$pg" \
  -e KOTSQL_TEST_URL='postgresql://kotsql:kotsql@localhost:5432/kotsql?sslmode=disable' \
  -e KOTSQL_TEST_SCRAM_URL="postgresql://kotsql:kotsql@$ip:5432/kotsql" -e KOTSQL_TEST_MD5_ADDRESS=localhost:5432 -e KOTSQL_TEST_MYSQL_HOST_PORT="$mysql_ip:3306" \
  -e KOTSQL_TEST_PRIMARY="$ip:5432" -e KOTSQL_TEST_STANDBY="$standby_ip:5432" -e KOTSQL_TEST_CERTS=/certs \
  -e KOTSQL_TEST_TLS_PORT=5432 -e KOTSQL_TEST_TLS_ROOT=/certs/root.pem -e KOTSQL_TEST_TLS_OTHER_ROOT=/certs/other.pem \
  -e KOTSQL_TEST_TLS_OTHER_HOST="$(docker inspect -f '{{.Config.Hostname}}' "$pg")" \
  -v "$certs:/certs:ro" -v "$PWD/kotsql-transport-ktor/build/bin/$dir/debugTest:/t:ro" \
  debian:stable-slim /t/test.kexe "$@"
