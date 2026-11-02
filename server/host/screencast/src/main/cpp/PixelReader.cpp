#include "PixelReader.h"

#include <cstring>

namespace {

constexpr size_t BYTES_PER_PIXEL = 4;

}  // namespace

HRESULT PixelReader::open(ID3D11Device* device, ID3D11DeviceContext* context, UINT width, UINT height) {
    context_ = context;
    height_ = height;
    rowBytes_ = width * BYTES_PER_PIXEL;

    D3D11_TEXTURE2D_DESC texture{};
    texture.Width = width;
    texture.Height = height;
    texture.MipLevels = 1;
    texture.ArraySize = 1;
    texture.Format = DXGI_FORMAT_B8G8R8A8_UNORM;
    texture.SampleDesc.Count = 1;
    texture.Usage = D3D11_USAGE_STAGING;
    texture.CPUAccessFlags = D3D11_CPU_ACCESS_READ;
    return device->CreateTexture2D(&texture, nullptr, &staging_);
}

HRESULT PixelReader::read(ID3D11Texture2D* frame, uint8_t* buffer) {
    context_->CopyResource(staging_.Get(), frame);
    D3D11_MAPPED_SUBRESOURCE mapped{};
    RETURN_IF_FAILED(context_->Map(staging_.Get(), 0, D3D11_MAP_READ, 0, &mapped));
    const auto* source = static_cast<const uint8_t*>(mapped.pData);
    for (UINT row = 0; row < height_; ++row) {
        std::memcpy(buffer + row * rowBytes_, source + row * static_cast<size_t>(mapped.RowPitch), rowBytes_);
    }
    context_->Unmap(staging_.Get(), 0);
    return S_OK;
}
