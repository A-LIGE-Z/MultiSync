package dev.multisync.core

import com.jcraft.jsch.HostKeyRepository
import okio.Buffer
import okio.ByteString.Companion.toByteString
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class SshOpenListTunnelTest {
    private val key = Buffer().writeInt(11).writeUtf8("ssh-ed25519").writeInt(32).write(ByteArray(32) { it.toByte() }).readByteArray()
    // Deliberately invalid private key; the public host key is generated test data.
    private val config = SshTunnelConfig("upload.example", "upload-only", key.toByteString().base64(),
        "-----BEGIN OPENSSH PRIVATE KEY-----\nfixture-secret\n-----END OPENSSH PRIVATE KEY-----",
        openlist_urls = listOf("https://openlist.example", "https://alias.example"))

    @Test fun tunnelOnlyAcceptsItsBoundOpenListOriginsAndPreservesProxyPrefixes() {
        config.validate("https://openlist.example/dav/")
        config.validate("https://alias.example:443/dav/")
        assertThrows(IllegalArgumentException::class.java) { config.validate("https://other.example/dav/") }
        assertThrows(IllegalArgumentException::class.java) { config.validate("http://openlist.example/dav/") }
        assertThrows(IllegalArgumentException::class.java) { config.validate("https://openlist.example/prefix/dav/") }
        config.copy(openlist_urls = listOf("https://openlist.example/prefix")).validate("https://openlist.example/prefix/dav/")
        assertThrows(IllegalArgumentException::class.java) { config.validate("https://user:pass@openlist.example/dav/") }
    }

    @Test fun hostKeyMismatchOrWrongHostCannotBeTrustedAutomatically() {
        val repository = PinnedHostKeys("upload.example", key)
        assertEquals(HostKeyRepository.OK, repository.check("upload.example", key))
        assertEquals(HostKeyRepository.CHANGED, repository.check("other.example", key))
        assertEquals(HostKeyRepository.CHANGED, repository.check("upload.example", key.copyOf().apply { this[lastIndex] = 0 }))
        assertThrows(IllegalStateException::class.java) { repository.add(null, null) }
    }

    @Test fun diagnosticsDoNotExposeThePrivateKey() {
        assertFalse(config.toString().contains("fixture-secret"))
        assertThrows(IllegalArgumentException::class.java) { config.copy(host_key = "invalid").validate("https://openlist.example/dav/") }
    }

    @Test fun signedDownloadsCanUpgradeToHttpsButCannotSendDataToAnExternalHttpOrigin() {
        val base = "http://127.0.0.1:4321/dav/".toHttpUrl()
        assertTrue(safeTunnelDestination(base, base))
        assertTrue(safeTunnelDestination(base, "https://signed-cloud.example/file".toHttpUrl()))
        assertFalse(safeTunnelDestination(base, "http://signed-cloud.example/file".toHttpUrl()))
        assertFalse(safeTunnelDestination(base, "http://127.0.0.1:5432/file".toHttpUrl()))
    }

    @Test fun portableEd25519SignsAndVerifiesWithoutJdkEdEcApis() {
        val privateKey = org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters(ByteArray(32) { (it + 1).toByte() }, 0)
        val data = "SSH provider compatibility".toByteArray()
        val signer = com.jcraft.jsch.bc.SignatureEd25519().apply { init(); setPrvKey(privateKey.encoded); update(data) }
        val signature = signer.sign()
        val verifier = com.jcraft.jsch.bc.SignatureEd25519().apply { init(); setPubKey(privateKey.generatePublicKey().encoded); update(data) }
        val packet = Buffer().writeInt(11).writeUtf8("ssh-ed25519").writeInt(signature.size).write(signature).readByteArray()
        assertTrue(verifier.verify(packet))
    }
}
