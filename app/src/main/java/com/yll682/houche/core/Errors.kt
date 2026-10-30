package com.yll682.houche.core

import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.util.logging.Level
import java.util.logging.Logger

class BusException(val kind: Kind, override val message: String, cause: Throwable? = null) : Exception(message, cause) {
    enum class Kind { NETWORK, UPSTREAM, ARGUMENT }
}

// 核心层保持纯 JVM；Android 的 java.util.logging 同样会写入系统日志。
internal val logger: Logger = Logger.getLogger("Houche")
internal fun failure(error: Exception, message: String): BusException {
    if (error is CancellationException) throw error
    val wrapped = error as? BusException ?: BusException(
        if (error is IOException || error is io.ktor.client.plugins.HttpRequestTimeoutException) BusException.Kind.NETWORK else BusException.Kind.UPSTREAM,
        message, error
    )
    logger.log(Level.WARNING, wrapped.message, wrapped)
    return wrapped
}
internal fun argument(condition: Boolean, message: String) {
    if (!condition) throw BusException(BusException.Kind.ARGUMENT, message)
}
