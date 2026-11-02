#pragma once

#include <d3d11.h>
#include <dxgi1_2.h>

#include <string>
#include <vector>

#include "Common.h"

/**
 * One display as a stream of GPU textures. A new desktop image lands in [image], so a still screen
 * costs nothing and the next step works on the same device.
 */
class DesktopCapture {
public:
    enum class Grab { Frame, Unchanged, Lost };

    virtual ~DesktopCapture() = default;

    /** Waits up to [timeoutMs] for a new desktop image and keeps it in [image]. */
    virtual Grab next(UINT timeoutMs) = 0;

    virtual const char* name() const = 0;

    ID3D11Device* device() const { return device_.Get(); }
    ID3D11DeviceContext* context() const { return context_.Get(); }
    ID3D11Texture2D* image() const { return image_.Get(); }
    const LUID& adapter() const { return adapter_; }
    UINT width() const { return width_; }
    UINT height() const { return height_; }
    DXGI_MODE_ROTATION rotation() const { return rotation_; }

protected:
    HRESULT createDevice(IDXGIAdapter1* adapter);
    HRESULT createImage(UINT width, UINT height);

    ComPtr<ID3D11Device> device_;
    ComPtr<ID3D11DeviceContext> context_;
    ComPtr<ID3D11Texture2D> image_;
    LUID adapter_{};
    UINT width_ = 0;
    UINT height_ = 0;
    DXGI_MODE_ROTATION rotation_ = DXGI_MODE_ROTATION_IDENTITY;
};

/** [index] counts displays in EnumDisplayMonitors order, as AWT's \Display<n> does. */
HMONITOR monitorAt(int index);

/** Every GPU, the one that saves the most power first: on a laptop that is the one driving the screen. */
std::vector<ComPtr<IDXGIAdapter1>> hardwareAdapters();

std::string adapterName(IDXGIAdapter1* adapter);

std::string toUtf8(const std::wstring& text);
