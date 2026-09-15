package com.example.matrixclock.sensing

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.example.matrixclock.voice.VoiceCommands
import kotlin.concurrent.thread

/**
 * Owns the single microphone stream and fans it out.
 *
 * Both the clap detector and the Vosk recogniser need raw PCM, and only one component can hold an
 * [AudioRecord] at a time — so this class reads once and hands the same buffer to each consumer.
 */
class AudioEngine(
    private val clapDetector: ClapDetector,
    private val voiceCommands: VoiceCommands
) {

    private companion object {
        const val TAG = "AudioEngine"
        const val SAMPLE_RATE = 16000
        const val READ_SAMPLES = 2048
    }

    @Volatile
    private var running = false
    private var worker: Thread? = null
    private var record: AudioRecord? = null

    /** Requires RECORD_AUDIO; the caller checks the permission before starting. */
    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        running = true

        worker = thread(name = "matrix-audio", isDaemon = true) {
            // Model load takes a few seconds, so do it here rather than blocking the UI thread.
            Log.i(TAG, "Audio thread starting")
            val voiceReady = voiceCommands.prepare()
            if (!voiceReady) Log.w(TAG, "Continuing with clap detection only")

            val minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuffer <= 0) {
                Log.e(TAG, "No usable microphone configuration")
                running = false
                return@thread
            }

            val recorder = try {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuffer, READ_SAMPLES * 4)
                )
            } catch (e: Exception) {
                Log.e(TAG, "Could not open the microphone", e)
                running = false
                return@thread
            }

            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "Microphone unavailable (state=${recorder.state})")
                recorder.release()
                running = false
                return@thread
            }

            record = recorder
            val buffer = ShortArray(READ_SAMPLES)
            try {
                recorder.startRecording()
                Log.i(TAG, "Recording at $SAMPLE_RATE Hz, voiceReady=$voiceReady")
                while (running) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read <= 0) continue
                    val now = System.currentTimeMillis()
                    clapDetector.process(buffer, read, now)
                    if (voiceReady) voiceCommands.process(buffer, read, now)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Audio loop stopped", e)
            } finally {
                runCatching { recorder.stop() }
                runCatching { recorder.release() }
                record = null
            }
        }
    }

    fun stop() {
        if (!running) return
        running = false
        worker?.join(1500)
        worker = null
        voiceCommands.release()
    }
}
