package com.remotecontroller.client.data.log

import android.util.Log

internal actual fun logInfo(tag: String, message: String) {
    Log.i(tag, message)
}

internal actual fun logWarn(tag: String, message: String, error: Throwable?) {
    if (error == null) Log.w(tag, message) else Log.w(tag, message, error)
}
