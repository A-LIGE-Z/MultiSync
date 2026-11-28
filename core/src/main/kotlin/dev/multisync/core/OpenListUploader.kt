package dev.multisync.core

import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.io.RandomAccessFile
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import java.util.concurrent.ExecutionException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLongArray
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Call
import okhttp3.Protocol
import okio.BufferedSink

class OpenListResponseException(val statusCode: Int, operation: String, detail: String = "") :
    IllegalStateException("OpenList $operation 失败（状态 $statusCode）${if (detail.isBlank()) "" else "：$detail"}")

@Serializable
internal data class UploadSession(
    val upload_id: String,
    val state: String,
    val size: Long,
    val chunk_size: Long,
    val total_chunks: Int,
    val received: List<List<Int>> = emptyList(),
    val received_bytes: Long = 0,
    val storage_progress: Double = 0.0,
    val frontier: Int = 0,
    val error: String = "",
) {
    fun hasChunk(index: Int) = received.any { index in it[0]..it[1] }
}

/** Uses the same bounded upload window as the OpenList web client. Credentials stay in memory. */
internal class OpenListUploader(
    private val davBase: HttpUrl,
    private val username: String,
    private val password: String,
    client: OkHttpClient,
    private val onTransfer: (TransferProgress) -> Unit,
    private val pollMillis: Long = 2_000,
    private val maxWaitMillis: Long = TimeUnit.MINUTES.toMillis(20),
    private val onEvent: (String, String) -> Unit = { _, _ -> },
) {
    companion object {
        private val cancellationTimer = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "multisync-request-cancellation").apply { isDaemon = true }
        }
    }
    // Preserve a reverse-proxy prefix, e.g. /openlist/dav/ -> /openlist/api/.
    private val apiBase = davBase.takeIf { it.pathSegments.dropLast(1).lastOrNull() == "dav" }
        ?.newBuilder()?.removePathSegment(davBase.pathSegments.lastIndex)
        ?.removePathSegment(davBase.pathSegments.lastIndex - 1)?.addPathSegment("api")?.addPathSegment("")?.build()
    private val transport = client.newBuilder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .readTimeout(60, TimeUnit.SECONDS).callTimeout(3, TimeUnit.MINUTES).build()
    private val nativeTransport = transport.newBuilder().protocols(listOf(Protocol.HTTP_1_1))
        .readTimeout(15, TimeUnit.MINUTES).callTimeout(20, TimeUnit.MINUTES).build()
    private var chunkSize: Long? = null
    private var discovered = false
    private var token: String? = null
    private val activeCalls = ConcurrentHashMap.newKeySet<Call>()
    // Some OpenList forks encode an empty Go slice as null instead of [].
    private val protocolJson = Json(SyncJson) { coerceInputValues = true }
    private val inspected = mutableSetOf<String>()

    fun inspectDestination(path: String) {
        if (!supported()) return
        val directory = "/" + normalizeRemotePath(path).substringBeforeLast('/', "")
        if (!inspected.add(directory)) return
        val data = try { call("fs/list", "读取上传方式") {
            post(buildJsonObject { put("path", directory); put("password", ""); put("page", 1); put("per_page", 1); put("refresh", false) }
                .toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        } } catch (exception: Exception) { checkCancellation(); return }
        val objectData = data as? JsonObject ?: return
        val provider = (objectData["provider"] as? JsonPrimitive)?.content.orEmpty()
        val tools = (objectData["direct_upload_tools"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
        onEvent("上传方式检查", "驱动 ${safeDetail(provider)}；直传 ${safeDetail(tools.joinToString().ifBlank { "未提供" })}")
    }

    private fun url(endpoint: String) = checkNotNull(apiBase).newBuilder().apply {
        endpoint.split('/').forEach(::addPathSegment)
    }.build()

    fun supported(): Boolean {
        if (discovered) return chunkSize != null
        if (apiBase == null) { discovered = true; return false }
        val data = try {
            response(Request.Builder().url(url("public/settings")).get().build(), "读取上传能力")
        } catch (exception: OpenListResponseException) {
            if (exception.statusCode !in listOf(404, 405)) throw exception
            discovered = true
            return false
        }
        val settings = data as? JsonObject
        if ((settings?.get("multipart_enabled") as? JsonPrimitive)?.content == "true") {
            val mb = (settings["multipart_chunk_size"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 10
            chunkSize = mb.coerceIn(1, 10) * 1024 * 1024
        }
        discovered = true
        return chunkSize != null
    }

    private fun login(): String {
        val hashed = digest("$password-https://github.com/alist-org/alist".byteInputStream()).sha256
        val body = buildJsonObject { put("username", username); put("password", hashed); put("otp_code", "") }
            .toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val data = try {
            response(Request.Builder().url(url("auth/login/hash")).post(body).build(), "登录")
        } catch (exception: OpenListResponseException) {
            val detail = when (exception.statusCode) {
                401 -> "请检查账号和密码"
                402 -> "账号启用了两步验证，当前连接尚未配置验证码"
                429 -> "登录暂时受限，请稍后重试"
                else -> "请检查原生 API 登录权限"
            }
            throw OpenListResponseException(exception.statusCode, "登录", detail)
        }
        return ((data as? JsonObject)?.get("token") as? JsonPrimitive)?.content
            ?.takeIf { it.isNotBlank() }?.also { token = it } ?: throw IOException("OpenList 登录响应缺少令牌")
    }

    private fun call(endpoint: String, operation: String, build: Request.Builder.() -> Unit): JsonElement? {
        fun request() = Request.Builder().url(url(endpoint)).header("Authorization", token ?: login()).apply(build).build()
        // A failed login is final; only an expired token on an authenticated operation gets refreshed.
        val first = request()
        return try { response(first, operation) }
        catch (exception: OpenListResponseException) {
            if (exception.statusCode != 401) throw exception
            token = null
            response(request(), operation)
        }
    }

    private fun safeDetail(message: String): String {
        var safe = message.replace(Regex("https?://\\S+"), "[服务器地址]")
        if (password.isNotEmpty()) safe = safe.replace(password, "[凭据]")
        token?.let { safe = safe.replace(it, "[令牌]") }
        return safe.take(250)
    }

    private fun response(request: Request, operation: String): JsonElement? {
        checkCancellation()
        val call = (if (request.url.encodedPath.endsWith("/fs/resumable")) nativeTransport else transport).newCall(request)
        activeCalls.add(call)
        val owner = Thread.currentThread()
        val cancellation = cancellationTimer.scheduleAtFixedRate({ if (owner.isInterrupted) call.cancel() }, 250, 250, TimeUnit.MILLISECONDS)
        try { call.execute().use { response ->
            // Do not include raw HTML, response bodies or request headers in exceptions.
            val bytes = response.body?.byteStream()?.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    checkCancellation()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > 1024 * 1024) throw IOException("OpenList 响应超过大小限制")
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            } ?: byteArrayOf()
            val json = runCatching { SyncJson.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject }.getOrNull()
            val code = (json?.get("code") as? JsonPrimitive)?.intOrNull
            if (!response.isSuccessful || (code != null && code != 200)) {
                throw OpenListResponseException(if (!response.isSuccessful) response.code else checkNotNull(code), operation,
                    safeDetail((json?.get("message") as? JsonPrimitive)?.content.orEmpty()))
            }
            if (code != 200) throw IOException("OpenList $operation 返回无效响应")
            return json["data"]?.takeUnless { it is JsonNull }
        } } finally { cancellation.cancel(false); activeCalls.remove(call) }
    }

    private fun session(data: JsonElement?, size: Long, id: String? = null): UploadSession {
        val value = data?.let { element ->
            try { protocolJson.decodeFromJsonElement<UploadSession>(element) }
            catch (_: kotlinx.serialization.SerializationException) { throw IOException("OpenList 分片会话格式无效") }
        }
            ?: throw IOException("OpenList 分片响应缺少会话")
        check(value.upload_id.isNotBlank() && (id == null || id == value.upload_id) && value.size == size &&
            value.chunk_size in 1..(64L * 1024 * 1024) &&
            value.total_chunks.toLong() == (size - 1) / value.chunk_size + 1 &&
            value.received_bytes in 0..size && value.received.all {
                it.size == 2 && it[0] >= 0 && it[1] >= it[0] && it[1] < value.total_chunks
            }) { "OpenList 返回的分片会话无效" }
        check(value.state in setOf("receiving", "completed", "failed_retriable", "failed_permanent", "aborted")) {
            "OpenList 返回未知上传状态"
        }
        return value
    }

    private fun retryable(exception: Exception) = exception is IOException ||
        (exception is OpenListResponseException && (exception.statusCode >= 500 || exception.statusCode in listOf(409, 429)))

    private fun failureDetail(exception: Exception): String = when (exception) {
        is OpenListResponseException -> "服务器状态 ${exception.statusCode}"
        is java.net.SocketTimeoutException -> "网络等待超时"
        else -> "连接中断"
    }

    /** Returns false only when the server does not offer multipart upload, so the caller falls back to WebDAV. */
    fun upload(path: String, file: File, verifyRemote: (FileDigest) -> Unit): Boolean {
        val multipart = supported()
        if (!multipart || file.length() == 0L) return false
        val expected = digest(file)
        val started = System.nanoTime()
        var reportedAt = 0L
        var reportedBytes = 0L
        val progressLock = Any()
        fun deadline() {
            checkCancellation()
            if ((System.nanoTime() - started) / 1_000_000 >= maxWaitMillis) throw IOException("分片上传等待超时，本地文件已保留，下次检查将尝试恢复")
        }
        fun pause() {
            deadline()
            try { Thread.sleep(pollMillis) }
            catch (_: InterruptedException) { Thread.currentThread().interrupt(); throw InterruptedIOException("任务已取消") }
            deadline()
        }
        fun report(bytes: Long, phase: TransferPhase, storage: Double? = null, force: Boolean = true) = synchronized(progressLock) {
            val now = System.nanoTime()
            if (!force && now - reportedAt < 250_000_000) return@synchronized
            reportedAt = now
            reportedBytes = bytes.coerceIn(0, expected.size)
            onTransfer(TransferProgress(TransferDirection.UPLOAD, path, reportedBytes, expected.size,
                (now - started) / 1_000_000, phase, storage?.takeIf { it.isFinite() }?.coerceIn(0.0, 100.0)?.toFloat()))
        }
        fun status(id: String): UploadSession {
            var errors = 0
            while (true) {
                deadline()
                try {
                    val requestUrl = url("fs/multipart/status").newBuilder().addQueryParameter("upload_id", id).build()
                    return session(call("fs/multipart/status", "查询上传状态") { url(requestUrl); get() }, expected.size, id)
                } catch (exception: Exception) {
                    checkCancellation()
                    if (exception is OpenListResponseException && exception.statusCode == 404) {
                        verifyRemote(expected)
                        val chunk = checkNotNull(chunkSize)
                        return UploadSession(id, "completed", expected.size, chunk, ((expected.size - 1) / chunk + 1).toInt())
                    }
                    if (!retryable(exception) || ++errors >= 6) throw exception
                    pause()
                }
            }
        }
        fun chunk(current: UploadSession, index: Int, measured: (Long) -> Unit = {
            report(current.received_bytes + it, TransferPhase.TRANSFERRING, force = false)
        }): UploadSession {
            val offset = index * current.chunk_size
            val length = minOf(current.chunk_size, expected.size - offset)
            val body = object : RequestBody() {
                override fun contentType() = "application/octet-stream".toMediaType()
                override fun contentLength() = length
                override fun writeTo(sink: BufferedSink) {
                    var sent = 0L
                    RandomAccessFile(file, "r").use { input ->
                        input.seek(offset)
                        val buffer = ByteArray(64 * 1024)
                        while (sent < length) {
                            deadline()
                            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), length - sent).toInt())
                            if (count < 0) throw IOException("本地上传快照读取不完整")
                            sink.write(buffer, 0, count)
                            sent += count
                            measured(sent)
                        }
                    }
                }
            }
            return session(call("fs/multipart/chunk", "上传分片 ${index + 1}/${current.total_chunks}") {
                header("X-Upload-Id", current.upload_id); header("X-Chunk-Index", index.toString()); put(body)
            }, expected.size, current.upload_id)
        }
        fun batch(current: UploadSession, indexes: List<Int>): UploadSession {
            val executor = Executors.newFixedThreadPool(indexes.size)
            val sent = AtomicLongArray(indexes.size)
            val futures = indexes.mapIndexed { position, index ->
                executor.submit<UploadSession> {
                    chunk(current, index) { count ->
                        sent.set(position, count)
                        report(current.received_bytes + (0 until sent.length()).sumOf { sent.get(it) },
                            TransferPhase.TRANSFERRING, force = false)
                    }
                }
            }
            try {
                var failure: Exception? = null
                var completed: UploadSession? = null
                for (future in futures) {
                    try { future.get().takeIf { it.state == "completed" }?.let { completed = it } }
                    catch (exception: ExecutionException) { failure = failure ?: (exception.cause as? Exception ?: IOException("分片传输失败")) }
                }
                completed?.let { return it }
                failure?.let { throw it }
                return status(current.upload_id)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("任务已取消")
            } finally {
                futures.forEach { it.cancel(true) }
                activeCalls.forEach { it.cancel() }
                executor.shutdownNow()
            }
        }
        fun initialize(resumeProof: Boolean = true): UploadSession? {
            for (attempt in 0..2) {
                deadline()
                try {
                    val data = call("fs/multipart/init", "初始化分片上传") {
                        header("File-Path", URLEncoder.encode("/${normalizeRemotePath(path)}", "UTF-8").replace("+", "%20"))
                        header("X-File-Size", expected.size.toString()); header("X-Chunk-Size", checkNotNull(chunkSize).toString())
                        if (resumeProof) header("X-File-Sha256", expected.sha256)
                        header("Last-Modified", file.lastModified().toString())
                        header("Overwrite", "true"); post(byteArrayOf().toRequestBody("application/octet-stream".toMediaType()))
                    }
                    if (data == null) return null
                    val current = session(data, expected.size)
                    onEvent("分片上传", "单片 ${current.chunk_size / (1024 * 1024)} MB，已确认 ${current.received_bytes} 字节，消费位置 ${current.frontier}，网盘进度 ${current.storage_progress.toInt()}%")
                    return current
                } catch (exception: Exception) {
                    if (!retryable(exception) || attempt == 2) throw exception
                    pause()
                }
            }
            error("分片初始化失败")
        }
        report(0, TransferPhase.TRANSFERRING)
        try {
            var snap = initialize() ?: return false
            var restarts = 0
            var errors = 0
            var waitingLogged = false
            var stalled = 0
            var rebuilt = false
            while (true) {
                deadline()
                when (snap.state) {
                    "completed" -> {
                        // Init starts an independent pipeline. Poll first; Complete then only acknowledges
                        // the finished session, instead of holding a request open across a CDN timeout.
                        try { call("fs/multipart/complete", "确认上传完成") {
                            header("X-Upload-Id", snap.upload_id); post(byteArrayOf().toRequestBody())
                        } } catch (exception: Exception) {
                            checkCancellation()
                            if (!retryable(exception) && !(exception is OpenListResponseException && exception.statusCode == 404)) throw exception
                        }
                        report(expected.size, TransferPhase.COMPLETE, 100.0)
                        onEvent("网盘写入完成", "开始远端内容校验")
                        return true
                    }
                    "failed_permanent", "aborted" -> error("OpenList 上传${if (snap.state == "aborted") "已中止" else "失败"}：${safeDetail(snap.error)}，本地文件已保留")
                    "failed_retriable" -> {
                        if (++restarts > 2) error("OpenList 网盘写入失败：${safeDetail(snap.error)}，本地文件已保留")
                        onEvent("网盘写入重试", safeDetail(snap.error))
                        waitingLogged = false
                        pause()
                        // Chunk zero explicitly restarts a failed pipeline; all old received ranges expire.
                        val restarting = snap.copy(received = emptyList(), received_bytes = 0)
                        snap = try { chunk(restarting, 0) }
                        catch (exception: Exception) {
                            checkCancellation()
                            if (!retryable(exception)) throw exception
                            pause()
                            status(snap.upload_id)
                        }
                        errors = 0
                    }
                    else -> {
                        val missing = (0 until snap.total_chunks).filter { !snap.hasChunk(it) }.take(3)
                        val index = missing.firstOrNull()
                        try {
                            snap = if (index == null) {
                                if (!waitingLogged) { onEvent("等待网盘写入", "所有分片已确认"); waitingLogged = true }
                                report(expected.size, TransferPhase.WAITING, snap.storage_progress)
                                pause()
                                status(snap.upload_id)
                            } else {
                                report(snap.received_bytes, TransferPhase.TRANSFERRING)
                                if (missing.size > 1) batch(snap, missing) else chunk(snap, index)
                            }
                            errors = 0
                        } catch (exception: Exception) {
                            checkCancellation()
                            if (exception is OpenListResponseException && exception.statusCode == 404) {
                                // A disappeared session can mean success, expiry or server restart.
                                // Only a full remote SHA/length match makes it successful.
                                verifyRemote(expected)
                                report(expected.size, TransferPhase.COMPLETE, 100.0)
                                return true
                            }
                            if (!retryable(exception) || ++errors >= 6) throw exception
                            onEvent("分片重试", "${failureDetail(exception)}，查询已确认分片后继续")
                            pause()
                            val next = status(snap.upload_id)
                            if (next.state == "receiving" && next.received_bytes == snap.received_bytes && next.frontier == snap.frontier) stalled++
                            else stalled = 0
                            if (stalled >= 2 && !rebuilt) {
                                onEvent("重建停滞上传会话", "连续重试未确认新分片；重新启动本次上传")
                                try { call("fs/multipart/abort", "结束停滞会话") {
                                    header("X-Upload-Id", snap.upload_id); post(byteArrayOf().toRequestBody())
                                } } catch (gone: OpenListResponseException) { if (gone.statusCode != 404) throw gone }
                                snap = initialize(resumeProof = false) ?: throw IOException("OpenList 未能重建分片会话")
                                rebuilt = true
                                stalled = 0
                                errors = 0
                            } else snap = next
                        }
                    }
                }
            }
        } catch (exception: Exception) {
            report(reportedBytes, if (Thread.currentThread().isInterrupted) TransferPhase.CANCELLED else TransferPhase.FAILED)
            // Do not abort on transient network failure: the next init can resume this SHA-identical file.
            throw exception
        }
    }
}
