package com.remotecontroller.client.feature.screen.impl

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.remotecontroller.shared.network.ScreenFrame

/**
 * MediaCodec on its own thread, drawing onto [surface]. A decoded frame is released to the display
 * at once: this is a live picture, so nothing waits for a presentation time.
 *
 * Every field below is touched on the handler thread only.
 */
internal class H264Decoder(
    private val surface: Surface,
    private val onRate: (Int) -> Unit,
    private val onLost: () -> Unit,
) {
    private val thread = HandlerThread("screen-decode").apply { start() }
    private val handler = Handler(thread.looper)
    private val waiting = ArrayDeque<ScreenFrame.Video>()
    private val freeInputs = ArrayDeque<Int>()
    private var codec: MediaCodec? = null
    private var width = 0
    private var height = 0
    private var nextSequence = -1
    private var awaitingKeyframe = true
    private var presentationMicros = 0L
    private var shownInWindow = 0

    private val reportRate = object : Runnable {
        override fun run() {
            onRate(shownInWindow)
            shownInWindow = 0
            handler.postDelayed(this, RATE_WINDOW_MS)
        }
    }

    private val callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            if (codec !== this@H264Decoder.codec) return
            freeInputs.addLast(index)
            pump()
        }

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            if (codec !== this@H264Decoder.codec) return
            codec.releaseOutputBuffer(index, true)
            shownInWindow++
        }

        override fun onError(codec: MediaCodec, error: MediaCodec.CodecException) {
            if (codec !== this@H264Decoder.codec) return
            stopCodec()
            lose()
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) = Unit
    }

    init {
        handler.postDelayed(reportRate, RATE_WINDOW_MS)
    }

    fun submit(frame: ScreenFrame.Video) {
        handler.post { accept(frame) }
    }

    fun release() {
        handler.post {
            handler.removeCallbacksAndMessages(null)
            stopCodec()
            thread.quitSafely()
        }
    }

    private fun accept(frame: ScreenFrame.Video) {
        // A gap means a frame was dropped on this side. The ones after it would decode to garbage.
        if (!frame.keyframe && frame.sequence != nextSequence) {
            if (!awaitingKeyframe) lose()
            return
        }
        nextSequence = (frame.sequence + 1) and Int.MAX_VALUE
        if (frame.keyframe) {
            awaitingKeyframe = false
            if (codec == null || frame.width != width || frame.height != height) startCodec(frame.width, frame.height)
        }
        if (awaitingKeyframe || codec == null) return
        if (waiting.size >= MAX_WAITING) {
            waiting.clear()
            lose()
            return
        }
        waiting.addLast(frame)
        pump()
    }

    private fun pump() {
        val codec = codec ?: return
        while (waiting.isNotEmpty() && freeInputs.isNotEmpty()) {
            val frame = waiting.removeFirst()
            val index = freeInputs.removeFirst()
            val input = codec.getInputBuffer(index) ?: continue
            if (frame.data.size > input.capacity()) {
                lose()
                return
            }
            input.clear()
            input.put(frame.data)
            presentationMicros += FRAME_MICROS
            val flags = if (frame.keyframe) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            codec.queueInputBuffer(index, 0, frame.data.size, presentationMicros, flags)
        }
    }

    private fun startCodec(frameWidth: Int, frameHeight: Int) {
        stopCodec()
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, frameWidth, frameHeight).apply {
            setInteger(MediaFormat.KEY_PRIORITY, REALTIME_PRIORITY)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            // Qualcomm chips hold frames back on phones older than Android 11 without this.
            setInteger(QUALCOMM_LOW_LATENCY, 1)
        }
        val started = runCatching {
            MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).also { decoder ->
                codec = decoder
                decoder.setCallback(callback, handler)
                decoder.configure(format, surface, null, 0)
                decoder.start()
            }
        }
        if (started.isFailure) {
            stopCodec()
            lose()
            return
        }
        width = frameWidth
        height = frameHeight
    }

    private fun stopCodec() {
        val current = codec ?: return
        codec = null
        runCatching { current.stop() }
        runCatching { current.release() }
        freeInputs.clear()
        waiting.clear()
    }

    private fun lose() {
        awaitingKeyframe = true
        onLost()
    }

    private companion object {
        const val MAX_WAITING = 12
        const val RATE_WINDOW_MS = 1_000L
        const val FRAME_MICROS = 16_667L
        const val REALTIME_PRIORITY = 0
        const val QUALCOMM_LOW_LATENCY = "vendor.qti-ext-dec-low-latency.enable"
    }
}
