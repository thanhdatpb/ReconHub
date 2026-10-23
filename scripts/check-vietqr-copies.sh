#!/usr/bin/env bash
# VietQR is built twice, in ReconHub (server/core) and in the legacy OMS, because each Docker image only sees its own
# directory. Fails when the two test-vector files differ, or the two VietQr.java differ in anything but the package line.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
server="$root/server/core/src"
oms="$root/legacy-oms/src"

if ! cmp -s "$server/test/resources/vietqr/vectors.csv" "$oms/test/resources/vietqr/vectors.csv"; then
  echo "vietqr/vectors.csv differs between server/core and legacy-oms; keep the two copies identical" >&2
  exit 1
fi
if ! diff <(grep -v '^package ' "$server/main/java/vn/reconhub/core/vietqr/VietQr.java" | tr -d '\r') \
          <(grep -v '^package ' "$oms/main/java/vn/shop/oms/vietqr/VietQr.java" | tr -d '\r') >&2; then
  echo "VietQr.java differs between server/core and legacy-oms (apart from the package line)" >&2
  exit 1
fi
echo "VietQR copies match"
