package com.yll682.houche.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select

const val METRO_ETA_LOADING_LIMIT_MILLIS = 8_000L

/**
 * 每个未成功目标保留同一条需求：切站、手刷不能重置首次等待截止或失败退避档位。
 * 初次等待（含额度 Deferred）最多八秒；真实失败/超时后保留 Unavailable 直至成功。
 * scope 由页面拥有；取消会停止当前请求，但保留未解决需求，成功才重开下次八秒窗口。
 */
class MetroRealtimeRetryController<T>(
    private val scope: CoroutineScope,
    private val request: suspend (T) -> MetroRealtimeRequestResult,
    private val onLoading: (T) -> Unit,
    private val onResult: (T, MetroRealtime) -> Unit,
    private val sameTarget: (T, T) -> Boolean = { first, second -> first == second },
    private val elapsedRealtimeMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val waitMillis: suspend (Long) -> Unit = { delay(it) },
    /** 每条未解决需求只通知一次；恢复历史失败状态不重复发事件。 */
    private val onUnavailable: (T) -> Unit = {},
    /** 只固定本次请求身份；恢复历史失败时也调用，不代表进入 Loading。 */
    private val onStarted: (T) -> Unit = {}
) {
    private class Demand<T>(val target: T, val startedAt: Long) {
        var failures = 0
        var lastFailure: MetroRealtime? = null
        var backoffUntil = 0L
        var budgetUntil = 0L
        var notified = false
    }

    private class Chain<T>(val target: T, val demand: Demand<T>) {
        var job: Job? = null
        var backoffWake: CompletableDeferred<Unit>? = null
    }

    private val unresolved = mutableListOf<Demand<T>>()
    private var current: Chain<T>? = null

    /** 同目标刷新可唤醒失败退避；不能绕过额度等待或并行建立第二套请求。 */
    fun refresh(target: T): Job {
        current?.let { previous ->
            previous.job?.takeIf { it.isActive && sameTarget(previous.target, target) }?.let {
                previous.backoffWake?.complete(Unit)
                return it
            }
            previous.job?.cancel()
        }
        val demand = unresolved.firstOrNull { sameTarget(it.target, target) }
            ?: Demand(target, elapsedRealtimeMillis()).also { unresolved += it }
        val chain = Chain(target, demand)
        current = chain
        return scope.launch(start = CoroutineStart.UNDISPATCHED) {
            onStarted(target)
            demand.lastFailure?.let { onResult(target, it) } ?: run {
                if (loadingRemaining(demand) > 0) onLoading(target)
            }
            while (true) {
                ensureCurrent(chain)
                if (demand.lastFailure == null && loadingRemaining(demand) <= 0) fail(chain, timeout())
                val now = elapsedRealtimeMillis()
                val budgetRemaining = demand.budgetUntil - now
                if (budgetRemaining > 0) {
                    // 八秒截止必须能打断长额度排队，超时后继续遵守原额度时间。
                    val duration = if (demand.lastFailure == null) minOf(budgetRemaining, loadingRemaining(demand)) else budgetRemaining
                    waitUntil(chain, duration.coerceAtLeast(1))
                    continue
                }
                val backoffRemaining = demand.backoffUntil - now
                if (backoffRemaining > 0) {
                    if (waitUntil(chain, backoffRemaining, allowRefresh = true)) demand.backoffUntil = elapsedRealtimeMillis()
                    continue
                }
                val limit = if (demand.lastFailure == null) loadingRemaining(demand) else METRO_ETA_LOADING_LIMIT_MILLIS
                when (val result = requestWithDeadline(chain, limit.coerceAtLeast(1))) {
                    is MetroRealtimeRequestResult.Deferred -> {
                        ensureCurrent(chain)
                        demand.budgetUntil = elapsedRealtimeMillis() + result.retryAfterMillis.coerceAtLeast(1)
                    }
                    is MetroRealtimeRequestResult.Completed -> {
                        ensureCurrent(chain)
                        if (result.realtime.online) {
                            onResult(target, result.realtime)
                            unresolved.remove(demand)
                            return@launch
                        }
                        fail(chain, result.realtime)
                    }
                }
            }
        }.also { chain.job = it }
    }

    fun cancel() {
        current?.job?.cancel()
        current = null
    }

    private fun loadingRemaining(demand: Demand<T>): Long =
        METRO_ETA_LOADING_LIMIT_MILLIS - (elapsedRealtimeMillis() - demand.startedAt).coerceAtLeast(0)

    private fun timeout(): MetroRealtime = MetroRealtime(false, "本站到站查询等待超时")

    private suspend fun fail(chain: Chain<T>, result: MetroRealtime) {
        ensureCurrent(chain)
        val demand = chain.demand
        demand.lastFailure = result
        val backoffMillis = 1_000L shl demand.failures.coerceAtMost(3)
        demand.failures = (demand.failures + 1).coerceAtMost(3)
        demand.backoffUntil = elapsedRealtimeMillis() + backoffMillis
        onResult(chain.target, result)
        if (!demand.notified) {
            demand.notified = true
            onUnavailable(chain.target)
        }
    }

    private suspend fun requestWithDeadline(chain: Chain<T>, limit: Long): MetroRealtimeRequestResult {
        // 请求独立由拥有者 scope 管理，超时不等待忽略取消的旧 HTTP 返回；结果只在当前链提交。
        val response = scope.async(start = CoroutineStart.UNDISPATCHED) {
            try {
                request(chain.target)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                MetroRealtimeRequestResult.Completed(MetroRealtime(false, "本站到站请求失败：${error.javaClass.simpleName}"))
            }
        }
        var timer: kotlinx.coroutines.Deferred<Unit>? = null
        try {
            if (response.isCompleted) return response.await()
            timer = scope.async(start = CoroutineStart.UNDISPATCHED) { waitMillis(limit) }
            return select {
                response.onAwait { it }
                timer.onAwait { MetroRealtimeRequestResult.Completed(timeout()) }
            }
        } finally {
            response.cancel()
            timer?.cancel()
        }
    }

    private suspend fun ensureCurrent(chain: Chain<T>) {
        currentCoroutineContext().ensureActive()
        if (current !== chain) throw CancellationException("地铁实时参考站已改变")
    }

    private suspend fun waitUntil(chain: Chain<T>, durationMillis: Long, allowRefresh: Boolean = false): Boolean {
        val startedAt = elapsedRealtimeMillis()
        var remaining = durationMillis
        val wake = if (allowRefresh) CompletableDeferred<Unit>() else null
        chain.backoffWake = wake
        try {
            do {
                val refreshed = if (wake == null) {
                    waitMillis(remaining)
                    false
                } else {
                    val timer = scope.async(start = CoroutineStart.UNDISPATCHED) { waitMillis(remaining) }
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
                if (refreshed) return true
                remaining = durationMillis - (elapsedRealtimeMillis() - startedAt).coerceAtLeast(0)
            } while (remaining > 0)
            return false
        } finally {
            if (chain.backoffWake === wake) chain.backoffWake = null
        }
    }
}
