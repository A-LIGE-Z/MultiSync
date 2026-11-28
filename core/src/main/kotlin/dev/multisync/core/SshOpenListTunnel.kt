package dev.multisync.core

import com.jcraft.jsch.*
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okio.ByteString.Companion.decodeBase64

@Serializable
data class SshTunnelConfig(
    val host: String, val username: String, val host_key: String, val private_key: String,
    val port: Int = 22, val openlist_urls: List<String> = emptyList(), val openlist_url: String = "",
) {
    override fun toString() = "SshTunnelConfig(host=$host, port=$port, private_key=[hidden])"

    fun validate(baseUrl: String) {
        require(host.isNotBlank() && !host.contains('/') && !host.any(Char::isWhitespace) && port in 1..65535 && username.isNotBlank()) { "上传通道地址或账号无效" }
        require(host_key.decodeBase64()?.size?.let { it >= 32 } == true) { "上传通道缺少有效的服务器主机密钥" }
        require(private_key.contains("BEGIN OPENSSH PRIVATE KEY") || private_key.contains("BEGIN RSA PRIVATE KEY") ||
            private_key.contains("BEGIN PRIVATE KEY")) { "上传通道缺少有效的私钥" }
        val origin = davOrigin(baseUrl)
        val bound = openlist_urls.ifEmpty { listOf(openlist_url) }
        require(bound.any { it.toHttpUrlOrNull()?.let { value -> identity(value) == identity(origin) } == true }) {
            "此上传通道未绑定当前 OpenList，请导入对应服务的通道"
        }
    }
}

private fun identity(url: HttpUrl) = listOf(url.scheme, url.host, url.port.toString(), url.encodedPath.trimEnd('/'))

internal fun safeTunnelDestination(base: HttpUrl, next: HttpUrl) = next.isHttps ||
    (next.scheme == base.scheme && next.host == base.host && next.port == base.port)

internal fun davOrigin(baseUrl: String): HttpUrl {
    val base = checkNotNull(baseUrl.trim().let { if (it.endsWith('/')) it else "$it/" }.toHttpUrlOrNull()) { "WebDAV 地址无效" }
    require(base.username.isEmpty() && base.password.isEmpty() && base.query == null && base.fragment == null) { "连接地址不能包含凭据" }
    require(base.pathSegments.dropLast(1).lastOrNull() == "dav") { "上传通道需要以 /dav/ 结尾的 OpenList 地址" }
    return base.newBuilder().removePathSegment(base.pathSegments.lastIndex).removePathSegment(base.pathSegments.lastIndex - 1).addPathSegment("").build()
}

internal class PinnedHostKeys(private val hostname: String, key: ByteArray) : HostKeyRepository {
    private val pinned = key.copyOf()
    override fun check(host: String?, key: ByteArray?): Int =
        if (host == hostname && key != null && MessageDigest.isEqual(pinned, key)) HostKeyRepository.OK else HostKeyRepository.CHANGED
    override fun add(hostkey: HostKey?, ui: UserInfo?) { error("不能自动信任新的 SSH 主机密钥") }
    override fun remove(host: String?, type: String?) = Unit
    override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
    override fun getKnownHostsRepositoryID() = "MultiSync pinned upload server"
    override fun getHostKey(): Array<HostKey> = arrayOf(HostKey(hostname, pinned))
    override fun getHostKey(host: String?, type: String?): Array<HostKey> =
        getHostKey().filter { (host == null || it.host == host) && (type == null || it.type == type) }.toTypedArray()
}

/** Only creates a loopback forwarding channel to the shared OpenList port; never executes commands. */
class SshOpenListTunnel private constructor(private val session: Session, private val jsch: JSch, val davUrl: String) : AutoCloseable {
    override fun close() { session.disconnect(); jsch.removeAllIdentity() }

    companion object {
        fun open(config: SshTunnelConfig, baseUrl: String): SshOpenListTunnel {
            config.validate(baseUrl)
            checkCancellation()
            // Android does not expose the JDK 15 EdDSA APIs used by JSch's default JVM provider.
            // Use the same portable implementation on JVM and Android, while retaining exact host-key pinning.
            JSch.setConfig("ssh-ed25519", "com.jcraft.jsch.bc.SignatureEd25519")
            JSch.setConfig("keypairgen.eddsa", "com.jcraft.jsch.bc.KeyPairGenEdDSA")
            val jsch = JSch()
            val hostLabel = if (config.port == 22) config.host else "[${config.host}]:${config.port}"
            val repository = PinnedHostKeys(hostLabel, checkNotNull(config.host_key.decodeBase64()).toByteArray())
            jsch.hostKeyRepository = repository
            val hostType = repository.getHostKey().single().type
            val bytes = config.private_key.toByteArray(Charsets.UTF_8)
            var session: Session? = null
            try {
                try { jsch.addIdentity("multisync-upload", bytes, null, null) } finally { bytes.fill(0) }
                session = jsch.getSession(config.username, config.host, config.port).apply {
                    setConfig("StrictHostKeyChecking", "yes")
                    setConfig("PreferredAuthentications", "publickey")
                    setConfig("server_host_key", if (hostType == "ssh-rsa") "rsa-sha2-512,rsa-sha2-256" else hostType)
                    timeout = 120_000
                    setServerAliveInterval(20_000)
                    setServerAliveCountMax(3)
                    connect(20_000)
                }
                checkCancellation()
                val localPort = session.setPortForwardingL("127.0.0.1", 0, "127.0.0.1", 5244)
                val path = checkNotNull(baseUrl.toHttpUrlOrNull()).encodedPath
                return SshOpenListTunnel(session, jsch, "http://127.0.0.1:$localPort$path")
            } catch (exception: Exception) {
                session?.disconnect(); jsch.removeAllIdentity()
                checkCancellation()
                val message = when {
                    exception.message.orEmpty().contains("HostKey", ignoreCase = true) -> "SSH 服务器主机密钥不匹配，连接已拒绝"
                    exception.message.orEmpty().contains("Auth", ignoreCase = true) -> "SSH 上传通道认证失败，请核对账号和私钥"
                    else -> "SSH 上传通道连接失败，请检查配置和网络"
                }
                throw IllegalStateException(message)
            }
        }
    }
}
