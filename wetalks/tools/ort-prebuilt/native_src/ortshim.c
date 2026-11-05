/*
 * Stand-in for the NNAPI entry point of ONNX Runtime.
 *
 * The Java/JNI half of ONNX Runtime (libonnxruntime4j_jni.so, from the official AAR) is linked with BIND_NOW and references
 * OrtSessionOptionsAppendExecutionProvider_Nnapi. Our minimal libonnxruntime.so (CPU only, a few operators) does not contain
 * NNAPI, so without this symbol the JNI library fails to load ("cannot locate symbol") and every enhancement silently falls back
 * to the basic DSP. The patched JNI library lists libortshim.so as a dependency, which resolves the symbol.
 * The function is never called: AuraMic never enables NNAPI.
 */
#include <stdint.h>

__attribute__((visibility("default")))
void* OrtSessionOptionsAppendExecutionProvider_Nnapi(void* options, uint32_t flags) {
    (void)options; (void)flags;
    return (void*)0;
}
