package com.nuvio.app.features.pmdb

/**
 * Packaged desktop builds have no console, so sync failures are also written to PublicMetaDB's
 * storage (one entry, replaced on each failure) where they can be read back without a debugger.
 * Messages come from exception text only; request headers and API keys are never included.
 */
internal object PublicMetaDbDiagnostics {
    private const val LAST_ERROR_KEY = "pmdb_last_error"

    /** One-line description such as "IOException: Connection reset <- SocketException: ...". */
    fun summary(error: Throwable): String = buildString {
        append(error::class.simpleName ?: "Error")
        error.message?.takeIf { it.isNotBlank() }?.let { append(": ").append(it.take(160)) }
        error.cause?.let { cause ->
            append(" <- ").append(cause::class.simpleName ?: "Error")
            cause.message?.takeIf { it.isNotBlank() }?.let { append(": ").append(it.take(120)) }
        }
    }

    fun record(where: String, error: Throwable) {
        val text = buildString {
            append(kotlin.time.Clock.System.now()).append(" | ").append(where).append(" | ").append(summary(error)).append('\n')
            append(error.stackTraceToString().lines().take(14).joinToString("\n"))
        }
        runCatching { PublicMetaDbPlatform.persistence.write(LAST_ERROR_KEY, text.take(3_000)) }
    }
}
