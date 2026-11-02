# Screen streaming

The picture is H.264 on Windows, macOS and Linux. Nothing heavy runs on the CPU where the PC has a hardware encoder, and each OS uses its own tools:

| Step | Windows | macOS | Linux (X11) |
|---|---|---|---|
| Capture | DXGI Desktop Duplication | ScreenCaptureKit | X11 shared memory |
| Scale, color | D3D11 video processor | ScreenCaptureKit, on the GPU | The library, on the CPU |
| Encode | Hardware Media Foundation transform | VideoToolbox | GStreamer: VA-API, NVENC, Quick Sync or V4L2, then x264 or OpenH264 |
| Send | The same `wss` socket and Link v2 as everything else | Same | Same |

Viewers play it with `MediaCodec` on a surface on Android and WebCodecs in the browser.

`:host:screencast` does the first three steps in one small native library, with no codec of its own. GStreamer is opened at run time, so the Linux app has no hard dependency on it: most desktops have it, and `gstreamer1.0-plugins-ugly` adds x264 where nothing else is there.

**The Stream menu** in the Screen row of the laptop app picks the route, for all viewers, and takes effect at once:

| Option | What it sends | Needs |
|---|---|---|
| H.264 | Video | An encoder on the PC, a decoder in the viewer |
| Tiles | Only the parts of the screen that changed, as JPEG, compressed on every core | Nothing but a JPEG decoder |
| JPEG | The original stream: whole pictures through AWT Robot, capped at 1280 pixels | Nothing, and it works with older apps |

What a viewer or the PC cannot do falls to the next row by itself, so H.264 falling through gives tiles, not the slow route. iOS has no video decoder yet and gets tiles. On Windows tiles read the screen through Robot, because the library only hands out video there.

- **A still screen costs almost nothing.** Capture waits for a change (on Linux it compares pictures, about 20 times a second). After motion stops, a few repeats sharpen the picture, then no frames go out at all.
- **Frames are never dropped after encoding**, because each depends on the last. While a viewer's socket is busy, nothing is captured. If the link stays slow, the bitrate (or, for tiles, the JPEG quality) falls by a quarter a second and climbs back slowly. With several viewers, one that falls behind waits for a keyframe, so it does not hold up the others.
- **Keyframes come on demand**: when a viewer joins or its decoder loses the picture, at most one per viewer per 500 ms. A request inside that gap waits for it.
- **Viewers say what they play** in their first sealed message. The Screen row shows the route in use, and the server log says why a viewer got a lower one.

| Mode | Frame cap | Long edge | Bitrate |
|---|---|---|---|
| Economy | 15 fps | 1280 px | 3 Mbit/s |
| Balanced | 30 fps | 1920 px | 8 Mbit/s |
| Performance | 60 fps | 1920 px | 16 Mbit/s |

Measured at 1080p on a Ryzen 5 3600 and RTX 3060, sending to a Nothing Phone (2) over Wi-Fi:

| | Frame rate | CPU (one core) |
|---|---|---|
| JPEG, 720p | 19 fps | 61% |
| H.264 Performance | 60 fps | 17% |
| H.264, still screen | 0 fps | under 1% |

Measured at 1080p with Performance mode on Linux, in a container with no GPU, through a browser, with a video over two thirds of the screen:

| | Frame rate | Traffic | Long edge |
|---|---|---|---|
| H.264 (x264, software) | 55–60 fps | about 15 Mbit/s | 1920 px |
| Tiles | 60 fps | about 35 Mbit/s | 1920 px |
| JPEG | about 55 fps | about 24 Mbit/s | 1280 px |

On a mostly still desktop tiles need far less: under 0.5 Mbit/s with one small thing moving.

The stream stays on TCP on purpose. Sunshine and Parsec use their own UDP protocols, which lose less time to packet loss on bad links, but that means a second encrypted transport to secure and a firewall rule, and a browser cannot speak it.

The measurements, the options we compared and the attempts that failed are in [docs/research](research/README.md).
