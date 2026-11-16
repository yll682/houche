#!/usr/bin/env bash
# 本地构建入口：固定工具链路径后调用 Gradle wrapper
set -euo pipefail
export LANG=C.UTF-8
export LC_ALL=C.UTF-8
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
# SDK 不写死：优先用外部给的 ANDROID_SDK_ROOT，缺省回退到 $HOME/Android/Sdk。
# 本工作区用法：先加载 tools/env.sh（里面会设好 ANDROID_SDK_ROOT），再跑 ./build.sh。
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}"
export ANDROID_HOME="$ANDROID_SDK_ROOT"
export PATH="$JAVA_HOME/bin:$PATH"
cd "$(dirname "$0")"
exec ./gradlew "$@"
