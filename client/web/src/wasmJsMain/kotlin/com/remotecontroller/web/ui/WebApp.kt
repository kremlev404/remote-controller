package com.remotecontroller.web.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.remotecontroller.client.core.RemoteControllerTheme
import com.remotecontroller.shared.network.RelayProtocol
import com.remotecontroller.web.data.PageTarget

@Composable
fun WebApp() {
    RemoteControllerTheme(darkTheme = isSystemInDarkTheme()) {
        var target by remember { mutableStateOf(PageTarget.fromPage()) }
        if (target.relay && target.deviceId.length != RelayProtocol.ID_DIGITS) {
            DeviceGate(onContinue = { id, code -> target = target.copy(deviceId = id, code = code) })
        } else {
            PageScreen(viewModel { pageViewModel(target) })
        }
    }
}

@Composable
private fun PageScreen(viewModel: PageViewModel) {
    val state by viewModel.uiState.collectAsState()
    when (val link = state.link) {
        is LinkState.NeedsCode -> AccessGate(message = link.message, onContinue = viewModel::submitCode)
        is LinkState.Waiting -> WaitingGate(link.checkCode)
        is LinkState.Stopped -> StoppedGate(link.message, onRetry = viewModel::retry)
        LinkState.Connecting, LinkState.Live -> LinkedPanes(state, viewModel)
    }
}

@Composable
private fun LinkedPanes(state: PageUiState, viewModel: PageViewModel) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .pointerInput(viewModel) {
                // Last to see a press: only one the picture left alone gives the keyboard back.
                // Giving it back on every click would lift Ctrl or Shift in the middle of Ctrl + click.
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Final)
                        val outside = event.changes.none { it.isConsumed }
                        if (event.type == PointerEventType.Press && outside) viewModel.releaseKeyboard()
                    }
                }
            },
    ) {
        if (maxWidth >= WIDE_PAGE) {
            Row(modifier = Modifier.fillMaxSize()) {
                StreamPane(state, viewModel, Modifier.weight(1f).fillMaxHeight())
                if (!state.fullscreen) ChatPane(state.clip, viewModel, Modifier.width(CHAT_WIDTH).fillMaxHeight())
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                StreamPane(state, viewModel, Modifier.weight(1f).fillMaxWidth())
                if (!state.fullscreen) {
                    ChatPane(state.clip, viewModel, Modifier.fillMaxWidth().heightIn(min = 220.dp, max = 320.dp))
                }
            }
        }
    }
}

private val WIDE_PAGE = 840.dp
private val CHAT_WIDTH = 360.dp
