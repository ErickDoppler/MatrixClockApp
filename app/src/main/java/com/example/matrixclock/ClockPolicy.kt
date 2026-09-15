package com.example.matrixclock

/**
 * Decides whether the clock face should be showing.
 *
 * Inputs arrive from the camera and audio threads; the render side only reads [isVisible]. All
 * shared state is a `@Volatile` primitive, so no locking is needed either way.
 *
 * The rules, in the order they compose:
 *  - On battery, or with motion detection switched off, the clock shows by default.
 *  - While charging with motion detection on, the clock shows only if someone is around: it fades
 *    out once there has been no movement for [HOLD_MS].
 *  - A double clap or "time" pins it visible for [HOLD_MS] regardless of movement.
 *  - "matrix" / "code" hides it until something explicitly asks for it again.
 */
class ClockPolicy {

    companion object {
        /** "Fade out the clock after a minute of no motion." */
        const val HOLD_MS = 60_000L
    }

    @Volatile
    var charging = false

    @Volatile
    var motionDetectionActive = false

    @Volatile
    private var lastMotionMs = 0L

    @Volatile
    private var holdUntilMs = 0L

    @Volatile
    private var hiddenByCommand = false

    /** The camera saw movement. */
    fun onMotion(now: Long = System.currentTimeMillis()) {
        lastMotionMs = now
        hiddenByCommand = false
    }

    /** A double clap, or "time" — show the face for a minute. */
    fun onShowRequested(now: Long = System.currentTimeMillis()) {
        holdUntilMs = now + HOLD_MS
        hiddenByCommand = false
    }

    /** "matrix" / "code" — back to the rain. */
    fun onHideRequested() {
        hiddenByCommand = true
        holdUntilMs = 0L
        lastMotionMs = 0L
    }

    /** Sensors have stopped, so their state must not keep the clock pinned on. */
    fun reset() {
        lastMotionMs = 0L
        holdUntilMs = 0L
        hiddenByCommand = false
    }

    fun isVisible(now: Long = System.currentTimeMillis()): Boolean {
        if (hiddenByCommand) return false
        // Without a working camera there is no way to tell whether anyone is there, so default on.
        if (!charging || !motionDetectionActive) return true
        return now - lastMotionMs < HOLD_MS || now < holdUntilMs
    }
}
