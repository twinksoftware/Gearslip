package app.seb3thehacker.gearslip

import android.content.Context
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyFactory
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/**
 * A certificate fetched from a link the driver pastes (issue #15), in whatever form it comes:
 * a PKCS#12 file, with or without a password, or a certificate and its private key as PEM or
 * DER, together in one file or in two. Whatever arrives is converted to the PKCS#12 identity
 * Gearslip presents to the car, and stored as an imported certificate.
 *
 * Gearslip ships no address of its own. The links are kept on the phone so the same files can
 * be fetched again from Settings, and they're only ever fetched when the driver asks.
 */
object CertFromLink {

    sealed interface Outcome {
        /** Imported, and in use from the next connection. */
        data object Done : Outcome
        /** A PKCS#12 file that needs its password. */
        data object NeedsPassword : Outcome
        /** A certificate on its own: its private key is at another address. */
        data object NeedsKey : Outcome
    }

    private const val KEY_CERT_LINK = "cert_link"
    private const val KEY_KEY_LINK = "cert_key_link"
    private const val MAX_BYTES = 1L * 1024 * 1024
    /** The password the converted file is stored under; it never leaves the app's storage. */
    private const val STORE_PASSWORD = "gearslip"

    /** The link used last, for Download again, or null when none was ever used. */
    fun savedLink(context: Context): Pair<String, String?>? {
        val cert = AppSettings.getString(context, KEY_CERT_LINK, "").ifEmpty { return null }
        return cert to AppSettings.getString(context, KEY_KEY_LINK, "").ifEmpty { null }
    }

    /** Runs on a background thread. Throws with a message a driver can read when it can't be used. */
    fun import(context: Context, certLink: String, keyLink: String?, password: String?): Outcome {
        val first = download(certLink)
        // The key's link is fetched only when the first file turns out to hold no key.
        var second: ByteArray? = null
        var converted = convert(first, null, password)
        if (converted is Converted.NeedsKey && !keyLink.isNullOrBlank()) {
            second = download(keyLink)
            converted = convert(first, second, password)
        }
        return when (converted) {
            Converted.NeedsKey -> Outcome.NeedsKey
            Converted.NeedsPassword -> Outcome.NeedsPassword
            is Converted.Ready -> {
                CertProvider.importFromBytes(context, converted.p12, converted.password)
                remember(context, certLink, keyLink.takeIf { second != null })
                Outcome.Done
            }
        }
    }

    internal sealed interface Converted {
        class Ready(val p12: ByteArray, val password: String) : Converted
        data object NeedsPassword : Converted
        data object NeedsKey : Converted
    }

    /**
     * Turns what was downloaded into a PKCS#12 file with its password. [first] is the file at
     * the first link; [second] the key from the second, once asked for.
     */
    internal fun convert(first: ByteArray, second: ByteArray?, password: String?): Converted {
        // A PKCS#12 file is DER too, so the certificate parser gets the first try.
        val certs = certificates(first)
        if (certs.isEmpty()) {
            val pass = password.orEmpty()
            val loaded = runCatching { KeyStore.getInstance("PKCS12").apply { load(ByteArrayInputStream(first), pass.toCharArray()) } }
            if (loaded.isFailure) {
                if (password == null) return Converted.NeedsPassword
                error("That password doesn't open the file, or it isn't a certificate.")
            }
            val store = loaded.getOrThrow()
            require(store.aliases().asSequence().any { store.isKeyEntry(it) }) { "The file holds no private key, so the car can't be shown it." }
            return Converted.Ready(first, pass)
        }

        val key = privateKey(first) ?: second?.let {
            privateKey(it) ?: error("The second link isn't a private key in a form Gearslip reads.")
        } ?: return Converted.NeedsKey
        // The certificate that goes with the key comes first, and any issuers after it.
        val leaf = certs.firstOrNull { belongs(key, it) } ?: error("The private key doesn't belong to this certificate.")
        val chain = listOf(leaf) + certs.filter { it !== leaf }
        val store = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry("phone", key, STORE_PASSWORD.toCharArray(), chain.toTypedArray())
        }
        val bytes = ByteArrayOutputStream().use { store.store(it, STORE_PASSWORD.toCharArray()); it.toByteArray() }
        GearslipLog.i("certificate from a link: ${leaf.subjectX500Principal}")
        return Converted.Ready(bytes, STORE_PASSWORD)
    }

    private fun remember(context: Context, cert: String, key: String?) {
        AppSettings.putString(context, KEY_CERT_LINK, cert)
        AppSettings.putString(context, KEY_KEY_LINK, key.orEmpty())
    }

    private fun download(link: String): ByteArray {
        val url = link.trim()
        require(url.startsWith("https://") || url.startsWith("http://")) { "That isn't a web address." }
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            instanceFollowRedirects = true
        }
        try {
            if (connection.responseCode !in 200..299) error("The server answered ${connection.responseCode}.")
            val out = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    require(out.size() <= MAX_BYTES) { "That file is far too big to be a certificate." }
                }
            }
            return out.toByteArray()
        } finally {
            connection.disconnect()
        }
    }

    /** Every certificate in PEM text, or the one certificate a DER file holds. */
    private fun certificates(bytes: ByteArray): List<X509Certificate> {
        val factory = CertificateFactory.getInstance("X.509")
        val pem = pemBlocks(bytes, "CERTIFICATE")
        if (pem.isNotEmpty()) return pem.map { factory.generateCertificate(ByteArrayInputStream(it)) as X509Certificate }
        return listOfNotNull(runCatching { factory.generateCertificate(ByteArrayInputStream(bytes)) as X509Certificate }.getOrNull())
    }

    /** A PKCS#8 or PKCS#1 key, as PEM or as PKCS#8 DER; null when there's none. */
    private fun privateKey(bytes: ByteArray): PrivateKey? {
        require(pemBlocks(bytes, "ENCRYPTED PRIVATE KEY").isEmpty()) { "The private key is encrypted. Gearslip needs one without a passphrase, or a .p12 file." }
        pemBlocks(bytes, "PRIVATE KEY").firstOrNull()?.let { return pkcs8(it) }
        if (pemBlocks(bytes, "RSA PRIVATE KEY").isNotEmpty()) return PemKeyParser.parsePrivateKey(String(bytes)).getPrivate()
        if (String(bytes).contains("-----BEGIN")) return null
        return runCatching { pkcs8(bytes) }.getOrNull()
    }

    private fun pkcs8(der: ByteArray): PrivateKey = runCatching {
        KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der))
    }.getOrElse { KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(der)) }

    /**
     * Whether [key] is [cert]'s. RSA keys are matched by modulus, which every Android provider
     * exposes, even one that hides the rest of a PKCS#8 key. Any other kind is taken on trust.
     */
    private fun belongs(key: PrivateKey, cert: X509Certificate): Boolean {
        val public = cert.publicKey
        return if (key is java.security.interfaces.RSAKey && public is java.security.interfaces.RSAKey) key.modulus == public.modulus else key.algorithm == public.algorithm
    }

    private fun pemBlocks(bytes: ByteArray, type: String): List<ByteArray> {
        val text = runCatching { String(bytes, Charsets.US_ASCII) }.getOrDefault("")
        return Regex("-----BEGIN $type-----([A-Za-z0-9+/=\\s]+)-----END $type-----").findAll(text)
            .map { Base64.getMimeDecoder().decode(it.groupValues[1]) }.toList()
    }
}
