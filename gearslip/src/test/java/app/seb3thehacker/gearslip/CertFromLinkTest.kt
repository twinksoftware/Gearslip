package app.seb3thehacker.gearslip

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.KeyStore
import java.security.PrivateKey
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The forms a certificate can arrive in from a link (issue #15), each converted to a usable PKCS#12. */
class CertFromLinkTest {

    private val generated = SelfSignedCert.generate()
    private val key = generated.keyStore.getKey(SelfSignedCert.ALIAS, SelfSignedCert.PASSWORD) as PrivateKey
    private val certPem = pem("CERTIFICATE", generated.certificate.encoded)
    private val keyPem = pem("PRIVATE KEY", key.encoded)

    private fun pem(type: String, der: ByteArray) =
        "-----BEGIN $type-----\n" + Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der) + "\n-----END $type-----\n"

    private fun assertUsable(result: CertFromLink.Converted) {
        assertTrue("expected a ready file, got $result", result is CertFromLink.Converted.Ready)
        val ready = result as CertFromLink.Converted.Ready
        val store = KeyStore.getInstance("PKCS12").apply { load(ByteArrayInputStream(ready.p12), ready.password.toCharArray()) }
        val alias = store.aliases().asSequence().first { store.isKeyEntry(it) }
        assertEquals(generated.certificate, store.getCertificate(alias))
    }

    @Test fun certificateAndKeyInOnePem() = assertUsable(CertFromLink.convert((certPem + keyPem).toByteArray(), null, null))

    @Test fun certificateAloneAsksForTheKey() =
        assertEquals(CertFromLink.Converted.NeedsKey, CertFromLink.convert(certPem.toByteArray(), null, null))

    @Test fun certificateAndKeyInTwoFiles() = assertUsable(CertFromLink.convert(certPem.toByteArray(), keyPem.toByteArray(), null))

    @Test fun derCertificateAndDerKey() = assertUsable(CertFromLink.convert(generated.certificate.encoded, key.encoded, null))

    @Test fun p12WithPassword() {
        val bytes = ByteArrayOutputStream().use { generated.keyStore.store(it, "secret".toCharArray()); it.toByteArray() }
        assertEquals(CertFromLink.Converted.NeedsPassword, CertFromLink.convert(bytes, null, null))
        assertUsable(CertFromLink.convert(bytes, null, "secret"))
    }

    @Test fun keyFromAnotherCertificateIsRefused() {
        val other = SelfSignedCert.generate()
        val otherKey = other.keyStore.getKey(SelfSignedCert.ALIAS, SelfSignedCert.PASSWORD) as PrivateKey
        try {
            CertFromLink.convert(certPem.toByteArray(), pem("PRIVATE KEY", otherKey.encoded).toByteArray(), null)
            fail("a key from another certificate was accepted")
        } catch (_: IllegalStateException) {
        }
    }
}
