package com.example.matrixclock.voice

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

/**
 * Offers the bundled open-source TTS engine when the device has none of its own.
 *
 * Many devices ship without Google TTS, so the sherpa-onnx engine (Apache-2.0, fully offline) rides
 * along inside our APK. It is staged to cache and handed to the system package installer, which
 * shows the user the normal install prompt — nothing is installed silently.
 */
object TtsEngineInstaller {

    private const val TAG = "TtsEngineInstaller"
    private const val ASSET = "sherpa-onnx-tts-engine.apk"
    const val ENGINE_PACKAGE = "com.k2fsa.sherpa.onnx.tts.engine"

    /** Engine packages that can currently serve TTS_SERVICE. */
    fun installedEngines(context: Context): List<String> {
        val intent = Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
        return context.packageManager.queryIntentServices(intent, 0)
            .mapNotNull { it.serviceInfo?.packageName }
            .distinct()
    }

    fun hasAnyEngine(context: Context): Boolean = installedEngines(context).isNotEmpty()

    fun isBundledEngineInstalled(context: Context): Boolean =
        installedEngines(context).contains(ENGINE_PACKAGE)

    /**
     * Whether this build actually carries the engine. The 32-bit "legacy" flavour does not: the
     * engine is an arm64-only APK, so shipping it to a 32-bit device would add 81 MB that could
     * never be installed.
     */
    fun isBundled(context: Context): Boolean = try {
        context.assets.list("")?.contains(ASSET) == true
    } catch (e: Exception) {
        false
    }

    /**
     * Stages the bundled APK and launches the system installer.
     * Returns false if the asset could not be written; the caller then tells the user.
     */
    fun promptInstall(context: Context): Boolean = try {
        val staged = stageApk(context)
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
        Log.e(TAG, "Could not launch the bundled engine installer", e)
        false
    }

    private fun stageApk(context: Context): File {
        val dir = File(context.cacheDir, "installers").apply { mkdirs() }
        val target = File(dir, ASSET)
        // The asset is stored uncompressed, so this is a straight copy.
        context.assets.open(ASSET).use { input ->
            FileOutputStream(target).use { output -> input.copyTo(output, 256 * 1024) }
        }
        return target
    }
}
