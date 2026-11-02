package com.remotecontroller.client.domain.usecase

import com.remotecontroller.client.domain.model.RemoteInput
import com.remotecontroller.client.domain.repository.RemoteInputRepository
import com.remotecontroller.shared.model.RemoteKeys
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

private const val LEFT = "LEFT"

private class RecordingInput : RemoteInputRepository {
    val sent = ArrayList<RemoteInput>()

    override suspend fun send(input: RemoteInput) {
        sent += input
    }
}

class SendRemoteInputUseCaseTest {
    private val repository = RecordingInput()
    private val input = SendRemoteInputUseCase(repository)

    /** Everything queued so far arrives as one batch, as it does behind a busy link. */
    private fun deliver(): List<RemoteInput> = runBlocking {
        input.close()
        input.deliver()
        repository.sent
    }

    @Test
    fun movesBehindABusyLinkCollapseIntoTheNewest() {
        repeat(5) { step -> input.pointerMove(step.toFloat(), 0f, LEFT) }

        assertEquals(listOf<RemoteInput>(RemoteInput.PointerMove(4f, 0f, LEFT)), deliver())
    }

    @Test
    fun aClickIsNeverLostAmongMoves() {
        input.pointerMove(1f, 0f, LEFT)
        input.pointerMove(2f, 0f, LEFT)
        input.pointerDown(2f, 0f, LEFT)
        input.pointerMove(3f, 0f, LEFT)
        input.pointerMove(4f, 0f, LEFT)
        input.pointerUp(4f, 0f, LEFT)

        assertEquals(
            listOf(
                RemoteInput.PointerMove(2f, 0f, LEFT),
                RemoteInput.PointerDown(2f, 0f, LEFT),
                RemoteInput.PointerMove(4f, 0f, LEFT),
                RemoteInput.PointerUp(4f, 0f, LEFT),
            ),
            deliver(),
        )
    }

    @Test
    fun releaseLetsGoOfTheHeldButtonWhereThePointerIs() {
        input.pointerDown(1f, 1f, "RIGHT")
        input.pointerMove(5f, 6f, "RIGHT")
        input.release()

        assertEquals(RemoteInput.PointerUp(5f, 6f, "RIGHT"), deliver().last())
    }

    @Test
    fun aSecondReportOfTheSameReleaseSendsNothing() {
        input.pointerDown(1f, 1f, LEFT)
        input.pointerUp(1f, 1f, LEFT)
        input.pointerUp(1f, 1f, LEFT)
        input.release()

        assertEquals(1, deliver().count { it is RemoteInput.PointerUp })
    }

    @Test
    fun releaseWithNothingHeldSendsNothing() {
        input.release()

        assertEquals(emptyList(), deliver())
    }

    @Test
    fun scrollIsLimitedAndAStillWheelSendsNothing() {
        input.scroll(0f, 0f, 0)
        input.scroll(0f, 0f, 500)

        assertEquals(listOf<RemoteInput>(RemoteInput.Scroll(0f, 0f, RemoteKeys.MAX_SCROLL_STEPS)), deliver())
    }

    @Test
    fun keysKeepTheirOrderAroundAClick() {
        input.key("ControlLeft", true)
        input.pointerDown(1f, 1f, LEFT)
        input.pointerUp(1f, 1f, LEFT)
        input.key("ControlLeft", false)

        assertEquals(
            listOf(
                RemoteInput.Key("ControlLeft", true),
                RemoteInput.PointerDown(1f, 1f, LEFT),
                RemoteInput.PointerUp(1f, 1f, LEFT),
                RemoteInput.Key("ControlLeft", false),
            ),
            deliver(),
        )
    }
}
