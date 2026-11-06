#!/bin/sh
# Compiles the declarations of kotsql-transport-ktor's openssl.def, and the constants of OpenSslStream.kt, against the
# real OpenSSL headers. A wrong prototype or constant in there would otherwise show up only as a crash at run time.
set -eu
root=$(cd "$(dirname "$0")/.." && pwd)
docker run --rm -v "$root/kotsql-transport-ktor/src/nativeInterop/cinterop/openssl.def:/openssl.def:ro" debian:stable-slim sh -euc '
export DEBIAN_FRONTEND=noninteractive
apt="-o Acquire::http::Timeout=30 -o Acquire::Retries=3"
apt-get $apt update -qq >/dev/null && apt-get $apt install -qq -y gcc libssl-dev >/dev/null 2>&1
{
  echo "#include <openssl/ssl.h>"
  echo "#include <openssl/x509v3.h>"
  echo "#include <openssl/err.h>"
  sed "1,/^---$/d" /openssl.def | grep -v "^typedef"
  cat <<C
_Static_assert(SSL_VERIFY_NONE == 0 && SSL_VERIFY_PEER == 1, "verify modes");
_Static_assert(SSL_ERROR_SSL == 1 && SSL_ERROR_WANT_READ == 2 && SSL_ERROR_WANT_WRITE == 3, "errors");
_Static_assert(SSL_ERROR_SYSCALL == 5 && SSL_ERROR_ZERO_RETURN == 6, "errors");
_Static_assert(SSL_CTRL_SET_MIN_PROTO_VERSION == 123 && TLS1_2_VERSION == 0x0303, "min version");
_Static_assert(SSL_CTRL_SET_TLSEXT_HOSTNAME == 55 && TLSEXT_NAMETYPE_host_name == 0, "sni");
_Static_assert(X509_CHECK_FLAG_NO_PARTIAL_WILDCARDS == 4 && X509_V_OK == 0 && OPENSSL_VERSION == 0, "misc");
_Static_assert(SSL_FILETYPE_PEM == 1 && NID_md5 == 4 && NID_sha1 == 64 && EVP_MAX_MD_SIZE == 64, "certificates");
_Static_assert(sizeof(size_t) == sizeof(unsigned long), "size_t");
C
} > /t.c
gcc -fsyntax-only -Werror -D_GNU_SOURCE /t.c
echo "Declarations match the headers."
'
