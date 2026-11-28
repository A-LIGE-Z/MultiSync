package dev.multisync.app.ui

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.multisync.app.data.LOCAL_NETWORK_PERMISSION
import dev.multisync.app.data.needsLocalNetwork

@Composable
internal fun ObserveLocalNetworkAccess(model: SyncViewModel) {
    if (Build.VERSION.SDK_INT < 37) return
    val settings by model.settings.collectAsStateWithLifecycle()
    val granted by model.localNetworkAccess.collectAsStateWithLifecycle()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    var requested by rememberSaveable { mutableStateOf(false) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { model.refreshLocalNetworkAccess() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { model.refreshLocalNetworkAccess() }
    LaunchedEffect(needsLocalNetwork(settings), granted, lifecycle) {
        if (needsLocalNetwork(settings) && !granted && !requested && lifecycle.isAtLeast(Lifecycle.State.RESUMED)) {
            requested = true
            request.launch(LOCAL_NETWORK_PERMISSION)
        }
    }
}
