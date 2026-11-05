#!/usr/bin/env bash
# Builds an ONNX Runtime Android AAR that contains ONLY the operators DeepFilterNet3 uses (required_operators.config,
# generated from enc.onnx / erb_dec.onnx / df_dec.onnx). Needs ~25 GB disk, the Android SDK + NDK r26 and about an hour,
# so it is meant for a CI/build machine rather than the sandbox this project is edited in.
#
#   ANDROID_SDK_ROOT=... ANDROID_NDK_HOME=... ./build.sh
#
# Output: onnxruntime/build/Android/MinSizeRel/aar_out/.../onnxruntime-android-1.19.2.aar. Copy it to app/libs/ and replace the
# "com.microsoft.onnxruntime:onnxruntime-android" dependency in app/build.gradle.kts with files("libs/onnxruntime-android-1.19.2.aar").
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
[ -d onnxruntime ] || git clone --branch v1.19.2 --depth 1 --recursive https://github.com/microsoft/onnxruntime
cd onnxruntime
./build.sh --config MinSizeRel --parallel --skip_tests \
  --android --android_sdk_path "${ANDROID_SDK_ROOT:?}" --android_ndk_path "${ANDROID_NDK_HOME:?}" --android_abi arm64-v8a --android_api 24 \
  --build_java --build_shared_lib \
  --include_ops_by_config "$HERE/required_operators.config" --enable_reduced_operator_type_support \
  --disable_ml_ops --disable_rtti --cmake_extra_defines onnxruntime_BUILD_UNIT_TESTS=OFF
