#!/usr/bin/env bash
# 编译 src/ 与 test/ 到 out/。只用 JDK，无第三方依赖。
set -euo pipefail

cd "$(dirname "$0")/.."

rm -rf out
mkdir -p out

mapfile -t SOURCES < <(find src test -name '*.java' | sort)
if [ "${#SOURCES[@]}" -eq 0 ]; then
    echo "没有找到 .java 源文件" >&2
    exit 1
fi

javac -encoding UTF-8 -d out "${SOURCES[@]}"
echo "编译完成：${#SOURCES[@]} 个源文件 -> out/"
