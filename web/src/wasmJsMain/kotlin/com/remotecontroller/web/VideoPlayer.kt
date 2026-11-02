@file:OptIn(ExperimentalWasmJsInterop::class)

package com.remotecontroller.web

import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.js

external interface VideoPlayer : JsAny {
    fun decode(base64: String, keyframe: Boolean)
    fun paintTiles(base64: String)
    fun place(left: Double, top: Double, width: Double, height: Double)
    fun reset()
    fun close()
}

/** WebCodecs needs a secure page: true on https, and in every browser of the last few years. Tiles do not. */
fun canPlayVideo(): Boolean = js("typeof VideoDecoder === 'function' && window.isSecureContext === true")

/**
 * Plays H.264 with the browser's decoder, onto a canvas behind the Compose page. The page
 * leaves a clear hole over it, so its buttons stay on top and clicks still reach Compose.
 * Only the newest frame is painted, once per display refresh. [onLost] asks for a new keyframe:
 * frames depend on each other, so after a gap nothing can be shown until one arrives.
 * A tile stream paints onto the same canvas: each packet's parts land together, in order.
 */
fun openVideoPlayer(
    onShown: (Int, Int) -> Unit,
    onLost: () -> Unit,
): VideoPlayer = js(
    """
    (() => {
      const MAX_QUEUE = 8;
      const FRAME_MICROS = 16667;
      const MAX_TILE_PACKETS = 8;
      const TILES_START = 12;
      const TILE_HEADER = 12;
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
      let awaitingTiles = true;
      let tilePackets = 0;
      let tileEpoch = 0;
      let tileTail = Promise.resolve();

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
              // The browser still takes the hardware path when it has one. Asking for it outright fails where it has none.
              decoder.configure({ codec: wanted, optimizeForLatency: true });
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
        paintTiles: (base64) => {
          const bytes = bytesOf(base64);
          const keyframe = (bytes[1] & 1) !== 0;
          if (awaitingTiles && !keyframe) return;
          if (tilePackets >= MAX_TILE_PACKETS) {
            awaitingTiles = true;
            onLost();
            return;
          }
          awaitingTiles = false;
          tilePackets++;
          const view = new DataView(bytes.buffer);
          const width = view.getUint16(2);
          const height = view.getUint16(4);
          const parts = [];
          let offset = TILES_START;
          for (let i = view.getUint16(10); i > 0; i--) {
            const x = view.getUint16(offset);
            const y = view.getUint16(offset + 2);
            const size = view.getUint32(offset + 8);
            const start = offset + TILE_HEADER;
            const blob = new Blob([bytes.subarray(start, start + size)], { type: 'image/jpeg' });
            parts.push(createImageBitmap(blob).then((image) => ({ x, y, image })));
            offset = start + size;
          }
          const epoch = tileEpoch;
          tileTail = tileTail.then(() => Promise.all(parts)).then((images) => {
            tilePackets--;
            if (epoch === tileEpoch) {
              if (canvas.width !== width) canvas.width = width;
              if (canvas.height !== height) canvas.height = height;
              for (const part of images) paper.drawImage(part.image, part.x, part.y);
            }
            for (const part of images) part.image.close();
            if (epoch === tileEpoch) onShown(width, height);
          }).catch(() => {
            tilePackets--;
            awaitingTiles = true;
            onLost();
          });
        },
        place: (left, top, width, height) => {
          canvas.style.left = left + 'px';
          canvas.style.top = top + 'px';
          canvas.style.width = width + 'px';
          canvas.style.height = height + 'px';
        },
        reset: () => {
          awaitingKey = true;
          awaitingTiles = true;
          tileEpoch++;
          if (decoder && decoder.state !== 'closed') decoder.close();
          decoder = null;
          codec = '';
        },
        close: () => {
          tileEpoch++;
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
