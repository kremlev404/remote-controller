package com.remotecontroller.client.domain.model

/** One thing a viewer does to the PC. Positions are fractions of the shared screen. */
sealed interface RemoteInput {
    data class PointerDown(val x: Float, val y: Float, val button: String) : RemoteInput

    data class PointerMove(val x: Float, val y: Float, val button: String) : RemoteInput

    data class PointerUp(val x: Float, val y: Float, val button: String) : RemoteInput

    data class Scroll(val x: Float, val y: Float, val deltaY: Int) : RemoteInput

    data class Type(val text: String, val backspaces: Int) : RemoteInput

    data object Enter : RemoteInput

    /** One physical key going down or up, from a captured keyboard. */
    data class Key(val code: String, val down: Boolean) : RemoteInput
}
