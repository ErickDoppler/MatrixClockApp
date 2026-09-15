package com.example.matrixclock.sensing

import android.content.Context
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Front-camera motion detection.
 *
 * Frames are reduced to a coarse luminance grid and compared with the previous one. The global mean
 * change is subtracted before thresholding, so auto-exposure drift and gradual light changes do not
 * register as movement; only localised change does.
 *
 * No image ever leaves this class — frames are reduced to [GRID_W] x [GRID_H] averages and dropped.
 */
class MotionDetector(
    private val context: Context,
    private val onMotion: () -> Unit
) {

    private companion object {
        const val TAG = "MotionDetector"
        const val GRID_W = 32
        const val GRID_H = 24

        /** Per-cell luma change, after removing global drift, that counts as movement. */
        const val CELL_THRESHOLD = 12

        /** Fraction of cells that must change before the frame counts as motion. */
        const val AREA_THRESHOLD = 0.02f

        /** Analysing a handful of frames a second is plenty and keeps the camera cheap. */
        const val MIN_FRAME_INTERVAL_MS = 150L
    }

    private var executor: ExecutorService? = null
    private var provider: ProcessCameraProvider? = null

    private val previous = IntArray(GRID_W * GRID_H)
    private val current = IntArray(GRID_W * GRID_H)
    private var hasPrevious = false
    private var lastFrameMs = 0L
    private var lastReportMs = 0L

    var isRunning = false
        private set

    fun start(owner: LifecycleOwner) {
        if (isRunning) return
        isRunning = true
        hasPrevious = false

        val analysisExecutor = Executors.newSingleThreadExecutor()
        executor = analysisExecutor

        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (!isRunning) return@addListener
            try {
                val cameraProvider = future.get()
                provider = cameraProvider
                if (!bindAnyCamera(cameraProvider, owner, analysisExecutor)) {
                    // A camera that is unavailable (in use, no usable lens, policy) must not be
                    // fatal: the clock simply stays visible instead.
                    Log.w(TAG, "No usable camera, motion detection disabled")
                    isRunning = false
                }
            } catch (e: Exception) {
                Log.w(TAG, "Camera provider unavailable, motion detection disabled", e)
                isRunning = false
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /**
     * Binds the first camera that will actually accept an analysis use case.
     *
     * Old camera HALs are fussy: a LEGACY-level device can advertise a front lens yet refuse the
     * bind with "unable to resolve a camera for the given use case", and can reject a resolution
     * request that every modern device accepts. So the front camera is tried first and the back one
     * second — either is fine for spotting movement in front of a docked clock — and each is tried
     * once with the preferred low resolution and once letting CameraX choose.
     */
    private fun bindAnyCamera(
        cameraProvider: ProcessCameraProvider,
        owner: LifecycleOwner,
        analysisExecutor: java.util.concurrent.Executor
    ): Boolean {
        val candidates = listOf(
            "front" to CameraSelector.DEFAULT_FRONT_CAMERA,
            "back" to CameraSelector.DEFAULT_BACK_CAMERA
        ).filter { (name, selector) ->
            runCatching { cameraProvider.hasCamera(selector) }.getOrDefault(false).also {
                if (!it) Log.i(TAG, "No $name camera reported by CameraX")
            }
        }

        for ((name, selector) in candidates) {
            for (preferLowResolution in listOf(true, false)) {
                try {
                    val analysis = buildAnalysis(preferLowResolution).apply {
                        setAnalyzer(analysisExecutor, ::analyse)
                    }
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(owner, selector, analysis)
                    val how = if (preferLowResolution) "320x240" else "default resolution"
                    Log.i(TAG, "Bound $name camera for motion detection ($how)")
                    return true
                } catch (e: Exception) {
                    Log.i(TAG, "Could not bind $name camera (lowRes=$preferLowResolution): ${e.message}")
                }
            }
        }
        return false
    }

    private fun buildAnalysis(preferLowResolution: Boolean): ImageAnalysis {
        val builder = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        if (preferLowResolution) {
            builder.setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(320, 240),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                        )
                    )
                    .build()
            )
        }
        return builder.build()
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false
        provider?.unbindAll()
        provider = null
        executor?.shutdown()
        executor = null
        hasPrevious = false
    }

    private fun analyse(image: ImageProxy) {
        try {
            val now = System.currentTimeMillis()
            if (now - lastFrameMs < MIN_FRAME_INTERVAL_MS) return
            lastFrameMs = now

            reduceToGrid(image)
            if (!hasPrevious) {
                System.arraycopy(current, 0, previous, 0, current.size)
                hasPrevious = true
                return
            }
            if (isMotion()) {
                if (now - lastReportMs > 2000L) {
                    Log.i(TAG, "Motion")
                    lastReportMs = now
                }
                onMotion()
            }
            System.arraycopy(current, 0, previous, 0, current.size)
        } catch (e: Exception) {
            Log.w(TAG, "Frame analysis failed", e)
        } finally {
            image.close()
        }
    }

    /** Averages the Y plane into a coarse grid, which is all the comparison needs. */
    private fun reduceToGrid(image: ImageProxy) {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val width = image.width
        val height = image.height

        for (gy in 0 until GRID_H) {
            val yStart = gy * height / GRID_H
            val yEnd = ((gy + 1) * height / GRID_H).coerceAtLeast(yStart + 1)
            for (gx in 0 until GRID_W) {
                val xStart = gx * width / GRID_W
                val xEnd = ((gx + 1) * width / GRID_W).coerceAtLeast(xStart + 1)

                var sum = 0
                var count = 0
                var y = yStart
                while (y < yEnd) {
                    val rowOffset = y * rowStride
                    var x = xStart
                    while (x < xEnd) {
                        val index = rowOffset + x * pixelStride
                        if (index < buffer.limit()) {
                            sum += buffer.get(index).toInt() and 0xFF
                            count++
                        }
                        x += 2 // Every other pixel is ample at this grid size.
                    }
                    y += 2
                }
                current[gy * GRID_W + gx] = if (count > 0) sum / count else 0
            }
        }
    }

    private fun isMotion(): Boolean {
        // Remove the frame-wide brightness shift first; auto-exposure must not look like movement.
        var total = 0
        for (i in current.indices) total += current[i] - previous[i]
        val drift = total / current.size

        var changed = 0
        for (i in current.indices) {
            if (abs(current[i] - previous[i] - drift) > CELL_THRESHOLD) changed++
        }
        return changed > current.size * AREA_THRESHOLD
    }
}
