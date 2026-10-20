package com.remotecontroller.client.data.repository

import com.remotecontroller.client.data.remote.ScreenStreamClient
import com.remotecontroller.client.domain.model.PcConnection
import com.remotecontroller.client.domain.repository.ScreenRepository
import com.remotecontroller.shared.network.ScreenFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate

class ScreenRepositoryImpl(
    private val client: ScreenStreamClient,
) : ScreenRepository {
    override fun watch(connection: PcConnection): Flow<ScreenFrame.Picture> =
        client.frames(connection.host.value, connection.port.value).conflate()
}
