#!/usr/bin/env bash
# 跑既有用例 test/blobcache/BlobCodecTest.java。起点应 14/14 全绿。
set -euo pipefail

cd "$(dirname "$0")/.."

bash scripts/build.sh >/dev/null

java -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 \
    -cp out blobcache.BlobCodecTest
