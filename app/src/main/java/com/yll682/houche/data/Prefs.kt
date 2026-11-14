package com.yll682.houche.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.yll682.houche.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import java.util.UUID
import android.util.Log

private val Context.houcheDataStore by preferencesDataStore(name = "houche")
data class SearchRemoval(val before: List<String>, val removed: Set<String>)

class Prefs(context: Context) {
    private val store = context.applicationContext.houcheDataStore
    private val json = BusNetwork.json
    private val cityKey = stringPreferencesKey("city")
    private val deviceKey = stringPreferencesKey("deviceId")
    private val linesKey = stringPreferencesKey("savedLines")
    private val stationsKey = stringPreferencesKey("savedStations")
    private val historyKey = stringPreferencesKey("history")
    private val trackedVehicleKey = stringPreferencesKey("trackedVehicle")
    private val vehicleAlightKey = stringPreferencesKey("vehicleAlightSelection")
    private val values = store.data.catch { error -> throw wrap(error) }
    val currentCityKey: Flow<String?> = values.map { it[cityKey] }
    val favoriteLines: Flow<List<SavedLine>> = values.map { decode<List<SavedLine>>(it[linesKey]) }.flowOn(Dispatchers.IO)
    val favoriteStations: Flow<List<SavedStation>> = values.map { decode<List<SavedStation>>(it[stationsKey]) }.flowOn(Dispatchers.IO)
    val recentSearches: Flow<List<String>> = values.map { decode<List<String>>(it[historyKey]).take(10) }.flowOn(Dispatchers.IO)
    val trackedVehicle: Flow<TrackedVehicle?> = values.map { decodeOptional<TrackedVehicle>(it[trackedVehicleKey]) }.flowOn(Dispatchers.IO)
    val vehicleAlightSelection: Flow<VehicleAlightSelection?> = values.map { decodeOptional<VehicleAlightSelection>(it[vehicleAlightKey]) }.flowOn(Dispatchers.IO)

    init {
        // 应用重新进入前台时恢复上次目标；普通 Prefs/Robolectric Application 不注册恢复入口。
        VehicleTracker.initialize(context.applicationContext)
    }

    suspend fun setTrackedVehicle(value: TrackedVehicle?) = io {
        store.edit { prefs ->
            if (value == null) prefs.remove(trackedVehicleKey)
            else prefs[trackedVehicleKey] = json.encodeToString(value)
        }
        Unit
    }

    /** 在同一个 DataStore 事务中认领提醒；已切站或关闭的旧请求不能覆写新目标。 */
    suspend fun compareAndSetTrackedVehicle(expected: TrackedVehicle?, value: TrackedVehicle?): Boolean = io {
        var changed = false
        store.edit { prefs ->
            if (decodeOptional<TrackedVehicle>(prefs[trackedVehicleKey]) == expected) {
                if (value == null) prefs.remove(trackedVehicleKey)
                else prefs[trackedVehicleKey] = json.encodeToString(value)
                changed = true
            }
        }
        changed
    }

    suspend fun setVehicleAlightSelection(value: VehicleAlightSelection?) = io {
        store.edit { prefs ->
            if (value == null) prefs.remove(vehicleAlightKey)
            else prefs[vehicleAlightKey] = json.encodeToString(value)
        }
        Unit
    }

    suspend fun deviceId(): String = io {
        // edit 是串行事务；并发首次调用也只会生成并持久化一个编号。
        val result = store.edit { if (it[deviceKey] == null) it[deviceKey] = UUID.randomUUID().toString().replace("-", "") }
        result[deviceKey]!!
    }
    suspend fun setCurrentCity(key: String) = io { store.edit { it[cityKey] = key }; Unit }
    suspend fun toggleLine(value: SavedLine) = io {
        store.edit { prefs ->
            val current = decode<List<SavedLine>>(prefs[linesKey])
            val matches: (SavedLine) -> Boolean = { it.city.key == value.city.key && it.line.name == value.line.name && it.line.direction == value.line.direction }
            prefs[linesKey] = json.encodeToString(if (current.any(matches)) current.filterNot(matches) else current + value)
        }; Unit
    }
    suspend fun toggleStation(value: SavedStation) = io {
        store.edit { prefs ->
            val current = decode<List<SavedStation>>(prefs[stationsKey])
            val matches: (SavedStation) -> Boolean = { it.city.key == value.city.key && stationKey(it.station) == stationKey(value.station) }
            prefs[stationsKey] = json.encodeToString(if (current.any(matches)) current.filterNot(matches) else current + value)
        }; Unit
    }
    suspend fun addSearch(keyword: String) = io {
        val value = keyword.trim()
        if (value.isNotEmpty()) store.edit { prefs -> prefs[historyKey] = json.encodeToString((listOf(value) + decode<List<String>>(prefs[historyKey]).filterNot { it == value }).take(10)) }
        Unit
    }
    suspend fun removeSearch(keyword: String) = io { store.edit { it[historyKey] = json.encodeToString(decode<List<String>>(it[historyKey]).filterNot { text -> text == keyword }) }; Unit }
    suspend fun clearSearches() = io { store.edit { it.remove(historyKey) }; Unit }
    suspend fun removeSearchesWithSnapshot(keyword: String? = null): SearchRemoval = io {
        var snapshot = SearchRemoval(emptyList(), emptySet())
        store.edit { prefs ->
            val before = decode<List<String>>(prefs[historyKey])
            val removed = before.filter { keyword == null || it == keyword }.toSet()
            snapshot = SearchRemoval(before, removed)
            prefs[historyKey] = json.encodeToString(before.filterNot { it in removed })
        }
        snapshot
    }
    suspend fun restoreSearches(snapshot: SearchRemoval) = io {
        store.edit { prefs ->
            val current = decode<List<String>>(prefs[historyKey])
            val additions = current.filterNot { it in snapshot.before }
            val restored = snapshot.before.filter { it in current || it in snapshot.removed }
            prefs[historyKey] = json.encodeToString((additions + restored).distinct().take(10))
        }; Unit
    }
    private inline fun <reified T> decode(value: String?): T = try { json.decodeFromString(value ?: "[]") } catch (error: Exception) { throw wrap(error) }
    private inline fun <reified T> decodeOptional(value: String?): T? =
        value?.let { try { json.decodeFromString<T>(it) } catch (error: Exception) { throw wrap(error) } }
    private fun stationKey(station: Station) = listOf(station.number ?: station.name, station.lat, station.lng)
    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { try { block() } catch (error: Exception) { throw wrap(error) } }
    private fun wrap(error: Throwable): BusException {
        if (error is CancellationException) throw error
        val result = error as? BusException ?: BusException(BusException.Kind.ARGUMENT, "本地设置读取或保存失败", error)
        Log.e("Houche", result.message, result)
        return result
    }
}
