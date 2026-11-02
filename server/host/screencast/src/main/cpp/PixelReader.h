#pragma once

#include <d3d11.h>

#include <cstdint>

#include "Common.h"

/** Brings a BGRA picture from the GPU to memory, for the tile stream. */
class PixelReader {
public:
    HRESULT open(ID3D11Device* device, ID3D11DeviceContext* context, UINT width, UINT height);

    /** Waits for the GPU and copies [frame] to [buffer] in rows of width * 4 bytes. */
    HRESULT read(ID3D11Texture2D* frame, uint8_t* buffer);

private:
    ComPtr<ID3D11DeviceContext> context_;
    ComPtr<ID3D11Texture2D> staging_;
    UINT height_ = 0;
    size_t rowBytes_ = 0;
};
