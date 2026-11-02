# 002: Streaming on macOS and Linux

Goal: the H.264 route from [001](001-windows-screen-streaming.md) on the other two desktops, a choice of stream, and something better than JPEG where H.264 is out of reach.

## What we did

- **macOS:** ScreenCaptureKit for capture, scaling and colour, VideoToolbox for encoding.
- **Linux:** X11 shared memory capture, GStreamer for encoding. Hardware encoders first, x264 and OpenH264 last.
- **GStreamer at run time:** one stable ABI, every vendor through one call, no build per distribution. Calling VA-API directly means writing the bitstream headers by hand and covers no NVIDIA. libavcodec changes its ABI every major version.
- **Tiles, the new route:** native capture, a compare on a 64 pixel grid, JPEG of what changed on every core. It needs nothing but a JPEG decoder, so it also serves iOS and browsers without WebCodecs.
- **Stream menu:** H.264, Tiles or JPEG in the laptop app. Each falls to the next where it cannot run.
- **Windows library:** unchanged. Tiles read the screen through Robot there.

## Bugs this found

- **A frozen picture after a slow start.** The PC silently dropped a keyframe request inside its 500 ms limit, and a viewer that asked never asks twice. The request now waits for the gap. This was on Windows too.
- **No picture without a hardware decoder.** The browser player asked for `prefer-hardware`, which fails outright where there is none. It now states no preference, and the browser still takes the hardware path.

## Measured

Linux, 1080p, Performance mode, Ubuntu 22.04 container with no GPU, Chromium with WebCodecs. A video over two thirds of the screen:

| Measure | H.264 (x264) | Tiles | JPEG |
|---|---|---|---|
| Frames decoded per second | 55–60 | 60 | about 55 |
| Long edge | 1920 px | 1920 px | 1280 px |
| Traffic | about 15 Mbit/s | about 35 Mbit/s | about 24 Mbit/s |

- The library alone holds 60 fps with x264 at 5 ms per frame, and with raw pixels.
- H.264 on a still screen: 9 frames, then none.
- With GStreamer missing, a viewer asking for H.264 moves to tiles on its own and keeps 60 fps.

macOS capture and encoding, and Linux hardware encoders, are not measured yet.
