#!/usr/bin/env bash
# 零下载构建：直接用 CLion 自带的 lib/*.jar 作为 IntelliJ Platform SDK。
set -euo pipefail

CLION_HOME="${CLION_HOME:-D:/CLion 2026.1.1}"
JAVAC="${JAVAC:-javac}"

if [ ! -d "$CLION_HOME/lib" ]; then
  echo "找不到 CLion: $CLION_HOME （用环境变量 CLION_HOME 指定安装目录）" >&2
  exit 1
fi

rm -rf out
mkdir -p out
"$JAVAC" --release 21 -encoding UTF-8 -d out -cp "$CLION_HOME/lib/*" $(find src -name '*.java')
mkdir -p out/META-INF
cp src/META-INF/plugin.xml out/META-INF/
rm -f oi-runner.jar
jar cf oi-runner.jar -C out .
echo "已生成 oi-runner.jar ($(stat -c%s oi-runner.jar 2>/dev/null || wc -c < oi-runner.jar) 字节)"
