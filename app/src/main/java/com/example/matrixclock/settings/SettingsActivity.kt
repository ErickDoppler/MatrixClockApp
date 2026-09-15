package com.example.matrixclock.settings

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.example.matrixclock.R
import com.example.matrixclock.voice.Speaker
import com.example.matrixclock.voice.TtsEngineInstaller

/**
 * Settings screen, reached by holding Volume Down for three seconds.
 *
 * Values are written straight through to [Settings] as they change, so backing out with the system
 * back gesture keeps whatever was adjusted — there is nothing to save or cancel.
 */
class SettingsActivity : Activity() {

    private lateinit var settings: Settings
    private lateinit var speaker: Speaker

    private var engines: List<EngineChoice> = emptyList()
    private var voices: List<VoiceChoice> = emptyList()

    /** A TTS engine package plus the label to show for it. */
    private data class EngineChoice(val packageName: String?, val label: String)

    /** A voice name plus its label; a null name means "engine default". */
    private data class VoiceChoice(val name: String?, val label: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        speaker = Speaker(this, settings)
        setContentView(R.layout.activity_settings)

        bindRainControls()
        bindMotionControl()
        bindVoiceControls()

        findViewById<Button>(R.id.doneButton).setOnClickListener { finish() }
    }

    override fun onDestroy() {
        speaker.shutdown()
        super.onDestroy()
    }

    // ---- Rain ------------------------------------------------------------------------------

    private fun bindRainControls() {
        slider(
            barId = R.id.glyphSizeBar,
            valueId = R.id.glyphSizeValue,
            min = Settings.GLYPH_SIZE_MIN,
            max = Settings.GLYPH_SIZE_MAX,
            current = settings.glyphSizeDp,
            format = { "%.0f dp".format(it) }
        ) { settings.glyphSizeDp = it }

        slider(
            barId = R.id.glyphDensityBar,
            valueId = R.id.glyphDensityValue,
            min = Settings.DENSITY_MIN,
            max = Settings.DENSITY_MAX,
            current = settings.glyphDensity,
            format = { "%.0f%% of columns".format(it * 100f) }
        ) { settings.glyphDensity = it }

        slider(
            barId = R.id.glyphSpeedBar,
            valueId = R.id.glyphSpeedValue,
            min = Settings.SPEED_MIN,
            max = Settings.SPEED_MAX,
            current = settings.glyphSpeed,
            format = { "%.2fx".format(it) }
        ) { settings.glyphSpeed = it }

        slider(
            barId = R.id.clockSizeBar,
            valueId = R.id.clockSizeValue,
            min = Settings.CLOCK_SIZE_MIN,
            max = Settings.CLOCK_SIZE_MAX,
            current = settings.clockSize,
            format = { "%.0f%% of screen width".format(it * 100f) }
        ) { settings.clockSize = it }
    }

    private fun bindMotionControl() {
        val toggle = findViewById<Button>(R.id.motionToggle)
        fun render() {
            val state = if (settings.motionEnabled) "ON" else "OFF"
            toggle.text = getString(R.string.motion_enabled, state)
        }
        render()
        toggle.setOnClickListener {
            settings.motionEnabled = !settings.motionEnabled
            render()
        }
    }

    // ---- Voice -----------------------------------------------------------------------------

    private fun bindVoiceControls() {
        slider(
            barId = R.id.voiceRateBar,
            valueId = R.id.voiceRateValue,
            min = Settings.TTS_RATE_MIN,
            max = Settings.TTS_RATE_MAX,
            current = settings.ttsRate,
            format = { "%.2fx".format(it) }
        ) {
            settings.ttsRate = it
            speaker.applyVoiceSettings()
        }

        bindEngineSpinner()

        findViewById<Button>(R.id.testVoiceButton).setOnClickListener {
            if (speaker.isReady) speaker.speakTime() else toast("No speech engine ready yet")
        }

        findViewById<Button>(R.id.installEngineButton).apply {
            // Only worth offering when this build carries the engine and it is not already there.
            val offerInstall = TtsEngineInstaller.isBundled(this@SettingsActivity) &&
                !TtsEngineInstaller.isBundledEngineInstalled(this@SettingsActivity)
            visibility = if (offerInstall) View.VISIBLE else View.GONE
            setOnClickListener {
                if (!TtsEngineInstaller.promptInstall(this@SettingsActivity)) {
                    toast("Could not open the installer")
                }
            }
        }

        if (!TtsEngineInstaller.hasAnyEngine(this)) {
            findViewById<TextView>(R.id.voiceHint).text =
                if (TtsEngineInstaller.isBundled(this)) {
                    "This device has no speech engine. Install the bundled offline engine to hear " +
                        "the time spoken; wake words work either way."
                } else {
                    "This device has no speech engine, and this 32-bit build does not carry one. " +
                        "Install any TTS engine to hear announcements; wake words work either way."
                }
        }

        // The voice list only exists once an engine has initialised.
        speaker.start { ready -> runOnUiThread { if (ready) bindVoiceSpinner() } }
    }

    private fun bindEngineSpinner() {
        val installed = TtsEngineInstaller.installedEngines(this)
        val labels = installed.map { EngineChoice(it, describePackage(it)) }
        engines = listOf(EngineChoice(null, "System default")) + labels

        val spinner = findViewById<Spinner>(R.id.engineSpinner)
        spinner.adapter = adapterOf(engines.map { it.label })
        spinner.setSelection(engines.indexOfFirst { it.packageName == settings.ttsEngine }
            .coerceAtLeast(0))

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, position: Int, id: Long) {
                val chosen = engines.getOrNull(position)?.packageName
                if (chosen == settings.ttsEngine) return
                settings.ttsEngine = chosen
                // A different engine has a different voice list, so start over.
                settings.ttsVoice = null
                speaker.restart { ready -> runOnUiThread { if (ready) bindVoiceSpinner() } }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun bindVoiceSpinner() {
        val available = speaker.availableVoices().map { VoiceChoice(it.name, describeVoice(it)) }
        voices = listOf(VoiceChoice(null, "Engine default")) + available

        val spinner = findViewById<Spinner>(R.id.voiceSpinner)
        spinner.adapter = adapterOf(voices.map { it.label })
        spinner.setSelection(voices.indexOfFirst { it.name == settings.ttsVoice }.coerceAtLeast(0))

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, position: Int, id: Long) {
                val chosen = voices.getOrNull(position)?.name
                if (chosen == settings.ttsVoice) return
                settings.ttsVoice = chosen
                speaker.applyVoiceSettings()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun describeVoice(voice: android.speech.tts.Voice): String {
        val locale = voice.locale?.displayName.orEmpty()
        return if (locale.isEmpty()) voice.name else "${voice.name}  ($locale)"
    }

    private fun describePackage(packageName: String): String = runCatching {
        val info = packageManager.getApplicationInfo(packageName, 0)
        packageManager.getApplicationLabel(info).toString()
    }.getOrDefault(packageName)

    // ---- Helpers ---------------------------------------------------------------------------

    private fun adapterOf(items: List<String>): ArrayAdapter<String> =
        ArrayAdapter(this, R.layout.spinner_item, items).apply {
            setDropDownViewResource(R.layout.spinner_item)
        }

    /**
     * Wires a SeekBar over a float range. The bar works in 0..100 steps and maps onto [min]..[max],
     * so every control gets the same feel regardless of its units.
     */
    private fun slider(
        barId: Int,
        valueId: Int,
        min: Float,
        max: Float,
        current: Float,
        format: (Float) -> String,
        apply: (Float) -> Unit
    ) {
        val bar = findViewById<SeekBar>(barId)
        val label = findViewById<TextView>(valueId)

        label.text = format(current)
        bar.progress = (((current - min) / (max - min)) * 100f).toInt().coerceIn(0, 100)

        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val value = min + (max - min) * (progress / 100f)
                label.text = format(value)
                if (fromUser) apply(value)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}

