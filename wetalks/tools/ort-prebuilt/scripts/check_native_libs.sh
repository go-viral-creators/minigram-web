#!/usr/bin/env bash
# Verifies that every native library in an APK can be loaded on a device: all NEEDED libraries are either in the APK or
# system libraries, and every undefined non-system symbol is defined by some library in the APK. Catches the class of bug where
# libonnxruntime4j_jni.so (BIND_NOW) references a symbol our minimal libonnxruntime.so does not export - the app then loads fine,
# the model runtime does not, and every voice note silently falls back to the basic DSP.
#   usage: check_native_libs.sh app-universal-release.apk [abi]
set -euo pipefail
APK=$1; ABI=${2:-arm64-v8a}
NDK=${ANDROID_NDK_ROOT:-$(ls -d /opt/android-sdk/ndk/* | tail -1)}
BIN=$(ls -d "$NDK"/toolchains/llvm/prebuilt/*/bin)
T=$(mktemp -d); unzip -q "$APK" "lib/$ABI/*" -d "$T"
SYS="libc.so libm.so libdl.so liblog.so libandroid.so libz.so libstdc++.so libEGL.so libGLESv2.so libjnigraphics.so libOpenSLES.so libaaudio.so libmediandk.so"
DEFINED=$(for f in "$T"/lib/$ABI/*.so; do "$BIN/llvm-nm" -D --defined-only "$f" | awk '{print $3}' | sed 's/@.*//'; done | sort -u)
rc=0
for f in "$T"/lib/$ABI/*.so; do
  for need in $("$BIN/llvm-readelf" -d "$f" | sed -n 's/.*NEEDED.*\[\(.*\)\]/\1/p'); do
    case " $SYS " in *" $need "*) continue;; esac
    [ -e "$T/lib/$ABI/$need" ] || { echo "MISSING LIBRARY  $(basename "$f") needs $need"; rc=1; }
  done
  while read -r sym; do
    s=${sym%%@*}
    case "$sym" in *@LIBC*|*@LIBM*|*@LIBDL*) continue;; esac
    case "$s" in __android_log_*|ANativeWindow*|AAudio*|AMedia*|AAsset*|__cxa_*|_Z*|__gnu_*|__aeabi*|_ITM_*|__register_frame*|__deregister_frame*) continue;; esac
    echo "$DEFINED" | grep -qx "$s" || { echo "UNRESOLVED SYMBOL $(basename "$f") -> $s"; rc=1; }
  done < <("$BIN/llvm-nm" -D --undefined-only "$f" | awk '{print $2}')
done
[ $rc -eq 0 ] && echo "native libraries OK ($ABI)"
exit $rc
