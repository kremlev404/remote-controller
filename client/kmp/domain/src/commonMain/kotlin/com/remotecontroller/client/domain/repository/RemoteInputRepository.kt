package com.remotecontroller.client.domain.repository

import com.remotecontroller.client.domain.model.RemoteInput

/** The way this client's input reaches the PC. */
interface RemoteInputRepository {
    /** Returns once the link has taken [input], which is what lets a queue build up behind a slow one. */
    suspend fun send(input: RemoteInput)
}
