#pragma once

#include <d3d11.h>
#include <dxgi1_2.h>

#include "Common.h"

/**
 * One display through DXGI Desktop Duplication. The compositor hands over each new desktop image
 * as a GPU texture, so nothing is copied to system memory and an unchanged screen costs nothing.
 * The device lives on the adapter that owns the display: laptops with two GPUs need that.
 */
class DesktopDuplicator {
public:
    enum class Grab { Frame, Unchanged, Lost };

    /** [displayIndex] counts displays in EnumDisplayMonitors order, as AWT's \Display<n> does. */
    HRESULT open(int displayIndex);

    /** Waits up to [timeoutMs] for a new desktop image and keeps it in [image]. */
    Grab next(UINT timeoutMs);

    ID3D11Device* device() const { return device_.Get(); }
    ID3D11DeviceContext* context() const { return context_.Get(); }
    ID3D11Texture2D* image() const { return image_.Get(); }
    const LUID& adapter() const { return adapter_; }
    UINT width() const { return description_.ModeDesc.Width; }
    UINT height() const { return description_.ModeDesc.Height; }
    DXGI_MODE_ROTATION rotation() const { return description_.Rotation; }

private:
    ComPtr<ID3D11Device> device_;
    ComPtr<ID3D11DeviceContext> context_;
    ComPtr<IDXGIOutputDuplication> duplication_;
    ComPtr<ID3D11Texture2D> image_;
    DXGI_OUTDUPL_DESC description_{};
    LUID adapter_{};
    bool hasImage_ = false;
};
