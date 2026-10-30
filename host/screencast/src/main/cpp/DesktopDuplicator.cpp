#include "DesktopDuplicator.h"

#include <vector>

namespace {

constexpr INT GPU_THREAD_PRIORITY = 7;

BOOL CALLBACK collectMonitor(HMONITOR monitor, HDC, LPRECT, LPARAM list) {
    reinterpret_cast<std::vector<HMONITOR>*>(list)->push_back(monitor);
    return TRUE;
}

HMONITOR monitorAt(int index) {
    std::vector<HMONITOR> monitors;
    EnumDisplayMonitors(nullptr, nullptr, collectMonitor, reinterpret_cast<LPARAM>(&monitors));
    if (index >= 0 && index < static_cast<int>(monitors.size())) return monitors[index];
    return MonitorFromPoint(POINT{0, 0}, MONITOR_DEFAULTTOPRIMARY);
}

HRESULT findOutput(HMONITOR monitor, ComPtr<IDXGIAdapter1>& adapter, ComPtr<IDXGIOutput>& output) {
    ComPtr<IDXGIFactory1> factory;
    RETURN_IF_FAILED(CreateDXGIFactory1(IID_PPV_ARGS(&factory)));
    ComPtr<IDXGIAdapter1> candidate;
    for (UINT adapterIndex = 0; factory->EnumAdapters1(adapterIndex, &candidate) != DXGI_ERROR_NOT_FOUND; ++adapterIndex) {
        ComPtr<IDXGIOutput> found;
        for (UINT outputIndex = 0; candidate->EnumOutputs(outputIndex, &found) != DXGI_ERROR_NOT_FOUND; ++outputIndex) {
            DXGI_OUTPUT_DESC description{};
            if (SUCCEEDED(found->GetDesc(&description)) && description.Monitor == monitor) {
                adapter = candidate;
                output = found;
                return S_OK;
            }
        }
    }
    return DXGI_ERROR_NOT_FOUND;
}

}  // namespace

HRESULT DesktopDuplicator::open(int displayIndex) {
    ComPtr<IDXGIAdapter1> adapter;
    ComPtr<IDXGIOutput> output;
    RETURN_IF_FAILED(findOutput(monitorAt(displayIndex), adapter, output));

    DXGI_ADAPTER_DESC1 adapterDescription{};
    RETURN_IF_FAILED(adapter->GetDesc1(&adapterDescription));
    adapter_ = adapterDescription.AdapterLuid;

    const D3D_FEATURE_LEVEL levels[] = {D3D_FEATURE_LEVEL_11_1, D3D_FEATURE_LEVEL_11_0};
    RETURN_IF_FAILED(D3D11CreateDevice(
        adapter.Get(),
        D3D_DRIVER_TYPE_UNKNOWN,
        nullptr,
        D3D11_CREATE_DEVICE_VIDEO_SUPPORT | D3D11_CREATE_DEVICE_BGRA_SUPPORT,
        levels,
        ARRAYSIZE(levels),
        D3D11_SDK_VERSION,
        &device_,
        nullptr,
        &context_));

    // The encoder works on this device from Media Foundation's threads.
    ComPtr<ID3D10Multithread> multithread;
    RETURN_IF_FAILED(device_.As(&multithread));
    multithread->SetMultithreadProtected(TRUE);

    // Keeps the picture moving while a game fills the GPU.
    ComPtr<IDXGIDevice> dxgiDevice;
    RETURN_IF_FAILED(device_.As(&dxgiDevice));
    dxgiDevice->SetGPUThreadPriority(GPU_THREAD_PRIORITY);

    ComPtr<IDXGIOutput1> output1;
    RETURN_IF_FAILED(output.As(&output1));
    RETURN_IF_FAILED(output1->DuplicateOutput(device_.Get(), &duplication_));
    duplication_->GetDesc(&description_);

    D3D11_TEXTURE2D_DESC texture{};
    texture.Width = description_.ModeDesc.Width;
    texture.Height = description_.ModeDesc.Height;
    texture.MipLevels = 1;
    texture.ArraySize = 1;
    texture.Format = DXGI_FORMAT_B8G8R8A8_UNORM;
    texture.SampleDesc.Count = 1;
    texture.Usage = D3D11_USAGE_DEFAULT;
    texture.BindFlags = D3D11_BIND_RENDER_TARGET | D3D11_BIND_SHADER_RESOURCE;
    return device_->CreateTexture2D(&texture, nullptr, &image_);
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
