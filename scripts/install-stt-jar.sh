#!/usr/bin/env bash
#
# Installs the sherpa-onnx JVM bindings into the local Maven repository.
#
# The bindings are not published to Maven Central or JitPack, so the build
# cannot resolve them on its own. Download the jar once, put it in ./lib, and
# run this script — then ./mvnw package works like any other Maven project.
#
#   curl -L -o lib/sherpa-onnx-jvm.jar \
#     https://github.com/csukuangfj/sherpa-onnx/releases/download/v1.12.1/sherpa-onnx-v1.12.1-java21.jar
#
#   ./scripts/install-stt-jar.sh
#
# The native libraries (libsherpa-onnx-jni.so, libonnxruntime.so) are not in
# the jar. They are needed at runtime, not at build time:
#
#   curl -L https://github.com/csukuangfj/sherpa-onnx/releases/download/v1.12.1/sherpa-onnx-v1.12.1-linux-x64-jni.tar.bz2 \
#     | tar -xj -C /tmp && cp /tmp/sherpa-onnx-*-linux-x64-jni/lib/*.so lib/
#
set -euo pipefail

cd "$(dirname "$0")/.."

JAR="lib/sherpa-onnx-jvm.jar"
GROUP="com.k2fsa.sherpa.onnx"
ARTIFACT="sherpa-onnx"
VERSION="1.12.1"

if [ ! -f "$JAR" ]; then
  echo "error: $JAR not found."
  echo
  echo "Download it with:"
  echo "  mkdir -p lib && curl -L -o $JAR \\"
  echo "    https://github.com/csukuangfj/sherpa-onnx/releases/download/v$VERSION/sherpa-onnx-v$VERSION-java21.jar"
  exit 1
fi

echo "Installing $JAR as $GROUP:$ARTIFACT:$VERSION ..."
./mvnw -q install:install-file \
  -Dfile="$JAR" \
  -DgroupId="$GROUP" \
  -DartifactId="$ARTIFACT" \
  -Dversion="$VERSION" \
  -Dpackaging=jar

echo "Done. The build can now resolve $GROUP:$ARTIFACT:$VERSION."
