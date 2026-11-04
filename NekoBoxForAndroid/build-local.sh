#!/bin/bash
# 本机构建 NekoBox（sing-box 1.14 内核）
# 用法: bash build-local.sh [core|apk|all]  (默认 all)
set -e

ROOT="$(cd "$(dirname "$0")" && pwd)"
export DEV_HOME="C:/Users/zmyloy/dev"
export JAVA_HOME="/c/Users/zmyloy/dev/jdk-21.0.13+9"
export ANDROID_HOME="D:/android-sdk"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/29.0.14206865"
export NDK="$ANDROID_NDK_HOME"
export GOPATH="C:\\Users\\zmyloy\\dev\\gopath"
export PATH="/c/Users/zmyloy/dev/jdk-21.0.13+9/bin:/c/Users/zmyloy/dev/go/bin:/c/Users/zmyloy/dev/gopath/bin:$PATH"
export GOBIND=gobind-matsuri

TAGS="with_conntrack,with_gvisor,with_quic,with_wireguard,with_utls,with_clash_api,with_naive_outbound"
TARGET="${1:-all}"

build_core() {
  echo ">>> libcore.aar (android/arm64)"
  cd "$ROOT/libcore"
  mkdir -p "$ROOT/app/libs"
  gomobile-matsuri bind -v -androidapi 21 -target=android/arm64 \
    -cache "$(cygpath -m "$ROOT/libcore/.build" 2>/dev/null || echo "$ROOT/libcore/.build")" \
    -trimpath -ldflags='-s -w' -tags="$TAGS" \
    -o "$ROOT/app/libs/libcore.aar" .
}

build_apk() {
  echo ">>> gradle assemble"
  cd "$ROOT"
  printf 'sdk.dir=%s\n' "$(echo "$ANDROID_HOME" | sed 's|:|\\:|g')" > local.properties
  "$DEV_HOME/gradle-dist/gradle-8.10.2/bin/gradle" "${GRADLE_TASK:-assembleOssDebug}" --no-daemon
}

case "$TARGET" in
  core) build_core ;;
  apk) build_apk ;;
  all) build_core; build_apk ;;
  *) echo "usage: $0 [core|apk|all]"; exit 1 ;;
esac
