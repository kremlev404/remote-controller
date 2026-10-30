package com.remotecontroller.client.domain.repository

import com.remotecontroller.shared.network.LinkGrant

/** What each PC handed this device, so it is not asked again. */
interface DeviceGrantRepository {
    suspend fun grantFor(pc: String): LinkGrant?

    suspend fun remember(pc: String, grant: LinkGrant)

    suspend fun forget(pc: String)
}
