package com.example.matrixclock

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Choreographer
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.View
import com.example.matrixclock.settings.Settings
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.random.Random

/**
 * Falling-glyph "Matrix" backdrop with a clock that fades in and out on demand.
 *
 * The animation is driven by [Choreographer] (vsync aligned) rather than a self-rescheduling
 * Handler, and the whole draw path is allocation free: column state lives in primitive arrays and
 * glyphs are drawn straight out of a reusable [CharArray].
 *
 * Clock visibility is not decided here — [clockVisible] is set by the caller, and this class only
 * animates towards it.
 */
class MatrixView(context: Context, private val settings: Settings) : View(context),
    Choreographer.FrameCallback {

    private companion object {
        /**
         * Minimum gap between redraws. Zero means "every vsync", which is what the rain wants:
         * the glow envelope slides sub-cell, so every extra frame is a visible improvement.
         */
        const val FRAME_INTERVAL_NANOS = 0L

        /** Guards against huge simulation steps after a stall (e.g. returning from background). */
        const val MAX_DELTA_SECONDS = 0.1f

        const val MIN_ROWS_PER_SECOND = 2f
        const val ROWS_PER_SECOND_RANGE = 6f
        const val MIN_TAIL = 5
        const val TAIL_RANGE = 10

        /** Lowest tail alpha, so the end of a trail stays faintly visible. */
        const val MIN_TAIL_ALPHA = 30

        /** The rain runs alone for this long at startup before the clock may appear. */
        const val CLOCK_DELAY_MS = 5_000L
        const val CLOCK_FADE_SECONDS = 0.8f

        /** Hold the screen this long to open settings. */
        const val LONG_PRESS_MS = 2_000L

        /** Maximum gap between taps that still counts as part of the same multi-tap. */
        const val TAP_WINDOW_MS = 500L
        const val TAPS_TO_CYCLE_COLOUR = 3


        val COLOR_MODES = intArrayOf(
            // Packed as background, foreground pairs.
            Color.BLACK, Color.rgb(0, 255, 0),
            Color.BLACK, Color.rgb(120, 120, 0),
            Color.BLACK, Color.YELLOW,
            Color.BLACK, Color.rgb(120, 0, 0),
            Color.BLACK, Color.RED,
            Color.BLACK, Color.rgb(180, 90, 0),
            Color.BLACK, Color.rgb(255, 140, 0),
            Color.BLACK, Color.rgb(0, 0, 120),
            Color.BLACK, Color.BLUE,
            Color.BLACK, Color.GRAY,
            Color.BLACK, Color.WHITE,
            Color.WHITE, Color.BLACK,
            Color.BLACK, Color.rgb(0, 120, 0)
        )

        /** Katakana is repeated so it dominates the mix, exactly as in the original. */
        val GLYPHS: CharArray = (
            "アイウエオカキクケコサシスセソタチツテトナニヌネノ".repeat(4) +
                "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789" +
                "!@#\$%^&*()-_=+[]{}|;:',.<>/?\\\"`~"
            ).toCharArray()
    }

    /** Target clock visibility; the view fades towards it over [CLOCK_FADE_SECONDS]. */
    var clockVisible = true

    private var speedMultiplier = settings.glyphSpeed
    private var density = settings.glyphDensity
    private var clockSizeFraction = settings.clockSize

    private val random = Random(System.nanoTime())
    private val choreographer = Choreographer.getInstance()

    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textAlign = Paint.Align.LEFT
    }
    private val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.LEFT
    }
    private val clockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textAlign = Paint.Align.CENTER
    }

    // Column state, one entry per column. Primitive arrays avoid the boxing a List<Float> costs.
    private var headRow = FloatArray(0)
    private var lastHeadRow = IntArray(0)
    private var rowsPerSecond = FloatArray(0)
    private var tailLength = IntArray(0)

    /** Columns switched off by the density setting are skipped entirely. */
    private var columnActive = BooleanArray(0)

    /** Glyph grid, laid out column-major: `cells[column * rows + row]`. */
    private var cells = CharArray(0)
    private var columns = 0
    private var rows = 0

    private var cellWidth = 0f
    private var cellHeight = 0f
    private var mutationsPerSecond = 0f
    private var mutationCredit = 0f

    private var colorIndex = 0
    private var backgroundColor = COLOR_MODES[0]

    private var running = false
    private var lastFrameNanos = 0L
    private var lastDrawNanos = 0L
    private var startTimeMs = 0L

    // Clock text is rebuilt only when the displayed second actually changes.
    private val calendar: Calendar = Calendar.getInstance()
    private val timeChars = CharArray(8)
    private var renderedSecond = -1L
    private var clockBaselineY = 0f
    private var clockAlpha = 0f

    /** Invoked when the screen is held for [LONG_PRESS_MS]. */
    var onSettingsRequested: (() -> Unit)? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var tapCount = 0
    private var lastTapMs = 0L
    private var downX = 0f
    private var downY = 0f
    private var longPressFired = false

    private val longPressRunnable = Runnable {
        longPressFired = true
        tapCount = 0
        onSettingsRequested?.invoke()
    }

    init {
        applyGlyphMetrics()
        timeChars[2] = ':'
        timeChars[5] = ':'
        applyColorMode(0)
        isClickable = true
    }

    private fun applyGlyphMetrics() {
        cellHeight = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, settings.glyphSizeDp, resources.displayMetrics
        )
        glyphPaint.textSize = cellHeight
        headPaint.textSize = cellHeight
        // The glyph set mixes full-width katakana with half-width ASCII, so the column pitch has
        // to be the widest advance or wide glyphs would overlap their neighbour.
        val advances = FloatArray(GLYPHS.size)
        glyphPaint.getTextWidths(GLYPHS, 0, GLYPHS.size, advances)
        cellWidth = max(1f, advances.max())
    }

    /** Re-reads every rain setting and rebuilds the grid. Safe to call while running. */
    fun applySettings() {
        speedMultiplier = settings.glyphSpeed
        density = settings.glyphDensity
        clockSizeFraction = settings.clockSize
        applyGlyphMetrics()
        if (width > 0 && height > 0) rebuild(width, height)
        invalidate()
    }

    /**
     * Triple tap cycles the colour scheme; a two-second hold opens settings.
     *
     * Neither is handled by [android.view.GestureDetector]: it only knows about double taps, and
     * its long press fires at the system timeout rather than the two seconds asked for here.
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                longPressFired = false
                postDelayed(longPressRunnable, LONG_PRESS_MS)
            }

            MotionEvent.ACTION_MOVE -> {
                // A drag is not a tap and not a hold, so abandon both.
                if (abs(event.x - downX) > touchSlop || abs(event.y - downY) > touchSlop) {
                    removeCallbacks(longPressRunnable)
                    tapCount = 0
                }
            }

            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPressRunnable)
                if (!longPressFired) {
                    val now = event.eventTime
                    tapCount = if (now - lastTapMs <= TAP_WINDOW_MS) tapCount + 1 else 1
                    lastTapMs = now
                    if (tapCount >= TAPS_TO_CYCLE_COLOUR) {
                        tapCount = 0
                        applyColorMode((colorIndex + 1) % (COLOR_MODES.size / 2))
                    }
                    performClick()
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressRunnable)
                tapCount = 0
            }
        }
        return true
    }

    // Overridden so the gesture handling above still reports clicks to accessibility services.
    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun applyColorMode(index: Int) {
        colorIndex = index
        backgroundColor = COLOR_MODES[index * 2]
        val foreground = COLOR_MODES[index * 2 + 1]
        // Setting the colour once per mode means the draw loop only has to touch the alpha channel.
        glyphPaint.color = foreground
        headPaint.color = foreground
        clockPaint.color = foreground
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = rebuild(w, h)

    private fun rebuild(w: Int, h: Int) {
        columns = max(1, (w / cellWidth).toInt())
        rows = max(1, ceil(h / cellHeight).toInt() + 1)

        headRow = FloatArray(columns)
        lastHeadRow = IntArray(columns)
        rowsPerSecond = FloatArray(columns)
        tailLength = IntArray(columns)
        columnActive = BooleanArray(columns)
        cells = CharArray(columns * rows) { GLYPHS[random.nextInt(GLYPHS.size)] }

        var active = 0
        for (column in 0 until columns) {
            columnActive[column] = random.nextFloat() < density
            if (columnActive[column]) active++
            headRow[column] = random.nextFloat() * rows
            lastHeadRow[column] = headRow[column].toInt()
            respawn(column, keepPosition = true)
        }
        // Roughly four shimmering glyphs per active column per second.
        mutationsPerSecond = active * 3.75f

        clockPaint.textSize = w * clockSizeFraction
        val metrics = clockPaint.fontMetrics
        clockBaselineY = h / 2f - (metrics.bottom + metrics.top) / 2f
    }

    private fun respawn(column: Int, keepPosition: Boolean) {
        rowsPerSecond[column] = MIN_ROWS_PER_SECOND + random.nextFloat() * ROWS_PER_SECOND_RANGE
        tailLength[column] = MIN_TAIL + random.nextInt(TAIL_RANGE)
        if (!keepPosition) {
            headRow[column] = 0f
            lastHeadRow[column] = -1
        }
    }

    /** Advances the simulation. Movement is time based, so speed no longer depends on frame rate. */
    private fun advance(deltaSeconds: Float) {
        val scaledDelta = deltaSeconds * speedMultiplier
        for (column in 0 until columns) {
            if (!columnActive[column]) continue
            val head = headRow[column] + rowsPerSecond[column] * scaledDelta
            headRow[column] = head

            // Seed a fresh glyph into every row the head just moved through.
            val currentRow = head.toInt()
            val base = column * rows
            var row = lastHeadRow[column] + 1
            while (row <= currentRow) {
                if (row in 0 until rows) cells[base + row] = GLYPHS[random.nextInt(GLYPHS.size)]
                row++
            }
            lastHeadRow[column] = currentRow

            // Recycle the column only once its tail has cleared the bottom edge.
            if (head - tailLength[column] > rows) respawn(column, keepPosition = false)
        }

        // Shimmer: mutate a handful of already-placed glyphs instead of re-randomising every cell.
        // Rate is per second, so the flicker looks identical at any refresh rate.
        mutationCredit += mutationsPerSecond * deltaSeconds
        val mutations = mutationCredit.toInt()
        mutationCredit -= mutations
        repeat(mutations) {
            cells[random.nextInt(cells.size)] = GLYPHS[random.nextInt(GLYPHS.size)]
        }

        advanceClockFade(deltaSeconds)
    }

    private fun advanceClockFade(deltaSeconds: Float) {
        // The rain gets the screen to itself for the first few seconds, as it always has.
        val target = if (clockVisible && System.currentTimeMillis() - startTimeMs > CLOCK_DELAY_MS) {
            1f
        } else {
            0f
        }
        val step = deltaSeconds / CLOCK_FADE_SECONDS
        clockAlpha = when {
            clockAlpha < target -> (clockAlpha + step).coerceAtMost(target)
            clockAlpha > target -> (clockAlpha - step).coerceAtLeast(target)
            else -> clockAlpha
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(backgroundColor)
        if (columns == 0) return

        var x = 0f
        for (column in 0 until columns) {
            if (!columnActive[column]) {
                x += cellWidth
                continue
            }
            val base = column * rows
            val head = headRow[column]
            val headRowIndex = head.toInt()
            val tail = tailLength[column]
            val invTail = 1f / tail

            // The glyph one cell ahead of the head fades in as the head crosses into it, so the
            // bright tip advances continuously instead of jumping a whole cell at a time.
            val leadRow = headRowIndex + 1
            if (leadRow < rows) {
                headPaint.alpha = ((head - headRowIndex) * 255f).toInt().coerceIn(0, 255)
                canvas.drawText(cells, base + leadRow, 1, x, (leadRow + 1) * cellHeight, headPaint)
            }

            for (t in 0 until tail) {
                val row = headRowIndex - t
                if (row < 0) break
                if (row >= rows) continue

                // Distance to the head is fractional, so the whole glow slides smoothly between
                // cells every frame rather than stepping once per row.
                val fade = 1f - (head - row) * invTail
                val paint = if (t == 0) headPaint else glyphPaint
                paint.alpha = (fade * 255f).toInt().coerceIn(MIN_TAIL_ALPHA, 255)
                canvas.drawText(cells, base + row, 1, x, (row + 1) * cellHeight, paint)
            }
            x += cellWidth
        }

        drawClock(canvas)
    }

    private fun drawClock(canvas: Canvas) {
        if (clockAlpha <= 0f) return
        updateTimeText()
        clockPaint.alpha = (clockAlpha * 255f).toInt().coerceIn(0, 255)
        canvas.drawText(timeChars, 0, timeChars.size, width / 2f, clockBaselineY, clockPaint)
    }

    private fun updateTimeText() {
        val now = System.currentTimeMillis()
        val second = now / 1000L
        if (second == renderedSecond) return
        renderedSecond = second

        // Pick up time zone / DST changes without re-checking on every frame.
        if (second % 60L == 0L) calendar.timeZone = TimeZone.getDefault()
        calendar.timeInMillis = now
        writeTwoDigits(0, calendar.get(Calendar.HOUR_OF_DAY))
        writeTwoDigits(3, calendar.get(Calendar.MINUTE))
        writeTwoDigits(6, calendar.get(Calendar.SECOND))
    }

    private fun writeTwoDigits(offset: Int, value: Int) {
        timeChars[offset] = ('0' + (value / 10))
        timeChars[offset + 1] = ('0' + (value % 10))
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        choreographer.postFrameCallback(this)

        val deltaSeconds = if (lastFrameNanos == 0L) {
            0f
        } else {
            ((frameTimeNanos - lastFrameNanos) / 1_000_000_000.0).toFloat()
        }
        lastFrameNanos = frameTimeNanos
        advance(deltaSeconds.coerceIn(0f, MAX_DELTA_SECONDS))

        // Throttle redraws to the target interval while staying aligned to vsync.
        if (frameTimeNanos - lastDrawNanos >= FRAME_INTERVAL_NANOS) {
            lastDrawNanos = frameTimeNanos
            invalidate()
        }
    }

    fun resume() {
        if (running) return
        running = true
        if (startTimeMs == 0L) startTimeMs = System.currentTimeMillis()
        lastFrameNanos = 0L
        choreographer.postFrameCallback(this)
    }

    fun pause() {
        if (!running) return
        running = false
        choreographer.removeFrameCallback(this)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (windowVisibility == VISIBLE) resume()
    }

    override fun onDetachedFromWindow() {
        pause()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) resume() else pause()
    }
}
