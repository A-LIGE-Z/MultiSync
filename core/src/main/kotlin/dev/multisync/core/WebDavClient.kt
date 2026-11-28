package dev.multisync.core

import java.io.File
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import okhttp3.Credentials
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.RequestBody
import okhttp3.Protocol
import okio.BufferedSink
import okio.Buffer
import okio.ForwardingSink
import okio.buffer
import org.w3c.dom.Element
import org.xml.sax.SAXException

class WebDavResponseException(val statusCode: Int, operation: String) : IllegalStateException("WebDAV $operation 失败（HTTP $statusCode）")

// One process-wide client/pool so that the TLS (HTTP/2) connection is reused across version scans
// instead of paying a fresh handshake on every page load. maxRequestsPerHost lets scans multiplex.
private val sharedDavDispatcher = Dispatcher().apply { maxRequests = 64; maxRequestsPerHost = 16 }
private val sharedDavPool = ConnectionPool(8, 5, TimeUnit.MINUTES)
private val sharedDavClient: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS)
    .writeTimeout(120, TimeUnit.SECONDS).callTimeout(30, TimeUnit.MINUTES).followSslRedirects(false)
    .dispatcher(sharedDavDispatcher).connectionPool(sharedDavPool)
    .build()

class WebDavClient(
    baseUrl: String, username: String, password: String, allowHttp: Boolean = false,
    private val client: OkHttpClient = sharedDavClient,
    private val onTransfer: (TransferProgress) -> Unit = {},
    preferOpenListUpload: Boolean = false,
    onUploadEvent: (String, String) -> Unit = { _, _ -> },
    private val secureTunnel: Boolean = false,
) : CloudGateway {
    private val base: HttpUrl = checkNotNull(normalizeWebDavUrl(baseUrl).toHttpUrlOrNull()) {
        "WebDAV 地址格式不正确"
    }.also {
        require(it.isHttps || allowHttp) { "请使用 HTTPS，或在设置中明确允许 HTTP" }
    }
    // The SSH loopback leg is HTTP, but signed cloud downloads may upgrade to HTTPS.
    // HTTP/2 lets a version scan multiplex many PROPFIND/GET over one TLS connection, which is the key
    // HTTP/1.1: a single reused connection is enough and avoids h2-related rate-limit quirks on some CDNs.
    private val transport = client.newBuilder().protocols(listOf(Protocol.HTTP_1_1)).apply {
        if (secureTunnel) {
            followSslRedirects(true)
            addNetworkInterceptor { chain ->
                require(safeTunnelDestination(base, chain.request().url)) { "加密通道下载不允许转向未加密的外部地址" }
                chain.proceed(chain.request())
            }
        }
    }.build()
    private val uploadTransport = transport.newBuilder().readTimeout(10, TimeUnit.MINUTES).build()
    private val metadataTransport = transport.newBuilder().followRedirects(false).followSslRedirects(false).callTimeout(20, TimeUnit.SECONDS).build()
    // Directory listings and small record reads walk many requests in sequence. Bound every call so a
    // stalled request surfaces as a timeout instead of the old 30-minute "读取中" freeze.
    private val readTransport = transport.newBuilder().callTimeout(25, TimeUnit.SECONDS).build()
    private val authorization = Credentials.basic(username, password, Charsets.UTF_8)
    private val openList = if (preferOpenListUpload) OpenListUploader(base, username, password, client, onTransfer, onEvent = onUploadEvent) else null

    internal fun url(path: String): HttpUrl {
        if (path.isEmpty() || path == "/") return base
        val normalized = normalizeRemotePath(path)
        return base.newBuilder().apply { normalized.split('/').forEach(::addPathSegment) }.build()
    }

    private fun request(path: String): Request.Builder = Request.Builder().url(url(path)).header("Authorization", authorization)
        .apply { if (secureTunnel) header("X-Forwarded-Proto", "https") }

    // Space requests out a little and transparently retry transient rate limits (429/503/408),
    // honouring Retry-After. Without this a single 429 (e.g. from Cloudflare) fails a whole rule.
    private fun execute(callClient: OkHttpClient, request: Request): okhttp3.Response {
        var attempts = 0
        while (true) {
            checkCancellation()
            val response = callClient.newCall(request).execute()
            val code = response.code
            if (code == 429 || code == 503 || code == 408) {
                val retryAfterMs = response.header("Retry-After")?.trim()?.toLongOrNull()?.let { it * 1000L }
                if (attempts < 3) {
                    response.close()
                    val baseDelay = retryAfterMs ?: (1000L shl attempts.coerceAtMost(3)) // 1,2,4,8s
                    val wait = (baseDelay.coerceIn(500L, 8000L) + (0L..700L).random())
                    try { Thread.sleep(wait) } catch (interrupted: InterruptedException) {
                        Thread.currentThread().interrupt(); throw InterruptedIOException("任务已取消")
                    }
                    attempts++
                    continue
                }
            } else if (code in 200..299 || code == 207) {
                // brief, uniform spacing between successful calls to avoid burst rate limits
                try { Thread.sleep(150) } catch (interrupted: InterruptedException) { Thread.currentThread().interrupt(); throw InterruptedIOException("任务已取消") }
            }
            return response
        }
    }

    override fun exists(path: String): Boolean {
        checkCancellation()
        execute(metadataTransport, request(path).head().build()).use {
            if (it.code == 404) return false
            if (it.isSuccessful) return true
            if (it.code !in 300..399 && it.code !in listOf(403, 405, 501)) error("WebDAV 查询失败（HTTP ${it.code}）")
        }
        // OpenList can redirect HEAD to a provider URL signed for GET only. Verify the
        // authenticated WebDAV object's metadata rather than following that signed URL.
        val body = """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/></d:prop></d:propfind>"""
        val bytes = execute(metadataTransport, request(path).header("Depth", "0")
            .method("PROPFIND", body.toRequestBody("application/xml; charset=utf-8".toMediaType())).build()).use {
            if (it.code == 404) return false
            check(it.code == 207 || it.isSuccessful) { "WebDAV 元数据查询失败（HTTP ${it.code}）" }
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            val input = it.body?.byteStream() ?: error("WebDAV 元数据响应为空")
            input.use { stream -> while (true) {
                checkCancellation()
                val count = stream.read(buffer)
                if (count < 0) break
                require(output.size() + count <= 1024 * 1024) { "WebDAV 元数据响应过大" }
                output.write(buffer, 0, count)
            } }
            output.toByteArray()
        }
        // A Depth:0 PROPFIND on the exact URL returns the resource itself when it exists. Treat any
        // response entry as "exists" — strict href/encoding matching caused false negatives (re-uploads).
        val responses = safeDocument(bytes).getElementsByTagNameNS("DAV:", "response")
        return responses.length > 0
    }

    fun checkConnection() {
        list("")
    }

    private fun ensureParent(path: String) {
        val segments = normalizeRemotePath(path).split('/').dropLast(1)
        for (index in segments.indices) {
            checkCancellation()
            val parent = segments.take(index + 1).joinToString("/")
            execute(transport, request(parent).method("MKCOL", null).build()).use {
                check(it.isSuccessful || (it.code == 405 && exists(parent))) { "远端目录创建失败（HTTP ${it.code}）" }
            }
        }
    }

    override fun putFile(path: String, file: File) {
        ensureParent(path)
        openList?.inspectDestination(path)
        if (openList?.upload(path, file) { expected -> verify(path, expected) } == true) return
        val delegate = file.asRequestBody("application/octet-stream".toMediaType())
        val tracker = TransferTracker(TransferDirection.UPLOAD, path, delegate.contentLength(), onTransfer)
        val body = object : RequestBody() {
            override fun contentType() = delegate.contentType()
            override fun contentLength() = delegate.contentLength()
            override fun writeTo(sink: BufferedSink) {
                tracker.start()
                val counting = object : ForwardingSink(sink) {
                    override fun write(source: Buffer, byteCount: Long) { checkCancellation(); super.write(source, byteCount); tracker.add(byteCount) }
                }.buffer()
                delegate.writeTo(counting)
                counting.flush()
                tracker.waiting()
            }
        }
        try {
            execute(uploadTransport, request(path).put(body).build()).use { if (!it.isSuccessful) throw WebDavResponseException(it.code, "上传") }
            tracker.complete()
        } catch (exception: Exception) { tracker.failed(); throw exception }
    }

    override fun putBytes(path: String, bytes: ByteArray) {
        ensureParent(path)
        execute(uploadTransport, request(path).put(bytes.toRequestBody("application/json; charset=utf-8".toMediaType())).build()).use {
            if (!it.isSuccessful) throw WebDavResponseException(it.code, "记录提交")
        }
    }

    override fun copyFile(source: String, destination: String): Boolean {
        ensureParent(destination)
        checkCancellation()
        execute(metadataTransport, request(source).header("Destination", url(destination).toString())
            .header("Overwrite", "F").header("Depth", "0").method("COPY", null).build()).use {
            if (it.code in listOf(405, 501)) return false
            if (!it.isSuccessful) throw WebDavResponseException(it.code, "安装包复制")
            return true
        }
    }

    override fun open(path: String): InputStream {
        checkCancellation()
        // Release records are small; read them on the bounded transport so a stuck GET fails fast.
        val callTransport = if (path.endsWith(".json", ignoreCase = true)) readTransport else transport
        val response = execute(callTransport, request(path).get().build())
        if (!response.isSuccessful) {
            val code = response.code
            response.close()
            error("下载或校验读取失败（HTTP $code）")
        }
        val body = response.body ?: run { response.close(); error("远端返回空响应") }
        val tracked = !path.endsWith(".json", ignoreCase = true)
        val tracker = TransferTracker(TransferDirection.DOWNLOAD, path, body.contentLength().takeIf { it >= 0 }, if (tracked) onTransfer else ({ _ -> }))
        tracker.start()
        return object : java.io.FilterInputStream(body.byteStream()) {
            override fun read(): Int = measured { super.read().also { if (it >= 0) tracker.add(1) else tracker.complete() } }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int = measured {
                super.read(bytes, offset, length).also { if (it > 0) tracker.add(it.toLong()) else if (it < 0) tracker.complete() }
            }
            private fun measured(read: () -> Int): Int = try { checkCancellation(); read() } catch (exception: Exception) { tracker.failed(); throw exception }
            override fun close() { try { super.close() } finally { response.close(); tracker.closed() } }
        }
    }

    override fun list(path: String): List<RemoteEntry> = readListing(path, missingIsEmpty = true)

    fun listDirectories(path: String): List<RemoteEntry> = readListing(path, missingIsEmpty = false).filter { it.isDirectory }

    private fun readListing(path: String, missingIsEmpty: Boolean): List<RemoteEntry> {
        val body = """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/></d:prop></d:propfind>"""
        val bytes = execute(readTransport, request(path).header("Depth", "1")
            .method("PROPFIND", body.toRequestBody("application/xml; charset=utf-8".toMediaType())).build()).use {
            if (it.code == 404 && missingIsEmpty) return emptyList()
            check(it.code != 404) { "目录不存在，请返回上级选择或手动填写路径" }
            check(it.code == 207 || it.isSuccessful) { "目录读取失败（HTTP ${it.code}）" }
            val input = it.body?.byteStream() ?: error("目录响应为空")
            input.use { stream ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    checkCancellation()
                    val count = stream.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= 8 * 1024 * 1024) { "目录响应过大，请拆分目录" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        }
        return parseListing(bytes, url(path))
    }

    internal fun parseListing(bytes: ByteArray, directory: HttpUrl): List<RemoteEntry> {
        val document = safeDocument(bytes)
        val responses = document.getElementsByTagNameNS("DAV:", "response")
        val directoryPath = directory.encodedPath.trimEnd('/')
        return buildList {
            for (index in 0 until responses.length) {
                val element = responses.item(index) as? Element ?: continue
                val href = element.getElementsByTagNameNS("DAV:", "href").item(0)?.textContent ?: continue
                val collectionUrl = if (directory.encodedPath.endsWith('/')) directory else directory.newBuilder().addPathSegment("").build()
                val resolved = collectionUrl.resolve(href) ?: continue
                if (resolved.scheme != directory.scheme || resolved.host != directory.host || resolved.port != directory.port) continue
                val path = resolved.encodedPath.trimEnd('/')
                if (!path.startsWith("$directoryPath/")) continue
                val child = path.removePrefix("$directoryPath/")
                if (child.isEmpty() || child.contains('/')) continue
                val name = URLDecoder.decode(child.replace("+", "%2B"), "UTF-8")
                if (name == "." || name == ".." || name.contains('/') || name.contains('\\')) continue
                val propstats = element.getElementsByTagNameNS("DAV:", "propstat")
                for (p in 0 until propstats.length) {
                    val propstat = propstats.item(p) as? Element ?: continue
                    val status = propstat.getElementsByTagNameNS("DAV:", "status").item(0)?.textContent ?: ""
                    if (!status.contains(" 200 ")) continue
                    val directoryFlag = propstat.getElementsByTagNameNS("DAV:", "collection").length > 0
                    val size = propstat.getElementsByTagNameNS("DAV:", "getcontentlength").item(0)?.textContent?.toLongOrNull() ?: 0
                    add(RemoteEntry(name, directoryFlag, size))
                    break
                }
            }
        }.distinctBy { it.name }
    }

    private fun safeDocument(bytes: ByteArray): org.w3c.dom.Document {
        require(!bytes.toString(Charsets.UTF_8).contains("<!DOCTYPE", ignoreCase = true)) { "目录 XML 包含无效声明" }
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }
        val builder = factory.newDocumentBuilder().apply { setEntityResolver { _, _ -> throw SAXException("External entity disabled") } }
        return builder.parse(bytes.inputStream())
    }
}
