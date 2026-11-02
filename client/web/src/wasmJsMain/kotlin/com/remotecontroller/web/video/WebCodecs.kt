@file:OptIn(ExperimentalWasmJsInterop::class)

package com.remotecontroller.web.video

import org.khronos.webgl.Uint8Array
import org.w3c.dom.CanvasImageSource
import org.w3c.dom.ImageBitmap
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.Promise
import kotlin.js.js

// The browser library stops short of WebCodecs, so what the player uses of it is declared here.

external class VideoDecoder(init: JsAny) : JsAny {
    val state: String
    val decodeQueueSize: Int

    fun configure(config: JsAny)

    fun decode(chunk: EncodedVideoChunk)

    fun close()
}

external class EncodedVideoChunk(init: JsAny) : JsAny

external interface VideoFrame : CanvasImageSource, JsAny {
    val displayWidth: Int
    val displayHeight: Int

    fun close()
}

/** WebCodecs needs a secure page: true on https, and in every browser of the last few years. Tiles do not. */
fun canPlayVideo(): Boolean = js("typeof VideoDecoder === 'function' && window.isSecureContext === true")

internal fun decoderCallbacks(output: (VideoFrame) -> Unit, error: (JsAny?) -> Unit): JsAny =
    js("({ output: output, error: error })")

// The browser still takes the hardware path when it has one. Asking for it outright fails where it has none.
internal fun decoderConfig(codec: String): JsAny = js("({ codec: codec, optimizeForLatency: true })")

internal fun videoChunk(type: String, timestamp: Double, data: Uint8Array): JsAny =
    js("({ type: type, timestamp: timestamp, data: data })")

internal fun opaqueCanvas(): JsAny = js("({ alpha: false })")

internal fun decodeJpeg(bytes: Uint8Array): Promise<ImageBitmap> =
    js("createImageBitmap(new Blob([bytes], { type: 'image/jpeg' }))")
