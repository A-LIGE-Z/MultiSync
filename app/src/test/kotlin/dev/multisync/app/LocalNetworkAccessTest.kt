package dev.multisync.app

import dev.multisync.app.data.Settings
import dev.multisync.app.data.needsLocalNetwork
import org.junit.Assert.*
import org.junit.Test

class LocalNetworkAccessTest {
    @Test fun localDavAddressesAndSshLoopbackRequireLocalAccessButPublicHttpsDoesNot() {
        val base = Settings(deviceName = "test")
        for (url in listOf("http://127.0.0.1/dav/", "http://192.168.1.10/dav/", "http://10.0.0.1/dav/", "http://172.16.0.1/dav/", "http://server.local/dav/", "http://[::1]/dav/")) {
            assertTrue(needsLocalNetwork(base.copy(baseUrl = url)))
        }
        assertFalse(needsLocalNetwork(base.copy(baseUrl = "https://openlist.example/dav/")))
        assertTrue(needsLocalNetwork(base.copy(baseUrl = "https://openlist.example/dav/", sshEnabled = true)))
    }
}
