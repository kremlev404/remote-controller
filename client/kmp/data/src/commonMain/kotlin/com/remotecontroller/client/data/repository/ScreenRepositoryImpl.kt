package com.remotecontroller.client.data.repository

import com.remotecontroller.client.data.remote.ScreenStreamClient
import com.remotecontroller.client.domain.model.PcConnection
import com.remotecontroller.client.domain.repository.ScreenRepository
import com.remotecontroller.shared.network.ScreenFeed
import kotlinx.coroutines.flow.Flow

class ScreenRepositoryImpl(
    private val client: ScreenStreamClient,
) : ScreenRepository {
    override fun watch(
        connection: PcConnection,
        codecs: List<String>,
        keyframeRequests: Flow<Unit>,
    ): Flow<ScreenFeed> = client.frames(
        host = connection.host.value,
        port = connection.port.value,
        accessCode = connection.accessCode,
        codecs = codecs,
        keyframeRequests = keyframeRequests,
    )
}
