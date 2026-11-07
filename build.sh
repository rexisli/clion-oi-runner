#!/usr/bin/env bash
# 零下载构建：直接用 CLion 自带的 lib/*.jar 作为 IntelliJ Platform SDK。
set -euo pipefail
cd "$(dirname "$0")"

CLION_HOME="${CLION_HOME:-D:/CLion 2026.1.1}"
JAVAC="${JAVAC:-javac}"
JAR="${JAR:-jar}"

if [ ! -d "$CLION_HOME/lib" ]; then
  echo "找不到 CLion: $CLION_HOME （用环境变量 CLION_HOME 指定安装目录）" >&2
  exit 1
fi

rm -rf out
mkdir -p out
"$JAVAC" --release 21 -encoding UTF-8 -d out -cp "$CLION_HOME/lib/*" $(find src -name '*.java')
mkdir -p out/META-INF
cp src/META-INF/plugin.xml out/META-INF/

mkdir -p dist
rm -f dist/oi-runner.jar
"$JAR" cf dist/oi-runner.jar -C out .
echo "已生成 dist/oi-runner.jar ($(stat -c%s dist/oi-runner.jar 2>/dev/null || wc -c < dist/oi-runner.jar) 字节)"

# 仓库根目录那份是给人下载的副本；被 CLion 映射着时覆盖会失败，失败就只提示，不影响构建。
if cp -f dist/oi-runner.jar oi-runner.jar 2>/dev/null; then
  echo "已同步根目录 oi-runner.jar"
else
  echo "提示：根目录 oi-runner.jar 被占用（多半是 CLion 正打开着插件），本次未同步；装插件用 dist/oi-runner.jar。"
fi
