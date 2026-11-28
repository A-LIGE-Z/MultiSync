package dev.multisync.core

enum class TransferDirection { UPLOAD, DOWNLOAD }
enum class TransferPhase { TRANSFERRING, WAITING, COMPLETE, FAILED, CANCELLED }

data class TransferProgress(
    val direction: TransferDirection,
    val path: String,
    val bytes: Long,
    val totalBytes: Long?,
    val elapsedMillis: Long,
    val phase: TransferPhase,
    val storagePercent: Float? = null,
) {
    val fraction: Float? get() = totalBytes?.takeIf { it > 0 }?.let { (bytes.toDouble() / it).toFloat().coerceIn(0f, 1f) }
    val bytesPerSecond: Double get() = if (elapsedMillis > 0) bytes.toDouble() * 1000 / elapsedMillis else 0.0
}

internal class TransferTracker(private val direction: TransferDirection, private val path: String,
    private val total: Long?, private val report: (TransferProgress) -> Unit) {
    private var started = System.nanoTime()
    private var lastReport = 0L
    private var bytes = 0L
    var finished = false
        private set
    fun start() { started = System.nanoTime(); bytes = 0; finished = false; emit(TransferPhase.TRANSFERRING) }
    fun add(count: Long) {
        bytes += count
        if (System.nanoTime() - lastReport >= 250_000_000L) emit(TransferPhase.TRANSFERRING)
    }
    fun position(count: Long) { bytes = count.coerceIn(0, total ?: Long.MAX_VALUE); emit(TransferPhase.TRANSFERRING) }
    fun waiting() = emit(TransferPhase.WAITING)
    fun complete() { if (!finished) { finished = true; emit(TransferPhase.COMPLETE) } }
    fun failed() { finished = true; emit(TransferPhase.FAILED) }
    fun cancelled() { finished = true; emit(TransferPhase.CANCELLED) }
    fun closed() { if (!finished) {
        if (total != null && bytes == total) complete()
        else { finished = true; emit(TransferPhase.CANCELLED) }
    } }
    private fun emit(phase: TransferPhase) {
        lastReport = System.nanoTime()
        report(TransferProgress(direction, path, bytes,
            total ?: bytes.takeIf { phase == TransferPhase.COMPLETE }, (lastReport - started) / 1_000_000, phase))
    }
}
