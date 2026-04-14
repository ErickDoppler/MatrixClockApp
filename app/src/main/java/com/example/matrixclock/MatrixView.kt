package com.example.matrixclock

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.view.View
import java.text.SimpleDateFormat
import java.util.*
import kotlin.random.Random
import kotlin.math.max

class MatrixView(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val random = Random
    private val chars = (
            "アイウエオカキクケコサシスセソタチツテトナニヌネノ".repeat(4) + // common
                    "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789" + // less common
                    "!@#\$%^&*()-_=+[]{}|;:',.<>/?\\\"`~" // rare
            )
    private val formatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val handler = Handler(Looper.getMainLooper())

    private var startTime = System.currentTimeMillis()
    private var colorIndex = 0

    // Adjustable speed multiplier
    var speedMultiplier = 0.4f

    // Colors
    private val colorModes = arrayOf(
        Pair(Color.BLACK, Color.rgb(0,255,0)),
        Pair(Color.BLACK, Color.rgb(120,120,0)),
        Pair(Color.BLACK, Color.YELLOW),
        Pair(Color.BLACK, Color.rgb(120,0,0)),
        Pair(Color.BLACK, Color.RED),
        Pair(Color.BLACK, Color.rgb(180,90,0)),
        Pair(Color.BLACK, Color.rgb(255,140,0)),
        Pair(Color.BLACK, Color.rgb(0,0,120)),
        Pair(Color.BLACK, Color.BLUE),
        Pair(Color.BLACK, Color.GRAY),
        Pair(Color.BLACK, Color.WHITE),
        Pair(Color.WHITE, Color.BLACK),
        Pair(Color.BLACK, Color.rgb(0,120,0))
    )

    // Column positions, speeds and tail lengths
    private val drops = mutableListOf<Float>()
    private val speeds = mutableListOf<Float>()
    private val tailLengths = mutableListOf<Int>()

    init {
        paint.typeface = Typeface.MONOSPACE
        paint.textSize = 32f
        setOnClickListener {
            colorIndex = (colorIndex + 1) % colorModes.size
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        drops.clear()
        speeds.clear()
        tailLengths.clear()
        val cols = max(1, w / paint.textSize.toInt())
        repeat(cols) {
            drops.add(random.nextFloat() * h)
            speeds.add(0.2f + random.nextFloat() * 0.6f) // variable speed
            tailLengths.add(5 + random.nextInt(10))      // variable tail lengths
        }
    }

    override fun onDraw(canvas: Canvas) {
        val (bg, fg) = colorModes[colorIndex]
        canvas.drawColor(bg)

        for (i in drops.indices) {
            val x = i * paint.textSize
            val yHead = drops[i]
            val tailLength = tailLengths[i]

            // Draw tail
            for (t in 0 until tailLength) {
                val y = yHead - t * paint.textSize
                if (y >= 0) {
                    val alpha = (255 * (1f - t.toFloat() / tailLength)).toInt().coerceAtLeast(30)
                    paint.color = Color.argb(alpha, Color.red(fg), Color.green(fg), Color.blue(fg))

                    // Make head bold
                    paint.typeface = if (t == 0) Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                    else Typeface.MONOSPACE

                    val c = chars[random.nextInt(chars.length)]
                    canvas.drawText(c.toString(), x, y, paint)
                }
            }

            // Move head down by speed
            drops[i] += paint.textSize * speeds[i] * speedMultiplier

            // Reset head only after tail fully off screen
            if (drops[i] - tailLength * paint.textSize > height) {
                drops[i] = 0f
                speeds[i] = 0.2f + random.nextFloat() * 0.6f
                tailLengths[i] = 5 + random.nextInt(10)
            }
        }

        // Draw clock after 5 seconds
        val elapsed = System.currentTimeMillis() - startTime
        if (elapsed > 5000) {
            val alpha = ((elapsed - 5000) / 1000f * 255).toInt().coerceAtMost(255)
            paint.color = Color.argb(alpha, Color.red(fg), Color.green(fg), Color.blue(fg))
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = width * 0.1f

            // Vertical centering
            val fm = paint.fontMetrics
            val y = height / 2f - (fm.bottom + fm.top) / 2f

            canvas.drawText(formatter.format(Date()), width / 2f, y, paint)

            paint.textAlign = Paint.Align.LEFT
            paint.textSize = 32f
        }

        handler.postDelayed({ invalidate() }, 40)
    }
}