package dev.multisync.app.sync

import dev.multisync.core.shellQuote
import dev.multisync.core.checkCancellation
import android.os.Build
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object RootShell {
    private val outputExecutor = Executors.newCachedThreadPool()
    data class Result(val code: Int, val output: String)

    private fun execute(command: String, timeoutSeconds: Long): Result {
        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        val output = outputExecutor.submit<String> {
            process.inputStream.bufferedReader().use { reader ->
                val result = StringBuilder()
                val buffer = CharArray(2048)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    if (result.length < 8192) result.append(buffer, 0, count.coerceAtMost(8192 - result.length))
                }
                result.toString()
            }
        }
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
            while (!hasExited(process)) {
                checkCancellation()
                check(System.nanoTime() < deadline) { "Root 操作超时" }
                Thread.sleep(50)
            }
            return Result(process.exitValue(), output.get(5, TimeUnit.SECONDS).trim())
        } finally {
            if (!hasExited(process)) {
                if (Build.VERSION.SDK_INT >= 26) process.destroyForcibly() else process.destroy()
            }
            output.cancel(true)
        }
    }

    private fun hasExited(process: Process): Boolean = try { process.exitValue(); true } catch (_: IllegalThreadStateException) { false }

    fun check(): Boolean = execute("id -u", 30).let { it.code == 0 && it.output.trim() == "0" }

    fun install(apk: File) {
        check(apk.isFile) { "安装文件不存在" }
        val result = execute("pm install -r --user 0 ${shellQuote(apk.canonicalPath)}", 180)
        check(result.code == 0 && result.output.lineSequence().any { it.trim() == "Success" }) {
            "安装失败：${result.output.ifBlank { "Root 未授权或安装器返回错误" }}"
        }
    }
}
