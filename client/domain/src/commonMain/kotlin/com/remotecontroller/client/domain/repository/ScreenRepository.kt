package com.remotecontroller.client.domain.repository

import com.remotecontroller.client.domain.model.PcConnection
import com.remotecontroller.shared.network.ScreenFrame
import kotlinx.coroutines.flow.Flow

interface ScreenRepository {
    fun watch(connection: PcConnection): Flow<ScreenFrame.Picture>
}
