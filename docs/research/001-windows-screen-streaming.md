# 001: Windows screen streaming

Goal: 60 fps at 1080p, light on the PC.

## Before

- `Robot` capture → Java scaling → JPEG on the CPU → `wss`.
- 19 fps at 720p, 61% of one CPU core.
- `Robot` alone took 20.8 ms per frame, more than the 16.7 ms that 60 fps allows.

## What we did

- **Capture:** DXGI Desktop Duplication instead of `Robot`.
- **Scaling and colour:** D3D11 video processor on the GPU.
- **Encoding:** hardware H.264 through Media Foundation, in `screencast.dll` (`:host:screencast`).
- **Transport:** kept `wss` and Link v2.
- **Playback:** `MediaCodec` on Android, WebCodecs in the browser.
- **Still screen:** no changes, no frames.
- **Fallback:** JPEG for clients and PCs without H.264. The server window shows the codec in use.

## After

| Measure | Before | After | Change |
|---|---|---|---|
| Frame rate | 19 fps | 60 fps | +216% |
| Resolution | 720p | 1080p | +125% pixels |
| Pixels per second | 17.5 million | 124.4 million | +611% |
| CPU, one core | 61% | 17% | −72% |
| CPU time per frame | 32.1 ms | 2.8 ms | −91% |
| CPU on a still screen | not measured | under 1%, 0 frames | |

iOS and macOS and Linux PCs stay on JPEG.
