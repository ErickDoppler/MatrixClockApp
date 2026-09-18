package com.example.matrixclock.voice

import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

/**
 * Offers the bundled open-source TTS engines when the device has none of its own.
 *
 * Many devices ship without Google TTS, so offline engines ride along inside our APK. Each flavour
 * carries whichever ones suit its hardware, as `assets/tts-engines/<package name>.apk` — the file
 * name *is* the package name, so adding an engine is purely a build-time matter and needs no code
 * change here. They are staged to cache and handed to the system package installer, which shows the
 * user the normal install prompt; nothing is installed silently.
 */
object TtsEngineInstaller {

    private const val TAG = "TtsEngineInstaller"
    private const val ASSET_DIR = "tts-engines"

    /** A speech engine carried inside this build. */
    data class BundledEngine(val packageName: String, val label: String, val note: String)

    private val KNOWN = mapOf(
        "com.k2fsa.sherpa.onnx.tts.engine" to
            ("sherpa-onnx" to "Natural neural voice. Needs a 64-bit device; too slow on older hardware."),
        "com.github.olga_yakovleva.rhvoice.android" to
            ("RHVoice" to "Clearer than eSpeak. Download one voice inside RHVoice after installing."),
        "com.reecedunn.espeak" to
            ("eSpeak NG" to "Robotic but tiny, instant, and works entirely offline."),
        "com.example.picotts" to
            ("Pico" to "Smoother than eSpeak, fully offline, and light enough for old devices.")
    )

    /** Engines this build carries, in the order they should be offered. */
    fun bundledEngines(context: Context): List<BundledEngine> = try {
        context.assets.list(ASSET_DIR).orEmpty()
            .filter { it.endsWith(".apk") }
            .map { it.removeSuffix(".apk") }
            .map { pkg ->
                val known = KNOWN[pkg]
                BundledEngine(pkg, known?.first ?: pkg, known?.second.orEmpty())
            }
            .sortedBy { it.label }
    } catch (e: Exception) {
        Log.w(TAG, "Could not list bundled engines", e)
        emptyList()
    }

    /** Engine packages that can currently serve TTS_SERVICE. */
    fun installedEngines(context: Context): List<String> {
        val intent = Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
        return context.packageManager.queryIntentServices(intent, 0)
            .mapNotNull { it.serviceInfo?.packageName }
            .distinct()
    }

    fun hasAnyEngine(context: Context): Boolean = installedEngines(context).isNotEmpty()

    /** Bundled engines that are not installed yet, so worth offering. */
    fun installableEngines(context: Context): List<BundledEngine> {
        val installed = installedEngines(context)
        return bundledEngines(context).filterNot { installed.contains(it.packageName) }
    }

    /**
     * Stages a bundled APK and launches the system installer.
     * Returns false if the asset could not be written; the caller then tells the user.
     */
    fun promptInstall(context: Context, packageName: String): Boolean = try {
        val staged = stageApk(context, packageName)
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", staged
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        true
    } catch (e: Exception) {
        Log.e(TAG, "Could not launch the installer for $packageName", e)
        false
    }

    private fun stageApk(context: Context, packageName: String): File {
        val dir = File(context.cacheDir, "installers").apply { mkdirs() }
        val target = File(dir, "$packageName.apk")
        // The assets are stored uncompressed, so this is a straight copy.
        context.assets.open("$ASSET_DIR/$packageName.apk").use { input ->
            FileOutputStream(target).use { output -> input.copyTo(output, 256 * 1024) }
        }
        return target
    }
}
