#pragma once

#include "DesktopCapture.h"

/**
 * One display through DXGI Desktop Duplication, the cheapest capture there is: the compositor hands
 * over each new desktop image and nothing is copied between GPUs. It only works on the GPU that
 * drives the display. On a laptop with two GPUs the other one can list the same monitor as a shim,
 * and there DuplicateOutput answers DXGI_ERROR_UNSUPPORTED: [GraphicsCapture] covers that case.
 */
class DesktopDuplicator : public DesktopCapture {
public:
    /** DXGI_ERROR_NOT_FOUND when [adapter] does not show [monitor]. */
    HRESULT open(IDXGIAdapter1* adapter, HMONITOR monitor);

    Grab next(UINT timeoutMs) override;

    const char* name() const override { return "Desktop Duplication"; }

private:
    ComPtr<IDXGIOutputDuplication> duplication_;
    bool hasImage_ = false;
};
