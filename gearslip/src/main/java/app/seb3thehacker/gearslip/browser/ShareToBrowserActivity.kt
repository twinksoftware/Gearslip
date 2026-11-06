package app.seb3thehacker.gearslip.browser

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import app.seb3thehacker.gearslip.R

/**
 * The "Add to Gearslip" entry in Android's share sheet. A page shared from any browser becomes a
 * tile on the car browser's start page, so nobody has to type an address on the car's keyboard.
 * It shows nothing of its own: a toast says whether it worked, and it closes.
 */
class ShareToBrowserActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BrowserSettings.init(this)
        val text = intent?.takeIf { it.action == Intent.ACTION_SEND }?.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        // Browsers share the bare address; some apps wrap it in a sentence, so take the first link.
        val url = URL_PATTERN.find(text)?.value
        val title = intent?.getStringExtra(Intent.EXTRA_SUBJECT) ?: intent?.getStringExtra(Intent.EXTRA_TITLE) ?: ""
        val added = url != null && BrowserSettings.addBookmark(title, url)
        Toast.makeText(this, getString(if (added) R.string.browser_shared_added else R.string.browser_shared_not_url), Toast.LENGTH_SHORT).show()
        finish()
    }

    private companion object {
        val URL_PATTERN = Regex("""https?://\S+""")
    }
}
