package com.remotecontroller.client.feature.connect.impl

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
internal actual fun rememberLocalNetworkPermissionController(
    permission: String,
    isGranted: () -> Boolean,
    onResult: (granted: Boolean, thenConnect: Boolean) -> Unit,
): LocalNetworkPermissionController {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var askedForPermission by remember { mutableStateOf(false) }
    var requestInFlight by remember { mutableStateOf(false) }
    var connectAfterGrant by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        requestInFlight = false
        val thenConnect = connectAfterGrant
        connectAfterGrant = false
        onResult(granted, thenConnect)
    }

    DisposableEffect(lifecycleOwner, isGranted, onResult) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && isGranted()) {
                onResult(true, false)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    return LocalNetworkPermissionController { thenConnect ->
        if (isGranted()) {
            onResult(true, thenConnect)
            return@LocalNetworkPermissionController
        }
        if (thenConnect) connectAfterGrant = true
        if (requestInFlight) return@LocalNetworkPermissionController
        val activity = context.findActivity()
        val permanentlyDenied = askedForPermission &&
            activity != null &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
        if (permanentlyDenied) {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.fromParts("package", context.packageName, null)),
            )
            return@LocalNetworkPermissionController
        }
        askedForPermission = true
        requestInFlight = true
        permissionLauncher.launch(permission)
    }
}

internal actual fun localNetworkPermissionHint(): String =
    "Android blocks Wi-Fi access to the PC until you allow local network. A VPN is not required."

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
