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
class Prefs(context: Context) {
    private val store = context.applicationContext.houcheDataStore
    private val json = BusNetwork.json
    private val cityKey = stringPreferencesKey("city")
    private val deviceKey = stringPreferencesKey("deviceId")
    private val linesKey = stringPreferencesKey("savedLines")
    private val stationsKey = stringPreferencesKey("savedStations")
    private val historyKey = stringPreferencesKey("history")
    private val values = store.data.catch { error -> throw wrap(error) }
    val currentCityKey: Flow<String?> = values.map { it[cityKey] }
    val favoriteLines: Flow<List<SavedLine>> = values.map { decode<List<SavedLine>>(it[linesKey]) }.flowOn(Dispatchers.IO)
    val favoriteStations: Flow<List<SavedStation>> = values.map { decode<List<SavedStation>>(it[stationsKey]) }.flowOn(Dispatchers.IO)
    val recentSearches: Flow<List<String>> = values.map { decode<List<String>>(it[historyKey]).take(10) }.flowOn(Dispatchers.IO)

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
    private inline fun <reified T> decode(value: String?): T = try { json.decodeFromString(value ?: "[]") } catch (error: Exception) { throw wrap(error) }
    private fun stationKey(station: Station) = listOf(station.number ?: station.name, station.lat, station.lng)
    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { try { block() } catch (error: Exception) { throw wrap(error) } }
    private fun wrap(error: Throwable): BusException {
        if (error is CancellationException) throw error
        val result = error as? BusException ?: BusException(BusException.Kind.ARGUMENT, "本地设置读取或保存失败", error)
        Log.e("Houche", result.message, result)
        return result
    }
}
