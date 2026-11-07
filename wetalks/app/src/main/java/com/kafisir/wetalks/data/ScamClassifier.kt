package com.kafisir.wetalks.data

/**
 * Optional second layer: a small on-device text model.
 * No model is bundled (see the report: the custom minimal ONNX Runtime cannot run a text model, and the licence/size
 * trade-off was not worth it). To add one later, implement this and pass it to [ScamDetector.scan].
 * [score] returns 0..1 (1 = surely a scam) or null when the model is not available.
 * The model may only raise a verdict that the rules already found suspicious; it never creates one alone.
 */
interface ScamClassifier {
    fun score(text: String): Float?
}

object NoClassifier : ScamClassifier {
    override fun score(text: String): Float? = null
}
