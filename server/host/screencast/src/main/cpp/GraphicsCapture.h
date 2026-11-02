#pragma once

#include <windows.graphics.capture.h>

#include <memory>

#include "DesktopCapture.h"

/**
 * One display through Windows Graphics Capture. Windows composes the desktop and carries it across
 * to whichever GPU this device is on, so it works where [DesktopDuplicator] cannot: a laptop with
 * two GPUs, whichever one the process landed on. That copy costs a little, and Windows 10 draws a
 * yellow border around the captured display, so duplication stays the first choice.
 */
class GraphicsCapture : public DesktopCapture {
public:
    ~GraphicsCapture() override;

    HRESULT open(IDXGIAdapter1* adapter, HMONITOR monitor);

    Grab next(UINT timeoutMs) override;

    const char* name() const override { return "Windows Graphics Capture"; }

private:
    using Frame = ABI::Windows::Graphics::Capture::IDirect3D11CaptureFrame;

    HRESULT createPool(ABI::Windows::Graphics::SizeInt32 size);
    HRESULT takeNewest(ComPtr<Frame>& newest);
    Grab keep(Frame* frame);

    class Signal;

    ComPtr<ABI::Windows::Graphics::Capture::IGraphicsCaptureItem> item_;
    ComPtr<ABI::Windows::Graphics::Capture::IDirect3D11CaptureFramePool> pool_;
    ComPtr<ABI::Windows::Graphics::Capture::IGraphicsCaptureSession> session_;
    // Shared with the handler, which can still be running on a pool thread while this closes.
    std::shared_ptr<Signal> frameArrived_;
    EventRegistrationToken arrivedToken_{};
};
