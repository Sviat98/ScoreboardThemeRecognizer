package com.bashkevich.scoreboardthemerecognizer.mvi

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

abstract class BaseViewModel<T : UiState, in E : UiEvent, A : UiAction> : ViewModel() {

    abstract val state: Flow<T>

    protected val _action = MutableStateFlow<A?>(null)

    protected fun sendAction(action: A) {
        _action.value = action
    }

    fun consumeAction() {
        _action.value = null
    }
}
interface UiState

interface UiEvent

interface UiAction
