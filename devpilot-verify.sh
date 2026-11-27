#!/usr/bin/env bash
# Compile Reveil et lance tous les tests automatisés (sans Docker ni service externe).
# Sort avec un code non nul au moindre échec.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "== Backend (Java 17, Maven, MongoDB embarqué) =="
# MongoDB embarqué 4.4 : requiert libcrypto.so.1.1. À défaut : MongoDB 7 si le CPU a AVX,
# sinon libssl1.1 (paquet Debian 11) extrait dans un cache hors dépôt (LIBSSL11_DIR pour en fournir un).
if ! ldconfig -p 2>/dev/null | grep -q 'libcrypto.so.1.1' && [ -z "${EMBEDDED_MONGO_VERSION:-}" ]; then
  if grep -qw avx /proc/cpuinfo 2>/dev/null; then
    export EMBEDDED_MONGO_VERSION=7.0.14
  else
    LIBSSL11_DIR="${LIBSSL11_DIR:-${XDG_CACHE_HOME:-$HOME/.cache}/reveil/libssl1.1}"
    LIB="$LIBSSL11_DIR/usr/lib/x86_64-linux-gnu"
    if [ ! -f "$LIB/libcrypto.so.1.1" ]; then
      echo "libssl1.1 absent : téléchargement dans $LIBSSL11_DIR"
      mkdir -p "$LIBSSL11_DIR"
      curl -sSfL -o "$LIBSSL11_DIR/libssl.deb" \
        https://deb.debian.org/debian/pool/main/o/openssl/libssl1.1_1.1.1w-0+deb11u1_amd64.deb
      python3 -I - "$LIBSSL11_DIR" <<'PY'
import io, sys, tarfile
dest = sys.argv[1]
data = open(dest + "/libssl.deb", "rb").read()
assert data[:8] == b"!<arch>\n"
i = 8
while i < len(data):
    name = data[i:i + 16].decode().strip()
    size = int(data[i + 48:i + 58])
    if name.startswith("data.tar"):
        t = tarfile.open(fileobj=io.BytesIO(data[i + 60:i + 60 + size]), mode="r:*")
        for m in t.getmembers():
            if m.isfile() and ".." not in m.name and not m.name.startswith("/"):
                t.extract(m, dest)
    i += 60 + size + (size % 2)
PY
    fi
    export LD_LIBRARY_PATH="$LIB${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
  fi
fi
(cd "$ROOT/backend" && mvn -B -q test)

echo "== Web (Angular) =="
cd "$ROOT/web"
[ -d node_modules ] || npm ci --no-audit --no-fund
npx ng test --watch=false
npx ng build --configuration production --base-href /test/reveil/

echo "== Mobile (React Native / Expo) =="
cd "$ROOT/mobile"
[ -d node_modules ] || npm ci --no-audit --no-fund
npm test -- --ci
npm run typecheck

echo "OK : compilation et tests réussis."
