@file:OptIn(ExperimentalWasmJsInterop::class)

package com.remotecontroller.web

import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.js

external interface VideoPlayer : JsAny {
    fun decode(base64: String, keyframe: Boolean)
    fun place(left: Double, top: Double, width: Double, height: Double)
    fun reset()
    fun close()
}

/** WebCodecs needs a secure page: true on https, and in every browser of the last few years. */
fun canPlayVideo(): Boolean = js("typeof VideoDecoder === 'function' && window.isSecureContext === true")

/**
 * Plays H.264 with the browser's hardware decoder, onto a canvas behind the Compose page. The page
 * leaves a clear hole over it, so its buttons stay on top and clicks still reach Compose.
 * Only the newest frame is painted, once per display refresh. [onLost] asks for a new keyframe:
 * frames depend on each other, so after a gap nothing can be shown until one arrives.
 */
fun openVideoPlayer(
    onShown: (Int, Int) -> Unit,
    onLost: () -> Unit,
): VideoPlayer = js(
    """
    (() => {
      const MAX_QUEUE = 8;
      const FRAME_MICROS = 16667;
      const canvas = document.createElement('canvas');
      canvas.style.cssText = 'position:fixed;left:0;top:0;width:0;height:0;z-index:0;pointer-events:none';
      document.body.appendChild(canvas);
      const paper = canvas.getContext('2d', { alpha: false });
      let decoder = null;
      let codec = '';
      let awaitingKey = true;
      let timestamp = 0;
      let latest = null;
      let painting = false;

      const paint = () => {
        painting = false;
        const frame = latest;
        latest = null;
        if (!frame) return;
        if (canvas.width !== frame.displayWidth) canvas.width = frame.displayWidth;
        if (canvas.height !== frame.displayHeight) canvas.height = frame.displayHeight;
        paper.drawImage(frame, 0, 0);
        const width = frame.displayWidth;
        const height = frame.displayHeight;
        frame.close();
        onShown(width, height);
      };

      const lose = () => {
        awaitingKey = true;
        onLost();
      };

      const open = () => new VideoDecoder({
        output: (frame) => {
          if (latest) latest.close();
          latest = frame;
          if (!painting) {
            painting = true;
            requestAnimationFrame(paint);
          }
        },
        error: () => {
          decoder = null;
          codec = '';
          lose();
        },
      });

      // The sequence header names the profile and level, in the form WebCodecs asks for.
      const codecOf = (bytes) => {
        for (let i = 0; i + 7 < bytes.length; i++) {
          if (bytes[i] === 0 && bytes[i + 1] === 0 && bytes[i + 2] === 1 && (bytes[i + 3] & 31) === 7) {
            const hex = (value) => value.toString(16).padStart(2, '0');
            return 'avc1.' + hex(bytes[i + 4]) + hex(bytes[i + 5]) + hex(bytes[i + 6]);
          }
        }
        return '';
      };

      const bytesOf = (base64) => {
        const binary = atob(base64);
        const bytes = new Uint8Array(binary.length);
        for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
        return bytes;
      };

      return {
        decode: (base64, keyframe) => {
          if (awaitingKey && !keyframe) return;
          const bytes = bytesOf(base64);
          if (keyframe) {
            const wanted = codecOf(bytes);
            if (wanted && wanted !== codec) {
              if (!decoder) decoder = open();
              decoder.configure({ codec: wanted, optimizeForLatency: true, hardwareAcceleration: 'prefer-hardware' });
              codec = wanted;
            }
            awaitingKey = false;
          }
          if (!decoder || !codec) return;
          if (decoder.decodeQueueSize > MAX_QUEUE) {
            lose();
            return;
          }
          timestamp += FRAME_MICROS;
          decoder.decode(new EncodedVideoChunk({ type: keyframe ? 'key' : 'delta', timestamp: timestamp, data: bytes }));
        },
        place: (left, top, width, height) => {
          canvas.style.left = left + 'px';
          canvas.style.top = top + 'px';
          canvas.style.width = width + 'px';
          canvas.style.height = height + 'px';
        },
        reset: () => {
          awaitingKey = true;
          if (decoder && decoder.state !== 'closed') decoder.close();
          decoder = null;
          codec = '';
        },
        close: () => {
          if (decoder && decoder.state !== 'closed') decoder.close();
          if (latest) latest.close();
          decoder = null;
          latest = null;
          canvas.remove();
        },
      };
    })()
    """,
)
