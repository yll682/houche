package com.yll682.houche.ui.screens.settings

import com.yll682.houche.AppGraph
import com.yll682.houche.BuildConfig
import com.yll682.houche.core.BusException
import com.yll682.houche.core.City
import com.yll682.houche.core.RemoteRelease
import com.yll682.houche.core.UpdateClient
import com.yll682.houche.core.isNewer
import com.yll682.houche.ui.StateViewModel

/** 检查更新的界面状态；release 非空表示已拿到远端版本信息（可能已是最新）。 */
data class UpdateState(
    val checking: Boolean = false,
    val release: RemoteRelease? = null,
    val hasUpdate: Boolean = false,
    val error: String? = null
)

data class SettingsData(
    val city: City,
    val cleared: Boolean = false,
    val versionName: String = BuildConfig.VERSION_NAME,
    val versionCode: Int = BuildConfig.VERSION_CODE,
    val update: UpdateState = UpdateState(),
    /** 地铁在线接口的最近一次探测结论；只在这里露出，主界面不显示。 */
    val metroNote: String = "",
    /** 地铁站序的出处，说明数据来自哪里。 */
    val metroSource: String = ""
)

class SettingsViewModel(private val graph: AppGraph, city: City) : StateViewModel<SettingsData>(SettingsData(city)) {
    private val updates = UpdateClient()

    init {
        action {
            val network = graph.metro.networkFor(city)
            update(
                data.copy(
                    metroNote = graph.metroRemote.last?.note.orEmpty(),
                    metroSource = network?.source.orEmpty()
                )
            )
        }
    }
    fun clearCache() = action {
        update(data.copy(cleared = false))
        graph.repository().clearCache()
        update(data.copy(cleared = true))
        kotlinx.coroutines.delay(4_000)
        update(data.copy(cleared = false))
    }

    /** 失败只落到 state.update.error，界面照常显示，不往上抛。 */
    fun checkUpdate() = action {
        update(data.copy(update = data.update.copy(checking = true, error = null)))
        val release = try {
            updates.latest()
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            update(
                data.copy(
                    update = data.update.copy(
                        checking = false,
                        release = null,
                        hasUpdate = false,
                        error = (error as? BusException)?.message ?: "检查更新失败，请检查网络"
                    )
                )
            )
            return@action
        }
        update(
            data.copy(
                update = UpdateState(
                    checking = false,
                    release = release,
                    hasUpdate = isNewer(release, BuildConfig.VERSION_CODE),
                    error = null
                )
            )
        )
    }
}
