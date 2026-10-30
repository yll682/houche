package com.yll682.houche.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.InputStream

// Android 调用方传入 { context.assets.open("cities.json") }，核心层不依赖 Context。
class Cities(private val openAsset: () -> InputStream) {
    private val mutex = Mutex()
    private var cached: List<City>? = null
    suspend fun all(): List<City> = withContext(Dispatchers.IO) {
        mutex.withLock {
            cached ?: try {
                BusNetwork.json.decodeFromString<List<City>>(openAsset().bufferedReader(Charsets.UTF_8).use { it.readText() }).also { cached = it }
            } catch (error: Exception) { throw failure(error, "城市资料加载失败") }
        }
    }
    suspend fun search(keyword: String): List<City> {
        val text = keyword.trim()
        return all().filter { it.name.contains(text, true) || it.pinyin.contains(text, true) }
    }
    suspend fun grouped(): Map<String, List<City>> = all().groupBy { it.province.ifBlank { "其他" } }
    suspend fun cityFor(key: String): City = all().firstOrNull { it.key == key }
        ?: throw BusException(BusException.Kind.ARGUMENT, "城市编号无效")
    suspend fun defaultCity(previousKey: String? = null, located: City? = null): City =
        all().firstOrNull { it.key == previousKey } ?: located ?: all().first { it.isXiamen }
}
