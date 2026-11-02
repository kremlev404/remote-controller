package com.remotecontroller.client.domain.usecase

import com.remotecontroller.client.domain.model.PcConnection
import com.remotecontroller.client.domain.repository.ScreenRepository
import com.remotecontroller.shared.network.ScreenFeed
import kotlinx.coroutines.flow.Flow

class ObserveScreenUseCase(
    private val repository: ScreenRepository,
) {
    operator fun invoke(
        connection: PcConnection,
        codecs: List<String>,
        keyframeRequests: Flow<Unit>,
    ): Flow<ScreenFeed> = repository.watch(connection, codecs, keyframeRequests)
}
