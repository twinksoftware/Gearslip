package app.seb3thehacker.gearslip.speech

import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech

/**
 * The text-to-speech engine Gearslip speaks with: Google's when it's installed, since it sounds
 * the most natural and reads place names best, otherwise whatever the phone has (GrapheneOS ships
 * its own, and some phones another maker's).
 */
object Voices {

    const val GOOGLE = "com.google.android.tts"

    /** Google's engine if the phone has it, else null for the phone's default. */
    fun engine(context: Context): String? {
        val engines = context.packageManager.queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0)
        return GOOGLE.takeIf { google -> engines.any { it.serviceInfo?.packageName == google } }
    }

    fun create(context: Context, onInit: TextToSpeech.OnInitListener): TextToSpeech =
        TextToSpeech(context, onInit, engine(context))
}
