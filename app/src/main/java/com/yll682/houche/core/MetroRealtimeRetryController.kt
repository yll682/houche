package com.yll682.houche.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select

/**
 * 当前参考站只有一条请求/退避链。等待预算和退避均是 Loading，只有实际失败回调才是失败。
 * scope 由调用方拥有；页面销毁、换站和换线都会取消等待以及在途请求。
 * 时钟、等待和请求可注入，测试无需启动 Android ViewModel 或使用真实秒级等待。
 */
class MetroRealtimeRetryController<T>(
    private val scope: CoroutineScope,
    private val request: suspend (T) -> MetroRealtimeRequestResult,
    private val onLoading: (T) -> Unit,
    private val onResult: (T, MetroRealtime) -> Unit,
    private val sameTarget: (T, T) -> Boolean = { first, second -> first == second },
    private val elapsedRealtimeMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val waitMillis: suspend (Long) -> Unit = { delay(it) }
) {
    private class Chain<T>(val target: T) {
        var job: Job? = null
        var backoffWake: CompletableDeferred<Unit>? = null
    }

    private var current: Chain<T>? = null

    /** 同站刷新复用原链：可唤醒退避，不能绕过额度等待或并行启动第二套请求。 */
    fun refresh(target: T): Job {
        current?.let { previous ->
            previous.job?.takeIf { it.isActive && sameTarget(previous.target, target) }?.let {
                // 单个完成信号合并连续点击；HTTP/额度等待中没有残留的唤醒队列。
                previous.backoffWake?.complete(Unit)
                return it
            }
            previous.job?.cancel()
        }
        val chain = Chain(target)
        current = chain
        return scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var failures = 0
            onLoading(target)
            while (true) {
                ensureCurrent(chain)
                when (val result = request(target)) {
                    is MetroRealtimeRequestResult.Deferred -> {
                        ensureCurrent(chain)
                        // 没发出 HTTP，不产生失败记录，也不占下一次失败的退避档位。
                        waitUntil(chain, result.retryAfterMillis.coerceAtLeast(1))
                    }
                    is MetroRealtimeRequestResult.Completed -> {
                        ensureCurrent(chain)
                        onResult(target, result.realtime)
                        if (result.realtime.online) return@launch
                        ensureCurrent(chain)
                        val backoffMillis = 1_000L shl failures.coerceAtMost(3)
                        failures = (failures + 1).coerceAtMost(3)
                        // 失败本身已单独提交；接下来等待重试，不能把等待误报为请求失败。
                        onLoading(target)
                        waitUntil(chain, backoffMillis, allowRefresh = true)
                    }
                }
            }
        }.also { chain.job = it }
    }

    fun cancel() {
        current?.job?.cancel()
        current = null
    }

    private suspend fun ensureCurrent(chain: Chain<T>) {
        currentCoroutineContext().ensureActive()
        if (current !== chain) throw CancellationException("地铁实时参考站已改变")
    }

    private suspend fun waitUntil(chain: Chain<T>, durationMillis: Long, allowRefresh: Boolean = false) {
        val startedAt = elapsedRealtimeMillis()
        var remaining = durationMillis
        val wake = if (allowRefresh) CompletableDeferred<Unit>() else null
        chain.backoffWake = wake
        try {
            do {
                val refreshed = if (wake == null) {
                    waitMillis(remaining)
                    false
                } else coroutineScope {
                    val timer = async(start = CoroutineStart.UNDISPATCHED) { waitMillis(remaining) }
                    try {
                        select {
                            timer.onAwait { false }
                            wake.onAwait { true }
                        }
                    } finally {
                        timer.cancel()
                    }
                }
                ensureCurrent(chain)
                if (refreshed) return
                val elapsed = (elapsedRealtimeMillis() - startedAt).coerceAtLeast(0)
                remaining = durationMillis - elapsed
            } while (remaining > 0)
        } finally {
            if (chain.backoffWake === wake) chain.backoffWake = null
        }
    }
}
