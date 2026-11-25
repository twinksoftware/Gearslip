package app.seb3thehacker.gearslip.car

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import app.seb3thehacker.gearslip.GearslipLog
import app.seb3thehacker.gearslip.R

/**
 * Lets another app drive Gearslip's assistant, so a different voice assistant, a home automation
 * app or Tasker can do what the driver could say: "navigate to work", "play Daft Punk", "open
 * Spotify". The command runs exactly as if spoken, and the answer is said on the car.
 *
 * Two ways in, for the two kinds of caller:
 *
 *     gearslip://command?text=navigate%20to%20work           (a link, for assistants)
 *     am broadcast -a app.seb3thehacker.gearslip.action.COMMAND -e text "play jazz"   (Tasker)
 *
 * Off until the driver turns on "Commands from other apps", only while a car is connected, and
 * never a phone call: that would let any app dial out without the phone permission.
 */
object AssistantLink {

    const val ACTION = "app.seb3thehacker.gearslip.action.COMMAND"
    const val EXTRA_TEXT = "text"

    /** Longest command taken: a spoken one is a sentence, not a page. */
    private const val MAX_LENGTH = 300

    enum class Result { RAN, OFF, NO_CAR, EMPTY }

    fun run(intent: Intent?): Result {
        val text = (intent?.getStringExtra(EXTRA_TEXT) ?: intent?.data?.getQueryParameter(EXTRA_TEXT))
            ?.trim()?.take(MAX_LENGTH).orEmpty()
        val result = when {
            !CarSettings.appCommands.value -> Result.OFF
            text.isEmpty() -> Result.EMPTY
            !CarAssistant.runCommand(text) -> Result.NO_CAR
            else -> Result.RAN
        }
        // What was asked stays out of the log, as with spoken commands.
        GearslipLog.i("assistant: a command from another app: ${result.name.lowercase().replace('_', ' ')}")
        return result
    }

    fun explain(context: Context, result: Result) {
        val message = when (result) {
            Result.RAN -> return
            Result.OFF -> R.string.app_commands_off
            Result.NO_CAR -> R.string.app_commands_no_car
            Result.EMPTY -> R.string.app_commands_empty
        }
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}

/** The broadcast way in, for automation apps that run in the background. */
class AssistantLinkReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AssistantLink.ACTION) return
        AssistantLink.run(intent)
    }
}

/** The link way in. Shows nothing: it hands the command on and closes. */
class AssistantLinkActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AssistantLink.explain(this, AssistantLink.run(intent))
        finish()
    }
}
