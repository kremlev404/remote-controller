#include "DesktopDuplicator.h"

namespace {

HRESULT findOutput(IDXGIAdapter1* adapter, HMONITOR monitor, ComPtr<IDXGIOutput>& output) {
    ComPtr<IDXGIOutput> found;
    for (UINT index = 0; adapter->EnumOutputs(index, &found) != DXGI_ERROR_NOT_FOUND; ++index) {
        DXGI_OUTPUT_DESC description{};
        if (SUCCEEDED(found->GetDesc(&description)) && description.Monitor == monitor) {
            output = found;
            return S_OK;
        }
    }
    return DXGI_ERROR_NOT_FOUND;
}

}  // namespace

HRESULT DesktopDuplicator::open(IDXGIAdapter1* adapter, HMONITOR monitor) {
    ComPtr<IDXGIOutput> output;
    RETURN_IF_FAILED(findOutput(adapter, monitor, output));
    RETURN_IF_FAILED(createDevice(adapter));

    ComPtr<IDXGIOutput1> output1;
    RETURN_IF_FAILED(output.As(&output1));
    RETURN_IF_FAILED(output1->DuplicateOutput(device_.Get(), &duplication_));

    DXGI_OUTDUPL_DESC description{};
    duplication_->GetDesc(&description);
    rotation_ = description.Rotation;
    return createImage(description.ModeDesc.Width, description.ModeDesc.Height);
}

DesktopDuplicator::Grab DesktopDuplicator::next(UINT timeoutMs) {
    DXGI_OUTDUPL_FRAME_INFO info{};
    ComPtr<IDXGIResource> resource;
    const HRESULT acquired = duplication_->AcquireNextFrame(timeoutMs, &info, &resource);
    if (acquired == DXGI_ERROR_WAIT_TIMEOUT) return Grab::Unchanged;
    // Access lost: a mode change, the UAC prompt, or the lock screen. The session opens a new one.
    if (FAILED(acquired)) return Grab::Lost;

    // No present time means only the pointer moved, and the picture carries no pointer. No metadata
    // means a present that changed no pixels. The first frame holds the desktop whatever it says:
    // on a still screen there is no other.
    const bool changed = info.LastPresentTime.QuadPart != 0 && info.TotalMetadataBufferSize != 0;
    Grab result = Grab::Unchanged;
    if (changed || !hasImage_) {
        ComPtr<ID3D11Texture2D> desktop;
        if (FAILED(resource.As(&desktop))) {
            duplication_->ReleaseFrame();
            return Grab::Lost;
        }
        context_->CopyResource(image_.Get(), desktop.Get());
        hasImage_ = true;
        result = Grab::Frame;
    }
    return SUCCEEDED(duplication_->ReleaseFrame()) ? result : Grab::Lost;
}
