package dev.multisync.app

import android.content.Context
import org.robolectric.RuntimeEnvironment
import dev.multisync.app.data.SettingsStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = android.app.Application::class)
class SshTunnelSettingsTest {
    @Test fun malformedOrOversizedImportDoesNotExposeSecretInput() {
        val error = assertThrows(IllegalStateException::class.java) {
            SettingsStore.parseTunnel("""{"private_key":"secret-fixture"}""".byteInputStream())
        }
        assertFalse(error.message!!.contains("secret-fixture"))
        assertThrows(IllegalArgumentException::class.java) { SettingsStore.parseTunnel(ByteArray(128 * 1024 + 1).inputStream()) }
    }

    @Test fun missingTunnelCannotBeEnabledOrPartiallyChangeConnectionSettings() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("sync_settings", Context.MODE_PRIVATE).edit().clear().commit()
        val store = SettingsStore(context)
        val original = store.state.value.copy(baseUrl = "https://old.example/dav/", softwareUploadPath = "apps", fileUploadPath = "backups")
        store.save(original, null)
        val saved = store.state.value
        assertThrows(IllegalStateException::class.java) { store.save(saved.copy(sshEnabled = true, baseUrl = "https://new.example/dav/"), null) }
        assertEquals(saved, store.state.value)
        assertFalse(store.state.value.sshEnabled)
    }
}
