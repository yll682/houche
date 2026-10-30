#!/usr/bin/env bash
# 本地构建入口：固定工具链路径后调用 Gradle wrapper
set -euo pipefail
export LANG=C.UTF-8
export LC_ALL=C.UTF-8
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export ANDROID_HOME=/data/tools/android-sdk
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$PATH"
cd "$(dirname "$0")"
exec ./gradlew "$@"