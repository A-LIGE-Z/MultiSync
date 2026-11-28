package dev.multisync.core

import org.junit.Assert.*
import org.junit.Test

class UploadPathsTest {
    @Test fun followingRulesResolveAgainstCurrentConfigurationAndKeepAppsSeparate() {
        val app = AppRule("a", "应用", "com.example.app", "old/place", followUploadPath = true)
        val backup = BackupRule("b", "备份", "content://backup", "old/place", followUploadPath = true)
        assertEquals("team/software/应用", appUploadPath(app, "/team/software/"))
        assertEquals("another/应用", appUploadPath(app, "another"))
        assertEquals("team/files", backupUploadPath(backup, "team/files"))
        assertEquals("another/files", backupUploadPath(backup, "another/files"))
    }

    @Test fun legacyRulesKeepCustomPathsInsteadOfBeingSilentlyMoved() {
        val app = SyncJson.decodeFromString<AppRule>("""{"id":"a","name":"应用","packageName":"com.example.app","remotePath":"custom/app"}""")
        val backup = SyncJson.decodeFromString<BackupRule>("""{"id":"b","name":"备份","sourceTreeUri":"content://backup","remotePath":"custom/backup"}""")
        assertFalse(app.followUploadPath)
        assertFalse(backup.followUploadPath)
        assertEquals("custom/app", appUploadPath(app, "different"))
        assertEquals("custom/backup", backupUploadPath(backup, "different"))
    }

    @Test fun webDavRootIsAnExplicitDirectoryAndChildrenStayRelativeToTheConfiguredBase() {
        assertEquals("/", normalizeRemoteDirectory(" / "))
        assertEquals("A", appUploadPath(AppRule("a", "A", "com.example.app", "old", followUploadPath = true), "/"))
        assertEquals("/", backupUploadPath(BackupRule("b", "B", "content://backup", "old", followUploadPath = true), "/"))
        assertEquals("objects/hash", remoteChildPath("/", "objects/hash"))
        assertThrows(IllegalArgumentException::class.java) { normalizeRemoteDirectory("") }
        assertThrows(IllegalArgumentException::class.java) { remoteChildPath("/", "../outside") }
    }

    @Test fun applicationNameIsUsedInsteadOfARuleNicknameAndOldPackageDirectoriesAreRecognized() {
        val rule = AppRule("a", "手机发布规则", "com.example.app", "team/com.example.app", applicationName = "应用名称")
        assertEquals("team/应用名称", appUploadPath(rule, "unused"))
        assertEquals("new/应用名称", appUploadPath(rule.copy(followUploadPath = true), "new"))
        assertEquals("A_B_C", applicationFolderName("A/B\\C\n"))
        assertEquals("未命名应用", applicationFolderName(".."))
    }
}
