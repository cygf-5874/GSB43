#!/usr/bin/env bash
# 固定验收入口：校验 review/REVIEW.md 的格式、可判定性与对 6 条对外保证的覆盖自洽。
# 用法：bash scripts/check.sh [-list] [--only <组名>]
set -uo pipefail

cd "$(dirname "$0")/.."

mkdir -p out
javac -encoding UTF-8 -d out check/Checker.java

java -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 \
    -cp out Checker "$@"
