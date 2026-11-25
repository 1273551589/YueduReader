#!/usr/bin/env bash
# shim 保真度测试 —— 一键编译并运行
#
#   ./run.sh           跑全部
#   ./run.sh base64    只跑 Base64
#   ./run.sh subdomain 只跑 Cookie 分桶
#   ./run.sh json      只跑 GSON vs GSONStrict 差集
set -euo pipefail
cd "$(dirname "$0")"

if command -v javac >/dev/null 2>&1; then
  JAVAC=javac
  JAVA=java
elif [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac" ]; then
  JAVAC="$JAVA_HOME/bin/javac"
  JAVA="$JAVA_HOME/bin/java"
else
  echo "找不到 javac，请先安装 JDK 17+" >&2
  exit 1
fi

HTTP_LIBS="lib/okhttp.jar:lib/okio.jar:lib/kotlin-stdlib.jar"
JSON_LIBS="lib/gson.jar"
TARGET="${1:-all}"

mkdir -p out

run_base64() {
  echo "############ Base64 ############"
  $JAVAC -d out src/AndroidBase64.java src/Base64FidelityTest.java
  $JAVA -cp out Base64FidelityTest
}

run_subdomain() {
  echo "############ Cookie 分桶 ############"
  if [ ! -f lib/okhttp.jar ]; then
    echo "缺少 lib/okhttp.jar —— 需要手动放入 okhttp 4.12.0 / okio-jvm 3.6.0 / kotlin-stdlib 1.9.10" >&2
    exit 1
  fi
  $JAVAC -cp "$HTTP_LIBS" -d out src/SubDomainFidelityTest.java
  $JAVA -cp "$HTTP_LIBS:out" SubDomainFidelityTest
}

run_json() {
  echo "############ 宽松 JSON 差集 ############"
  if [ ! -f lib/gson.jar ]; then
    echo "缺少 lib/gson.jar —— 需要 gson 2.13.2（与 Legado 一致）" >&2
    exit 1
  fi
  $JAVAC -cp "$JSON_LIBS" -d out src/LenientJsonTest.java
  $JAVA -cp "$JSON_LIBS:out" LenientJsonTest
}

case "$TARGET" in
  base64)    run_base64 ;;
  subdomain) run_subdomain ;;
  json)      run_json ;;
  all)
    run_base64
    echo
    run_subdomain
    echo
    run_json
    ;;
  *)
    echo "用法: ./run.sh [base64|subdomain|json|all]" >&2
    exit 1
    ;;
esac
