#include "DesktopCapture.h"

#include <dxgi1_6.h>

namespace {

constexpr INT GPU_THREAD_PRIORITY = 7;

BOOL CALLBACK collectMonitor(HMONITOR monitor, HDC, LPRECT, LPARAM list) {
    reinterpret_cast<std::vector<HMONITOR>*>(list)->push_back(monitor);
    return TRUE;
}

/** The next adapter in order of power use where Windows can sort them, in plain order otherwise. */
HRESULT adapterAt(IDXGIFactory1* factory, IDXGIFactory6* sorted, UINT index, ComPtr<IDXGIAdapter1>& adapter) {
    if (sorted == nullptr) return factory->EnumAdapters1(index, &adapter);
    return sorted->EnumAdapterByGpuPreference(index, DXGI_GPU_PREFERENCE_MINIMUM_POWER, IID_PPV_ARGS(&adapter));
}

}  // namespace

HRESULT DesktopCapture::createDevice(IDXGIAdapter1* adapter) {
    DXGI_ADAPTER_DESC1 adapterDescription{};
    RETURN_IF_FAILED(adapter->GetDesc1(&adapterDescription));
    adapter_ = adapterDescription.AdapterLuid;

    const D3D_FEATURE_LEVEL levels[] = {D3D_FEATURE_LEVEL_11_1, D3D_FEATURE_LEVEL_11_0};
    RETURN_IF_FAILED(D3D11CreateDevice(
        adapter,
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
    return S_OK;
}

HRESULT DesktopCapture::createImage(UINT width, UINT height) {
    width_ = width;
    height_ = height;
    D3D11_TEXTURE2D_DESC texture{};
    texture.Width = width;
    texture.Height = height;
    texture.MipLevels = 1;
    texture.ArraySize = 1;
    texture.Format = DXGI_FORMAT_B8G8R8A8_UNORM;
    texture.SampleDesc.Count = 1;
    texture.Usage = D3D11_USAGE_DEFAULT;
    texture.BindFlags = D3D11_BIND_RENDER_TARGET | D3D11_BIND_SHADER_RESOURCE;
    return device_->CreateTexture2D(&texture, nullptr, &image_);
}

HMONITOR monitorAt(int index) {
    std::vector<HMONITOR> monitors;
    EnumDisplayMonitors(nullptr, nullptr, collectMonitor, reinterpret_cast<LPARAM>(&monitors));
    if (index >= 0 && index < static_cast<int>(monitors.size())) return monitors[index];
    return MonitorFromPoint(POINT{0, 0}, MONITOR_DEFAULTTOPRIMARY);
}

std::vector<ComPtr<IDXGIAdapter1>> hardwareAdapters() {
    std::vector<ComPtr<IDXGIAdapter1>> adapters;
    ComPtr<IDXGIFactory1> factory;
    if (FAILED(CreateDXGIFactory1(IID_PPV_ARGS(&factory)))) return adapters;
    ComPtr<IDXGIFactory6> sorted;
    factory.As(&sorted);

    ComPtr<IDXGIAdapter1> adapter;
    for (UINT index = 0; SUCCEEDED(adapterAt(factory.Get(), sorted.Get(), index, adapter)); ++index) {
        DXGI_ADAPTER_DESC1 description{};
        if (SUCCEEDED(adapter->GetDesc1(&description)) && (description.Flags & DXGI_ADAPTER_FLAG_SOFTWARE) == 0) {
            adapters.push_back(adapter);
        }
        adapter.Reset();
    }
    return adapters;
}

std::string adapterName(IDXGIAdapter1* adapter) {
    DXGI_ADAPTER_DESC1 description{};
    return SUCCEEDED(adapter->GetDesc1(&description)) ? toUtf8(description.Description) : "unknown GPU";
}

std::string toUtf8(const std::wstring& text) {
    if (text.empty()) return {};
    const int length = static_cast<int>(text.size());
    const int size = WideCharToMultiByte(CP_UTF8, 0, text.data(), length, nullptr, 0, nullptr, nullptr);
    std::string converted(static_cast<size_t>(size), '\0');
    WideCharToMultiByte(CP_UTF8, 0, text.data(), length, converted.data(), size, nullptr, nullptr);
    return converted;
}
