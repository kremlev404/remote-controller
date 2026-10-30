package com.remotecontroller.client.domain.model

class LocalNetworkAccess(
    val permission: String,
    private val granted: (String) -> Boolean,
) {
    fun isGranted(): Boolean = granted(permission)
}
