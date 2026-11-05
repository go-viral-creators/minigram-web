# AuraMic minimal ONNX Runtime 1.24.3 for Android (prebuilt)

A CPU-only, size-reduced ONNX Runtime built from source (v1.24.3, NDK 27.2.12479018, API 28, MinSizeRel, `--minimal_build`,
no exceptions / RTTI / ML ops). It contains ONLY the operators used by the DeepFilterNet3 models (enc, erb_dec, df_dec) and GTCRN.
`libonnxruntime.so`: arm64-v8a 2.28 MB, armeabi-v7a 1.47 MB (the official one is 25.8 MB).

## What is in here
| path | what |
|---|---|
| `jniLibs/<abi>/libonnxruntime.so` | the minimal runtime (stripped). ABIs: arm64-v8a, armeabi-v7a (NO x86 / x86_64) |
| `jniLibs/<abi>/libonnxruntime4j_jni.so` | the official JNI library of onnxruntime-android 1.24.3, patched with `patchelf --add-needed libortshim.so` |
| `native_src/ortshim.c, ortshim.map, CMakeLists_snippet.txt` | builds `libortshim.so`, the NNAPI stand-in the JNI library needs (without it the JNI library does not load) |
| `models/ort/*.ort` | DeepFilterNet3 models in ORT format (the only format a minimal build can load) |
| `models/onnx_source/*.onnx` | the original models (use them to re-convert if you change something) |
| `config/required_operators_and_types.config` | the operator/type list the runtime was built with |
| `scripts/check_native_libs.sh` | checks an APK: every NEEDED library present, every undefined symbol resolvable |
| `scripts/build_android.REFERENCE.sh` | the exact build command (contains the paths of the machine it ran on: adapt them) |
| `licenses/` | ONNX Runtime MIT licence + third-party notices, DeepFilterNet licence (keep them in your app's notices) |
| `SHA256SUMS.txt` | checksums of every file |

## How to use it in an Android app
1. Gradle: keep `implementation("com.microsoft.onnxruntime:onnxruntime-android:1.24.3")` (the Java classes; the version MUST be 1.24.3), `ndkVersion = "27.2.12479018"` is what the libs were built with, `minSdk >= 28`, `abiFilters` arm64-v8a / armeabi-v7a only.
2. Copy `jniLibs/` to `app/src/main/jniLibs/`.
3. In `packaging { jniLibs { pickFirsts += listOf("**/libonnxruntime.so", "**/libonnxruntime4j_jni.so") } }` so these files replace the ones inside the AAR.
4. Build `libortshim.so`: put `ortshim.c` / `ortshim.map` next to your CMakeLists and add the snippet (`externalNativeBuild { cmake { ... } }`). Without `libortshim.so` the patched JNI library cannot load.
5. Models: put the `.ort` files in assets, read them as bytes, `env.createSession(bytes, opts)` with
   `opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT)` (the minimal runtime has no optimizer) and `ExecutionMode.SEQUENTIAL`. CPU provider only (no NNAPI, no XNNPACK).
6. Run `scripts/check_native_libs.sh your.apk arm64-v8a` and `... armeabi-v7a`: it must print `native libraries OK`.

## Limits: read before reusing
* It runs ONLY models whose operators and data types are in `config/required_operators_and_types.config` (about 35 operators, mostly float). Any other model fails at load time with a "kernel not found" style error. For other models: regenerate the config from THEIR `.onnx` files (`python -m onnxruntime.tools.convert_onnx_models_to_ort <dir> --optimization_style Fixed --enable_type_reduction`, ORT 1.24.3) and rebuild.
* ORT-format `.ort` models only (not `.onnx`).
* The converter version, the runtime and the AAR's Java/JNI must all be 1.24.3.
* Not tested on x86 emulators (no x86/x86_64 build).
* Verified: `check_native_libs.sh` OK for both ABIs on the AuraMic release APK; all LOAD segments aligned to 16 KB (0x4000) or more. Quality / speed numbers were measured inside AuraMic only.
