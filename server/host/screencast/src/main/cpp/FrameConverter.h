#pragma once

#include <d3d11.h>

#include <array>

#include "Common.h"

/**
 * Scales the desktop with the GPU's fixed-function video block, into the NV12 the encoder takes or
 * the BGRA the tile stream reads. The textures go round a small ring: the encoder may still read
 * the last one.
 */
class FrameConverter {
public:
    HRESULT open(
        ID3D11Device* device,
        ID3D11DeviceContext* context,
        ID3D11Texture2D* source,
        DXGI_MODE_ROTATION rotation,
        UINT width,
        UINT height,
        DXGI_FORMAT format);

    /** Returns the texture that holds the picture, or null on failure. */
    ID3D11Texture2D* convert();

private:
    static constexpr size_t RING_SIZE = 4;

    ComPtr<ID3D11VideoDevice> videoDevice_;
    ComPtr<ID3D11VideoContext> videoContext_;
    ComPtr<ID3D11VideoProcessorEnumerator> enumerator_;
    ComPtr<ID3D11VideoProcessor> processor_;
    ComPtr<ID3D11VideoProcessorInputView> input_;
    std::array<ComPtr<ID3D11Texture2D>, RING_SIZE> targets_;
    std::array<ComPtr<ID3D11VideoProcessorOutputView>, RING_SIZE> outputs_;
    size_t next_ = 0;
};
