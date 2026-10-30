package com.yll682.houche.data

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.util.Log
import com.yll682.houche.core.BusException
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.*

// 上游统一使用经纬度字段，坐标系明确标注，避免二次偏移。
data class Position(val lat: Double, val lng: Double, val accuracy: Float? = null, val coordinateSystem: String = "GCJ02", val fromLastKnown: Boolean = false)
class LocationProvider(context: Context) {
    private val manager = context.applicationContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    // 权限由 UI 在用户点击后申请；此处只报告权限不足，不弹窗。
    @SuppressLint("MissingPermission")
    suspend fun locate(): Position = withContext(Dispatchers.IO) {
        try {
            val providers = listOf("fused", LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .filter { it in manager.allProviders && manager.isProviderEnabled(it) }
            if (providers.isEmpty()) throw BusException(BusException.Kind.ARGUMENT, "请开启系统定位服务")
            val last = providers.mapNotNull { manager.getLastKnownLocation(it) }.maxByOrNull { it.elapsedRealtimeNanos }
            val fresh = withTimeoutOrNull(15_000) { awaitUpdate(if ("fused" in providers) listOf("fused") else providers) }
            val location = fresh ?: last ?: throw BusException(BusException.Kind.NETWORK, "定位超时，请重试或手动选择城市")
            wgs84ToGcj02(location.latitude, location.longitude).copy(accuracy = location.accuracy, fromLastKnown = fresh == null)
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            val wrapped = error as? BusException ?: BusException(BusException.Kind.ARGUMENT,
                if (error is SecurityException) "请先允许定位权限" else "定位失败，请检查系统定位设置", error)
            Log.e("Houche", wrapped.message, wrapped)
            throw wrapped
        }
    }
    @SuppressLint("MissingPermission")
    private suspend fun awaitUpdate(providers: List<String>): Location = withContext(Dispatchers.Main.immediate) {
        suspendCancellableCoroutine { continuation ->
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    if (continuation.isActive) {
                        manager.removeUpdates(this)
                        continuation.resume(location)
                    }
                }
                override fun onProviderEnabled(provider: String) = Unit
                override fun onProviderDisabled(provider: String) = Unit
                @Deprecated("旧版系统回调")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            }
            continuation.invokeOnCancellation { manager.removeUpdates(listener) }
            try { providers.forEach { manager.requestLocationUpdates(it, 0L, 0f, listener, Looper.getMainLooper()) } }
            catch (error: Exception) {
                manager.removeUpdates(listener)
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        }
    }
}

// 与 coordtransform 的 wgs84togcj02 算法一致；中国境外保持原坐标。
fun outOfChina(lat: Double, lng: Double): Boolean = lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271
fun wgs84ToGcj02(lat: Double, lng: Double): Position {
    if (!lat.isFinite() || !lng.isFinite() || lat !in -90.0..90.0 || lng !in -180.0..180.0)
        throw BusException(BusException.Kind.ARGUMENT, "请输入有效经纬度")
    if (outOfChina(lat, lng)) return Position(lat, lng)
    val x = lng - 105.0
    val y = lat - 35.0
    var dlat = -100.0 + 2*x + 3*y + 0.2*y*y + 0.1*x*y + 0.2*sqrt(abs(x))
    dlat += (20*sin(6*x*PI) + 20*sin(2*x*PI))*2/3
    dlat += (20*sin(y*PI) + 40*sin(y/3*PI))*2/3
    dlat += (160*sin(y/12*PI) + 320*sin(y*PI/30))*2/3
    var dlng = 300.0 + x + 2*y + 0.1*x*x + 0.1*x*y + 0.1*sqrt(abs(x))
    dlng += (20*sin(6*x*PI) + 20*sin(2*x*PI))*2/3
    dlng += (20*sin(x*PI) + 40*sin(x/3*PI))*2/3
    dlng += (150*sin(x/12*PI) + 300*sin(x/30*PI))*2/3
    val rad = lat/180*PI
    val magic = 1 - 0.00669342162296594323*sin(rad)*sin(rad)
    val root = sqrt(magic)
    dlat = dlat*180 / ((6378245.0*(1-0.00669342162296594323))/(magic*root)*PI)
    dlng = dlng*180 / (6378245.0/root*cos(rad)*PI)
    return Position(lat+dlat, lng+dlng)
}
