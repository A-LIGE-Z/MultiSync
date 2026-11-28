package dev.multisync.core

import org.junit.Assert.*
import org.junit.Test

class VersionPolicyTest {
    private fun release(version: Long = 20, signer: String = "trusted", minSdk: Int = 24, abi: String = "arm64-v8a") = Release(
        packageName = "com.example.app", label = "A", versionCode = version, versionName = "2.0", signerDigests = listOf(signer),
        apkPath = "apps/A/packages/a.apk", sha256 = "a".repeat(64), size = 10, minSdk = minSdk,
        supportedAbis = listOf(abi), publisherId = "phone", publishedAt = 1,
    )
    private fun choose(releases: List<Release>) = VersionPolicy.choose(releases, "com.example.app", 10, setOf("trusted"), 35, setOf("arm64-v8a"))

    @Test fun higherVersionWinsRegardlessOfUploadTime() {
        assertEquals(30L, choose(listOf(release(20).copy(publishedAt = 1000), release(30).copy(publishedAt = 1)))?.versionCode)
    }
    @Test fun downgradeSameVersionWrongSignerAndIncompatiblePackagesAreExcluded() {
        assertNull(choose(listOf(release(9), release(10), release(signer = "other"), release(minSdk = 36), release(abi = "x86_64"))))
    }
    @Test fun unpinnedFirstInstallationIsNotAutomaticallyTrusted() {
        assertNull(VersionPolicy.choose(listOf(release()), "com.example.app", 0, emptySet(), 35, setOf("arm64-v8a")))
    }
    @Test fun shellParametersDoNotEscapeTheirSingleQuotedArgument() {
        assertEquals("'a'\\''; echo bad'", shellQuote("a'; echo bad"))
    }
}
