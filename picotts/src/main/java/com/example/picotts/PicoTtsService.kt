package com.example.picotts

import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.util.Log
import java.io.File
import java.util.Locale

/**
 * A system speech engine backed by SVOX Pico.
 *
 * Pico is small and entirely offline, and was Android's own engine for years, so it runs
 * comfortably on hardware far too slow for a neural voice. Two English voices are bundled; the
 * synthesiser itself is a few hundred kilobytes of native code.
 */
class PicoTtsService : TextToSpeechService() {

    private companion object {
        const val TAG = "PicoTtsService"

        /** Pico emits audio in one go; hand it to the framework in pieces this size. */
        const val CHUNK_BYTES = 4096

        /** Two seconds of 16 kHz 16-bit mono audio; the buffer grows if the utterance is longer. */
        const val INITIAL_AUDIO_BYTES = 64000

        val VOICES = mapOf(
            "eng-GBR" to Voice("en-GB", "en-GB_ta.bin", "en-GB_kh0_sg.bin"),
            "eng-USA" to Voice("en-US", "en-US_ta.bin", "en-US_lh0_sg.bin")
        )
    }

    private data class Voice(val label: String, val textAnalysis: String, val signalGeneration: String)

    private var handle = 0L
    private var loadedVoice: String? = null

    override fun onCreate() {
        // Pico reads its resources from real files, so unpack them out of assets first: the base
        // class asks for a language during its own onCreate, which needs them already on disk.
        unpackVoices()
        super.onCreate()
    }

    override fun onDestroy() {
        releaseEngine()
        super.onDestroy()
    }

    // ---- Language handling -------------------------------------------------------------------

    override fun onGetLanguage(): Array<String> {
        val key = loadedVoice ?: "eng-GBR"
        return arrayOf(key.substring(0, 3), key.substring(4), "")
    }

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        val exact = "${lang.orEmpty()}-${country.orEmpty()}"
        if (VOICES.containsKey(exact)) return TextToSpeech.LANG_COUNTRY_AVAILABLE
        // Any English falls back to the British voice rather than refusing to speak.
        if (lang.equals("eng", true) || lang.equals("en", true)) return TextToSpeech.LANG_AVAILABLE
        return TextToSpeech.LANG_NOT_SUPPORTED
    }

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        val available = onIsLanguageAvailable(lang, country, variant)
        if (available == TextToSpeech.LANG_NOT_SUPPORTED) return available

        val key = if (VOICES.containsKey("${lang.orEmpty()}-${country.orEmpty()}")) {
            "${lang.orEmpty()}-${country.orEmpty()}"
        } else {
            "eng-GBR"
        }
        return if (loadVoice(key)) available else TextToSpeech.LANG_NOT_SUPPORTED
    }

    override fun onStop() = Unit

    // ---- Synthesis ---------------------------------------------------------------------------

    override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {
        val key = resolveVoiceKey(request)
        if (!loadVoice(key)) {
            callback.error()
            return
        }

        val text = request.charSequenceText?.toString().orEmpty()
        if (text.isBlank()) {
            callback.start(PicoNative.SAMPLE_RATE, android.media.AudioFormat.ENCODING_PCM_16BIT, 1)
            callback.done()
            return
        }

        /*
         * Collect the whole utterance, then hand it over.
         *
         * Feeding each block to the framework as Pico produces it does start playback sooner, but
         * on a slow, busy device the synthesiser cannot reliably stay ahead of playback and the
         * audio stutters: on the LG G Pad the same sentence took 12.6 s streamed against 7.4 s
         * buffered, the extra time being gaps. Announcements here are a sentence or two, so paying
         * roughly a second up front for speech that does not break up is the better trade.
         */
        val audio = java.io.ByteArrayOutputStream(INITIAL_AUDIO_BYTES)
        val sink = object : PicoNative.AudioSink {
            override fun onAudio(pcm: ByteArray, length: Int): Boolean {
                audio.write(pcm, 0, length)
                return true
            }
        }

        val status = runCatching { PicoNative.nativeSynthesize(handle, text, sink) }
            .getOrElse {
                Log.e(TAG, "Synthesis threw", it)
                -1
            }
        if (status != 0) {
            Log.w(TAG, "Synthesis failed")
            callback.error()
            return
        }

        val pcm = audio.toByteArray()
        callback.start(PicoNative.SAMPLE_RATE, android.media.AudioFormat.ENCODING_PCM_16BIT, 1)
        val maxChunk = minOf(CHUNK_BYTES, callback.maxBufferSize)
        var offset = 0
        while (offset < pcm.size) {
            val slice = minOf(maxChunk, pcm.size - offset)
            if (callback.audioAvailable(pcm, offset, slice) != TextToSpeech.SUCCESS) return
            offset += slice
        }
        callback.done()
    }

    private fun resolveVoiceKey(request: SynthesisRequest): String {
        val key = "${request.language.orEmpty()}-${request.country.orEmpty()}"
        return if (VOICES.containsKey(key)) key else "eng-GBR"
    }

    // ---- Voice loading -----------------------------------------------------------------------

    @Synchronized
    private fun loadVoice(key: String): Boolean {
        if (handle != 0L && loadedVoice == key) return true
        releaseEngine()

        val voice = VOICES[key] ?: return false
        val dir = voiceDir()
        val opened = runCatching {
            PicoNative.nativeOpen(
                File(dir, voice.textAnalysis).absolutePath,
                File(dir, voice.signalGeneration).absolutePath
            )
        }.getOrDefault(0L)

        if (opened == 0L) {
            Log.e(TAG, "Could not open Pico voice $key")
            return false
        }
        handle = opened
        loadedVoice = key
        Log.i(TAG, "Loaded Pico voice $key (${voice.label})")
        return true
    }

    @Synchronized
    private fun releaseEngine() {
        if (handle != 0L) {
            PicoNative.nativeClose(handle)
            handle = 0L
        }
        loadedVoice = null
    }

    private fun voiceDir(): File = File(filesDir, "pico").apply { mkdirs() }

    /** Copies the voice data out of assets on first run; Pico cannot read from an APK. */
    private fun unpackVoices() {
        val dir = voiceDir()
        val names = runCatching { assets.list("pico") }.getOrNull().orEmpty()
        for (name in names) {
            val target = File(dir, name)
            if (target.exists() && target.length() > 0) continue
            runCatching {
                assets.open("pico/$name").use { input ->
                    target.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
                }
                Log.i(TAG, "Unpacked $name")
            }.onFailure { Log.e(TAG, "Could not unpack $name", it) }
        }
    }
}
