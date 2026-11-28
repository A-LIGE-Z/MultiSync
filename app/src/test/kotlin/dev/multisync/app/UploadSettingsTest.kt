package dev.multisync.app

import dev.multisync.app.data.SettingsStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class UploadSettingsTest {
    @Test fun bothUploadPathsPersistNormalizeAndSurviveUnrelatedPreferenceUpdates() {
        val context = RuntimeEnvironment.getApplication()
        val store = SettingsStore(context)
        store.save(store.state.value.copy(softwareUploadPath = " /团队/软件/ ", fileUploadPath = "/"), null)
        val restored = SettingsStore(context)
        assertEquals("团队/软件", restored.state.value.softwareUploadPath)
        assertEquals("/", restored.state.value.fileUploadPath)
        restored.save(restored.state.value.copy(theme = "dark"), null)
        assertEquals("团队/软件", SettingsStore(context).state.value.softwareUploadPath)
        assertEquals("/", SettingsStore(context).state.value.fileUploadPath)
    }

    @Test fun invalidUploadDirectoryDoesNotPartiallySaveConnectionOrOtherPaths() {
        val context = RuntimeEnvironment.getApplication()
        val store = SettingsStore(context)
        val before = store.state.value
        assertThrows(IllegalArgumentException::class.java) {
            store.save(before.copy(baseUrl = "https://changed.example/dav/", softwareUploadPath = "valid", fileUploadPath = "invalid/../path"), null)
        }
        assertEquals(before, SettingsStore(context).state.value)
    }
}
