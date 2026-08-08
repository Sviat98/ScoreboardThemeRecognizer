package com.bashkevich.scoreboardthemerecognizer.mvi

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
fun <A : UiAction> LaunchedUiEffectHandler(
    effect: A?,
    onDismissSnackbar: () -> Unit,
    onConsume: () -> Unit,
    handler: suspend (A) -> Unit,
    ) {
    LaunchedEffect(effect) {
        val currentEffect = effect ?: return@LaunchedEffect
        handler(currentEffect)
        onConsume()
    }

    val currentEffectRef = rememberUpdatedState(effect)
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE && currentEffectRef.value != null) {
                onDismissSnackbar()
                onConsume()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
}
