#!/usr/bin/env bash
# 下载 shim 保真度测试需要的第三方 jar。
# 这些不该进 git（3MB 二进制），需要时现拉。
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p lib

fetch() {
  local dest="lib/$2"
  if [ -f "$dest" ]; then
    echo "  已存在 $2"
    return
  fi
  echo "  下载 $2 ..."
  curl -sL -o "$dest" "https://repo1.maven.org/maven2/$1"
  local size
  size=$(stat -f%z "$dest" 2>/dev/null || stat -c%s "$dest")
  if [ "$size" -lt 10000 ]; then
    echo "    ❌ 下载失败（$size 字节，可能是 404）" >&2
    rm -f "$dest"
    return 1
  fi
  echo "    ✅ $size 字节"
}

fetch "org/jsoup/jsoup/1.16.2/jsoup-1.16.2.jar"                          jsoup.jar
fetch "cn/wanghaomiao/JsoupXpath/2.5.3/JsoupXpath-2.5.3.jar"            xpath.jar
fetch "org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.9.0/kotlinx-coroutines-core-jvm-1.9.0.jar" coroutines.jar
fetch "com/squareup/okhttp3/okhttp/4.12.0/okhttp-4.12.0.jar"             okhttp.jar
fetch "com/squareup/okio/okio-jvm/3.9.0/okio-jvm-3.9.0.jar"              okio.jar
fetch "org/jetbrains/kotlin/kotlin-stdlib/1.9.10/kotlin-stdlib-1.9.10.jar" kotlin-stdlib.jar

echo "完成。跑 ./run.sh 开始测试。"
