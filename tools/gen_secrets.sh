#!/bin/sh
# Generates the relay's TLS certificate, HMAC secret and firmware/relay/secrets.h (git-ignored).
# Usage: tools/gen_secrets.sh "<wifi ssid>" "<wifi password>" <relay-ip>
set -eu
[ $# -eq 3 ] || { echo "usage: $0 ssid password relay-ip" >&2; exit 1; }
out=firmware/relay; tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
openssl req -x509 -newkey rsa:2048 -nodes -days 3650 -subj "/CN=door-relay" -addext "subjectAltName=IP:$3" -keyout "$tmp/k.pem" -out "$tmp/c.pem" 2>/dev/null
openssl x509 -in "$tmp/c.pem" -outform DER -out "$tmp/c.der"
openssl pkey -in "$tmp/k.pem" -outform DER -out "$tmp/k.der"
secret=$(openssl rand -hex 32)
pin=$(openssl dgst -sha256 -binary "$tmp/c.der" | od -An -tx1 | tr -d ' \n')
arr() { od -An -v -tx1 "$1" | sed 's/ \([0-9a-f][0-9a-f]\)/0x\1,/g'; }
{
  echo "#pragma once"; echo "#include <stdint.h>"
  echo "#define WIFI_SSID \"$1\""; echo "#define WIFI_PASS \"$2\""
  printf 'const uint8_t HMAC_SECRET[32] = {'; echo "$secret" | sed 's/\(..\)/0x\1,/g'; echo '};'
  echo "const uint8_t CERT_DER[] = {"; arr "$tmp/c.der"; echo "};"
  echo "const uint8_t KEY_DER[] = {"; arr "$tmp/k.der"; echo "};"
} > "$out/secrets.h"
echo "Wrote $out/secrets.h (do not commit or share it)."
echo "Enter these in the app Setup screen:"
echo "  Relay IP:   $3"
echo "  Pin:        $pin"
echo "  Secret:     $secret"
