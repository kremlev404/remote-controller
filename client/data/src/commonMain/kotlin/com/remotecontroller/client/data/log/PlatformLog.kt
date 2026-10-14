package com.remotecontroller.client.data.log

internal expect fun logInfo(tag: String, message: String)

internal expect fun logWarn(tag: String, message: String, error: Throwable? = null)
