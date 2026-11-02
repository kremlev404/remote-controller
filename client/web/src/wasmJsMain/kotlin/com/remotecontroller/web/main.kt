package com.remotecontroller.web

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import com.remotecontroller.web.ui.WebApp

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport("composeApp") {
        WebApp()
    }
}
