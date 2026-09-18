package com.example.picotts

/** JNI bridge to the bundled SVOX Pico synthesiser. */
internal object PicoNative {

    init {
        System.loadLibrary("picotts")
    }

    /** Sample rate Pico always produces. */
    const val SAMPLE_RATE = 16000

    /** Receives PCM as Pico produces it, so playback can start before synthesis finishes. */
    interface AudioSink {
        /** Returns false to abandon the rest of the utterance. */
        fun onAudio(pcm: ByteArray, length: Int): Boolean
    }

    /** Returns an opaque handle, or 0 if the voice could not be opened. */
    @JvmStatic
    external fun nativeOpen(textAnalysisPath: String, signalGenerationPath: String): Long

    @JvmStatic
    external fun nativeClose(handle: Long)

    /** Streams 16-bit mono PCM at [SAMPLE_RATE] to [sink]; returns 0 on success. */
    @JvmStatic
    external fun nativeSynthesize(handle: Long, text: String, sink: AudioSink): Int
}
