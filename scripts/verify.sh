#!/usr/bin/env bash
# Builds and tests the modules inside Docker containers, so the machine only needs Docker (no JDK, Maven or Node).
#
#   scripts/verify.sh            server + web + legacy OMS, with integration tests
#   scripts/verify.sh server     one part: server | web | oms
#   scripts/verify.sh --no-it    skip the Testcontainers integration tests (they need the Docker socket)
#   scripts/verify.sh eval       matching precision on the running stack (docker compose up first)
#
# Maven and npm downloads are kept in the Docker volumes reconhub-m2 and reconhub-web-modules.
set -euo pipefail
export MSYS_NO_PATHCONV=1 # Git Bash on Windows: keep /src, /root/.m2 and the socket path as written

root="$(cd "$(dirname "$0")/.." && (pwd -W 2>/dev/null || pwd))"
parts=()
it=true
for arg in "$@"; do
  case "$arg" in
    --no-it) it=false ;;
    server | web | oms | eval) parts+=("$arg") ;;
    *) echo "unknown argument: $arg" >&2; exit 2 ;;
  esac
done
[ ${#parts[@]} -eq 0 ] && parts=(server web oms)

for part in "${parts[@]}"; do
  echo "== $part"
  case "$part" in
    server)
      socket=()
      # Testcontainers inside a container reaches its databases through the host; host.docker.internal works on
      # Docker Desktop (Windows, macOS) and, with host-gateway, on Linux, where the default bridge IP is not always reachable.
      $it && socket=(-v /var/run/docker.sock:/var/run/docker.sock -e TESTCONTAINERS_RYUK_DISABLED=true
        --add-host=host.docker.internal:host-gateway -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal)
      docker run --rm "${socket[@]}" --mount "type=bind,source=$root/server,target=/src" -v reconhub-m2:/root/.m2 \
        -w /src maven:3.9-eclipse-temurin-21 mvn -B -ntp verify
      ;;
    web)
      docker run --rm --mount "type=bind,source=$root/web,target=/src" -v reconhub-web-modules:/src/node_modules \
        -w /src node:22-alpine sh -c "npm ci --no-audit --no-fund --loglevel=error && npm run build && npm run build:http"
      ;;
    oms)
      docker run --rm --mount "type=bind,source=$root/legacy-oms,target=/src" -v reconhub-m2:/root/.m2 \
        -w /src maven:3.9-eclipse-temurin-17 mvn -B -ntp package
      ;;
    eval)
      (cd "$root" && docker compose run --rm --entrypoint python datagen eval_matching.py \
        --truth /data/truth/truth.csv --db postgresql://reconhub:reconhub@postgres:5432/reconhub --min-precision 1.0)
      ;;
  esac
done
echo "== all checks passed"
