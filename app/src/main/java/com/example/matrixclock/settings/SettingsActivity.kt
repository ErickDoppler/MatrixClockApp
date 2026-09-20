package com.example.matrixclock.settings

import android.app.Activity
import android.graphics.Typeface
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.example.matrixclock.R
import com.example.matrixclock.ambient.Geocoder
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

    /** City lookups are network calls, so they run here rather than on the main thread. */
    private val geocoding = java.util.concurrent.Executors.newSingleThreadExecutor()

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
        bindLocationControls()
        bindMotionControl()
        bindVoiceControls()

        findViewById<Button>(R.id.doneButton).setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        // Coming back from the system installer, one of these engines may now exist.
        bindBundledEngineInstallers()
        bindEngineSpinner()
    }

    override fun onDestroy() {
        geocoding.shutdown()
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

    // ---- Location --------------------------------------------------------------------------

    /**
     * Lets the user name a city instead of relying on a location fix.
     *
     * Plenty of devices never produce one — a WiFi-only tablet, or location switched off — and
     * without coordinates there is no sunrise and no forecast. A named city also avoids needing the
     * location permission at all.
     */
    private fun bindLocationControls() {
        val input = findViewById<EditText>(R.id.cityInput)
        val results = findViewById<LinearLayout>(R.id.cityResults)

        renderCurrentCity()

        findViewById<Button>(R.id.citySearchButton).setOnClickListener {
            val query = input.text?.toString().orEmpty().trim()
            if (query.isEmpty()) {
                toast("Type a city name first")
                return@setOnClickListener
            }
            searchCity(query, results)
        }

        // The keyboard's search key should do the same thing as the button.
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                findViewById<Button>(R.id.citySearchButton).performClick()
                true
            } else {
                false
            }
        }

        findViewById<Button>(R.id.cityClearButton).setOnClickListener {
            settings.clearCity()
            results.removeAllViews()
            input.setText("")
            renderCurrentCity()
            toast("Using device location")
        }
    }

    private fun renderCurrentCity() {
        val label = findViewById<TextView>(R.id.cityCurrent)
        val city = settings.city
        label.text = if (city == null) {
            getString(R.string.city_using_device)
        } else {
            getString(R.string.city_using, city.name)
        }
        findViewById<Button>(R.id.cityClearButton).visibility =
            if (city == null) View.GONE else View.VISIBLE
    }

    private fun searchCity(query: String, results: LinearLayout) {
        results.removeAllViews()
        results.addView(hintLabel("Searching…"), matchWidthWithTopMargin(topDp = 8))

        // Network work must not touch the main thread.
        geocoding.execute {
            val places = Geocoder.search(query)
            runOnUiThread {
                results.removeAllViews()
                if (places.isEmpty()) {
                    results.addView(
                        hintLabel("No match for \"$query\". Check the spelling, or try a larger nearby city."),
                        matchWidthWithTopMargin(topDp = 8)
                    )
                    return@runOnUiThread
                }
                for (place in places) {
                    results.addView(
                        matrixButton(place.label) {
                            settings.setCity(place.label, place.latitude, place.longitude)
                            results.removeAllViews()
                            renderCurrentCity()
                            toast("City set to ${place.name}")
                        },
                        matchWidthWithTopMargin(topDp = 8)
                    )
                }
            }
        }
    }

    private fun hintLabel(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(ContextCompat.getColor(context, R.color.matrix_green_dim))
        textSize = 13f
    }

    private fun matrixButton(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        setTextColor(ContextCompat.getColor(context, R.color.matrix_green))
        typeface = Typeface.MONOSPACE
        setBackgroundResource(R.drawable.matrix_control)
        setPadding(paddingLeft, dp(12), paddingRight, dp(12))
        setOnClickListener { onClick() }
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

        bindBundledEngineInstallers()

        if (!TtsEngineInstaller.hasAnyEngine(this)) {
            findViewById<TextView>(R.id.voiceHint).text =
                if (TtsEngineInstaller.bundledEngines(this).isNotEmpty()) {
                    "This device has no speech engine. Install one of the bundled offline engines " +
                        "below to hear announcements; wake words work either way."
                } else {
                    "This device has no speech engine and this build carries none. Install any TTS " +
                        "engine to hear announcements; wake words work either way."
                }
        }

        // The voice list only exists once an engine has initialised.
        speaker.start { ready -> runOnUiThread { if (ready) bindVoiceSpinner() } }
    }

    /**
     * Adds an install button for every bundled engine the device does not already have.
     *
     * Rebuilt in [onResume] as well as here, so returning from the system installer removes the
     * button for whatever was just installed.
     */
    private fun bindBundledEngineInstallers() {
        val container = findViewById<LinearLayout>(R.id.installEngineContainer)
        container.removeAllViews()

        for (engine in TtsEngineInstaller.installableEngines(this)) {
            val button = matrixButton(getString(R.string.install_engine, engine.label)) {
                if (!TtsEngineInstaller.promptInstall(this@SettingsActivity, engine.packageName)) {
                    toast("Could not open the installer")
                }
            }
            container.addView(button, matchWidthWithTopMargin())

            if (engine.note.isNotEmpty()) {
                container.addView(hintLabel(engine.note), matchWidthWithTopMargin(topDp = 4))
            }
        }
    }

    private fun matchWidthWithTopMargin(topDp: Int = 12): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(topDp) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

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

