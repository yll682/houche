package com.yll682.houche.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yll682.houche.core.BusException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

sealed interface UiState<out T> {
    val data: T?
    data class Loading<T>(override val data: T? = null) : UiState<T>
    data class Success<T>(override val data: T) : UiState<T>
    data class Empty<T>(override val data: T) : UiState<T>
    data class Error<T>(val message: String?, override val data: T? = null) : UiState<T>
}

abstract class StateViewModel<T>(initial: T) : ViewModel() {
    protected val mutable = MutableStateFlow<UiState<T>>(UiState.Success(initial))
    val state: StateFlow<UiState<T>> = mutable.asStateFlow()
    protected val data: T get() = checkNotNull(mutable.value.data)
    protected fun update(value: T, empty: Boolean = false) { mutable.value = if (empty) UiState.Empty(value) else UiState.Success(value) }
    protected fun report(error: Exception) {
        if (error is CancellationException) throw error
        Log.e("Houche", "界面操作失败", error)
        mutable.value = UiState.Error((error as? BusException)?.message, mutable.value.data)
    }
    protected suspend fun attempt(block: suspend () -> Unit) {
        try { block() } catch (e: Exception) { report(e) }
    }
    protected fun action(block: suspend () -> Unit) = viewModelScope.launch { attempt(block) }
}
