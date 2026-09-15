package com.example.matrixclock.ambient

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper

/**
 * One-shot reads of the onboard environment sensors.
 *
 * Most phones ship only a barometer, if that, so every sensor here is optional and simply absent
 * from the result when the hardware does not exist. Listeners are registered only for the moment it
 * takes to get a sample, then torn down — nothing stays subscribed.
 */
class AmbientSensors(context: Context) {

    private companion object {
        /** Sensors report quickly; give up rather than leave a listener hanging. */
        const val TIMEOUT_MS = 1500L
    }

    /** Whatever this device could actually measure. Null means "no such sensor". */
    data class Reading(
        val pressureHpa: Float?,
        val humidityPercent: Float?,
        val temperatureC: Float?
    ) {
        val hasAny: Boolean get() = pressureHpa != null || humidityPercent != null || temperatureC != null
    }

    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    val hasPressure: Boolean get() = sensor(Sensor.TYPE_PRESSURE) != null
    val hasHumidity: Boolean get() = sensor(Sensor.TYPE_RELATIVE_HUMIDITY) != null
    val hasTemperature: Boolean get() = sensor(Sensor.TYPE_AMBIENT_TEMPERATURE) != null

    private fun sensor(type: Int): Sensor? = manager?.getDefaultSensor(type)

    /** Samples every available sensor and calls [onResult] on the main thread. */
    fun read(onResult: (Reading) -> Unit) {
        val handler = Handler(Looper.getMainLooper())
        val values = HashMap<Int, Float>()
        val wanted = listOf(
            Sensor.TYPE_PRESSURE,
            Sensor.TYPE_RELATIVE_HUMIDITY,
            Sensor.TYPE_AMBIENT_TEMPERATURE
        ).mapNotNull { sensor(it) }

        if (manager == null || wanted.isEmpty()) {
            onResult(Reading(null, null, null))
            return
        }

        var finished = false
        lateinit var listener: SensorEventListener

        fun finish() {
            if (finished) return
            finished = true
            manager.unregisterListener(listener)
            onResult(
                Reading(
                    pressureHpa = values[Sensor.TYPE_PRESSURE],
                    humidityPercent = values[Sensor.TYPE_RELATIVE_HUMIDITY],
                    temperatureC = values[Sensor.TYPE_AMBIENT_TEMPERATURE]
                )
            )
        }

        listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                values[event.sensor.type] = event.values.firstOrNull() ?: return
                if (values.size == wanted.size) finish()
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        wanted.forEach { manager.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL) }
        // Report whatever arrived; a sensor that never fires must not block the announcement.
        handler.postDelayed({ finish() }, TIMEOUT_MS)
    }
}
