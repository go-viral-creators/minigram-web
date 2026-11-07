package com.kafisir.wetalks.data

import android.content.Context
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * The on-device picture model (TensorFlow Lite). Input: 1 x 3 x 384 x 384 floats in -1..1, output: 2 numbers, index 0 = "NSFW".
 * Built ONLY by [NsfwRuntime] and used ONLY through [NsfwLogic.scan]; a unit test enforces that no other file touches TensorFlow Lite.
 * Nothing here opens a network connection.
 */
internal class TfliteNsfw private constructor(private val interp: Interpreter) : NsfwClassifier {
    private val input = ByteBuffer.allocateDirect(4 * 3 * NsfwLogic.SIZE * NsfwLogic.SIZE).order(ByteOrder.nativeOrder())
    private val output = Array(1) { FloatArray(2) }

    @Synchronized override fun probability(chw: FloatArray): Float {
        input.rewind(); input.asFloatBuffer().put(chw)
        interp.run(input, output)
        return NsfwLogic.nsfwProbability(output[0])
    }

    fun close() { try { interp.close() } catch (_: Exception) {} }

    companion object {
        /** Where the model file may be: first a downloaded copy in the app's private folder, then the one shipped inside the APK. */
        const val ASSET = "nsfw/model.tflite"
        fun privateFile(c: Context) = File(c.filesDir, "nsfw/model.tflite")

        fun present(c: Context): Boolean = privateFile(c).let { it.exists() && it.length() > 100_000 } || assetExists(c)
        private fun assetExists(c: Context) = try { c.assets.openFd(ASSET).use { it.length > 100_000 } } catch (_: Exception) { false }

        /** Null when there is no model file (the feature then stays off) or the file is unusable. Two threads: gentle on low-end phones. */
        fun load(c: Context): TfliteNsfw? = try {
            val buf: MappedByteBuffer? = privateFile(c).takeIf { it.exists() && it.length() > 100_000 }?.let { f ->
                FileInputStream(f).use { it.channel.map(FileChannel.MapMode.READ_ONLY, 0, f.length()) }
            } ?: try {
                c.assets.openFd(ASSET).use { fd -> FileInputStream(fd.fileDescriptor).use { it.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength) } }
            } catch (_: Exception) { null }
            buf?.let { TfliteNsfw(Interpreter(it, Interpreter.Options().setNumThreads(2))) }
        } catch (e: Throwable) { android.util.Log.w("WeTalks", "sensitive-image model not loaded: " + e.message); null }
    }
}
