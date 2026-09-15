package com.example.matrixclock

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.util.Log
import android.os.Looper
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.matrixclock.ambient.Announcer
import com.example.matrixclock.sensing.AudioEngine
import com.example.matrixclock.sensing.ChargingMonitor
import com.example.matrixclock.sensing.ClapDetector
import com.example.matrixclock.sensing.MotionDetector
import com.example.matrixclock.settings.Settings
import com.example.matrixclock.settings.SettingsActivity
import com.example.matrixclock.voice.Speaker
import com.example.matrixclock.voice.VoiceCommand
import com.example.matrixclock.voice.VoiceCommands

/**
 * Hosts the rain, and switches the sensing stack on and off with the charger.
 *
 * Nothing that costs power runs on battery: the screen-on flag, the camera and the microphone are
 * all tied to [ChargingMonitor], so on battery this is exactly the clock it was before.
 */
class MainActivity : ComponentActivity() {

    private companion object {
        /** Hold Volume Down this long to reach settings. */
        const val SETTINGS_HOLD_MS = 3_000L

        /** Hold Volume Up this long to quit. */
        const val EXIT_HOLD_MS = 1_000L

        /** How often the clock policy is re-evaluated; it only has to notice a 60s timeout. */
        const val POLICY_TICK_MS = 500L

        const val TAG = "MatrixClock"
    }

    private lateinit var settings: Settings
    private lateinit var matrixView: MatrixView
    private lateinit var policy: ClockPolicy
    private lateinit var chargingMonitor: ChargingMonitor
    private lateinit var speaker: Speaker
    private lateinit var announcer: Announcer

    private var motionDetector: MotionDetector? = null
    private var audioEngine: AudioEngine? = null

    private val handler = Handler(Looper.getMainLooper())
    private var sensorsRunning = false
    private var settingsLaunched = false
    private var permissionsRequested = false

    /** Results arrive here; the sensors are then restarted with whatever was granted. */
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (sensorsRunning) {
            stopSensors()
            startSensors()
        }
    }

    private val policyTick = object : Runnable {
        override fun run() {
            matrixView.clockVisible = policy.isVisible()
            handler.postDelayed(this, POLICY_TICK_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        policy = ClockPolicy()
        speaker = Speaker(this, settings)
        announcer = Announcer(this, speaker)

        matrixView = MatrixView(this, settings)
        matrixView.onSettingsRequested = { openSettings() }
        setContentView(matrixView)
        enterImmersiveMode()

        chargingMonitor = ChargingMonitor(this) { charging -> onChargingChanged(charging) }
    }

    private fun enterImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, matrixView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onResume() {
        super.onResume()
        settingsLaunched = false
        matrixView.applySettings()
        matrixView.resume()
        speaker.start()
        chargingMonitor.start()
        // The monitor only fires on change, so apply the current state explicitly.
        onChargingChanged(chargingMonitor.isCharging)
        handler.post(policyTick)
        registerDebugCommands()
    }

    override fun onDestroy() {
        announcer.shutdown()
        super.onDestroy()
    }

    override fun onPause() {
        handler.removeCallbacks(policyTick)
        unregisterDebugCommands()
        stopSensors()
        chargingMonitor.stop()
        speaker.shutdown()
        matrixView.pause()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onPause()
    }

    private fun onChargingChanged(charging: Boolean) {
        Log.i(TAG, "Charging=$charging")
        policy.charging = charging
        if (charging) {
            // A clock you have to wake is not a clock, so hold the screen on while docked.
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            startSensors()
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            stopSensors()
            policy.reset()
        }
        matrixView.clockVisible = policy.isVisible()
    }

    // ---- Sensing ---------------------------------------------------------------------------

    private fun startSensors() {
        if (sensorsRunning) return
        sensorsRunning = true
        requestMissingPermissions()
        startMotionDetection()
        startAudio()
        Log.i(TAG, "Sensors started (motion=${policy.motionDetectionActive}, audio=${audioEngine != null})")
    }

    private fun stopSensors() {
        if (!sensorsRunning) return
        sensorsRunning = false
        motionDetector?.stop()
        motionDetector = null
        audioEngine?.stop()
        audioEngine = null
        policy.motionDetectionActive = false
    }

    private fun startMotionDetection() {
        if (!settings.motionEnabled || !hasPermission(Manifest.permission.CAMERA)) {
            policy.motionDetectionActive = false
            return
        }
        val detector = MotionDetector(this) { policy.onMotion() }
        detector.start(this)
        motionDetector = detector
        policy.motionDetectionActive = true
    }

    private fun startAudio() {
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            Log.w(TAG, "No RECORD_AUDIO permission; clap and voice commands are off")
            return
        }
        val clapDetector = ClapDetector { handler.post { policy.onShowRequested() } }
        val voiceCommands = VoiceCommands(this) { command ->
            handler.post { onVoiceCommand(command) }
        }
        val engine = AudioEngine(clapDetector, voiceCommands)
        engine.start()
        audioEngine = engine
    }

    private fun onVoiceCommand(command: VoiceCommand) {
        when (command) {
            VoiceCommand.TIME -> {
                showClockNow()
                speaker.speakTime()
            }
            VoiceCommand.CODE -> policy.onHideRequested()
            // Date and weather are spoken over whatever is on screen; they do not force the
            // clock on, so "matrix weather" while showing code stays showing code.
            VoiceCommand.DATE -> announcer.announceDate()
            VoiceCommand.WEATHER -> announcer.announceWeather()
        }
    }

    private fun showClockNow() {
        policy.onShowRequested()
        matrixView.clockVisible = true
    }

    /**
     * Debug builds accept voice commands over a broadcast, so the announcements can be exercised
     * without speaking to the device:
     *   adb shell am broadcast -a com.matrixclock.DEBUG_COMMAND --es command WEATHER
     * Release builds never register this — BuildConfig.DEBUG is a compile-time constant, so R8
     * removes the whole branch.
     */
    private val debugCommandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val name = intent?.getStringExtra("command") ?: return
            val command = VoiceCommand.entries.firstOrNull { it.name == name.uppercase() }
            if (command == null) {
                Log.w(TAG, "Unknown debug command '$name'")
                return
            }
            Log.i(TAG, "Debug command $command")
            onVoiceCommand(command)
        }
    }

    private fun registerDebugCommands() {
        if (!BuildConfig.DEBUG) return
        ContextCompat.registerReceiver(
            this,
            debugCommandReceiver,
            IntentFilter("com.matrixclock.DEBUG_COMMAND"),
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    private fun unregisterDebugCommands() {
        if (!BuildConfig.DEBUG) return
        runCatching { unregisterReceiver(debugCommandReceiver) }
    }

    // ---- Permissions -----------------------------------------------------------------------

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun requestMissingPermissions() {
        // Ask at most once per launch. Re-asking on every denial would spin: the retry path calls
        // startSensors() again, which would request again, and so on.
        if (permissionsRequested) return
        val missing = buildList {
            if (settings.motionEnabled && !hasPermission(Manifest.permission.CAMERA)) {
                add(Manifest.permission.CAMERA)
            }
            if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
                add(Manifest.permission.RECORD_AUDIO)
            }
            // Coarse location is only ever read as a last-known fix, for sunrise and weather.
            if (!hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)) {
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
        }
        if (missing.isEmpty()) return
        permissionsRequested = true
        permissionLauncher.launch(missing.toTypedArray())
    }

    // ---- Keys ------------------------------------------------------------------------------

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val held = event.eventTime - event.downTime
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (held >= SETTINGS_HOLD_MS && !settingsLaunched) openSettings()
                return true
            }
            KeyEvent.KEYCODE_VOLUME_UP -> {
                if (held >= EXIT_HOLD_MS) finish()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun openSettings() {
        settingsLaunched = true
        Toast.makeText(this, "Settings", Toast.LENGTH_SHORT).show()
        startActivity(Intent(this, SettingsActivity::class.java))
    }
}
