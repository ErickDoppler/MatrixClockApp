package com.example.matrixclock.sensing

import kotlin.math.sqrt

/**
 * Detects a double clap in a stream of 16 kHz mono PCM.
 *
 * A clap is an onset that is far above the rolling background level *and* collapses back down
 * within a few tens of milliseconds. Requiring the fast decay is what separates a clap from speech
 * or music, which stay loud across many windows.
 */
class ClapDetector(private val onDoubleClap: () -> Unit) {

    private companion object {
        const val WINDOW = 256 // 16 ms at 16 kHz.

        /** How far above the rolling background an onset has to rise. */
        const val ONSET_RATIO = 7f

        /** Absolute floor, so a silent room cannot trigger on amplified noise. */
        const val ONSET_FLOOR = 1800f

        /** A clap must fall back to this fraction of its peak within [DECAY_WINDOWS]. */
        const val DECAY_RATIO = 0.25f
        const val DECAY_WINDOWS = 6 // ~96 ms.

        /** Spacing between the two claps of a deliberate double clap. */
        const val MIN_GAP_MS = 90L
        const val MAX_GAP_MS = 700L

        /** Ignore everything for a moment after firing, so one gesture fires once. */
        const val REARM_MS = 1200L
    }

    private var background = ONSET_FLOOR
    private var previousLevel = 0f

    private var pendingPeak = 0f
    private var windowsSincePeak = -1

    private var lastClapMs = 0L
    private var mutedUntilMs = 0L

    private val window = ShortArray(WINDOW)
    private var filled = 0

    /** Feeds PCM samples; call from the audio thread. */
    fun process(samples: ShortArray, length: Int, nowMs: Long) {
        var offset = 0
        while (offset < length) {
            val take = minOf(WINDOW - filled, length - offset)
            System.arraycopy(samples, offset, window, filled, take)
            filled += take
            offset += take
            if (filled == WINDOW) {
                processWindow(rms(window, WINDOW), nowMs)
                filled = 0
            }
        }
    }

    private fun processWindow(level: Float, nowMs: Long) {
        if (nowMs < mutedUntilMs) {
            trackBackground(level)
            previousLevel = level
            return
        }

        // A candidate clap is waiting to prove it decays quickly.
        if (windowsSincePeak >= 0) {
            windowsSincePeak++
            if (level < pendingPeak * DECAY_RATIO) {
                confirmClap(nowMs)
                windowsSincePeak = -1
            } else if (windowsSincePeak > DECAY_WINDOWS) {
                // Stayed loud: speech, music or a door, not a clap.
                windowsSincePeak = -1
            }
        } else if (isOnset(level)) {
            pendingPeak = level
            windowsSincePeak = 0
        }

        trackBackground(level)
        previousLevel = level
    }

    private fun isOnset(level: Float): Boolean =
        level > ONSET_FLOOR && level > background * ONSET_RATIO && level > previousLevel * 2f

    private fun confirmClap(nowMs: Long) {
        val gap = nowMs - lastClapMs
        if (lastClapMs != 0L && gap in MIN_GAP_MS..MAX_GAP_MS) {
            lastClapMs = 0L
            mutedUntilMs = nowMs + REARM_MS
            onDoubleClap()
        } else {
            lastClapMs = nowMs
        }
    }

    /** Slow follower, and never let a loud passage drag the threshold up quickly. */
    private fun trackBackground(level: Float) {
        val rate = if (level > background) 0.002f else 0.02f
        background = background * (1f - rate) + level * rate
        if (background < ONSET_FLOOR * 0.1f) background = ONSET_FLOOR * 0.1f
    }

    private fun rms(buffer: ShortArray, length: Int): Float {
        var sum = 0.0
        for (i in 0 until length) {
            val v = buffer[i].toDouble()
            sum += v * v
        }
        return sqrt(sum / length).toFloat()
    }
}
