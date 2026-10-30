#include "FrameConverter.h"

#include <d3d11_1.h>

namespace {

D3D11_VIDEO_PROCESSOR_ROTATION toProcessorRotation(DXGI_MODE_ROTATION rotation) {
    switch (rotation) {
        case DXGI_MODE_ROTATION_ROTATE90: return D3D11_VIDEO_PROCESSOR_ROTATION_90;
        case DXGI_MODE_ROTATION_ROTATE180: return D3D11_VIDEO_PROCESSOR_ROTATION_180;
        case DXGI_MODE_ROTATION_ROTATE270: return D3D11_VIDEO_PROCESSOR_ROTATION_270;
        default: return D3D11_VIDEO_PROCESSOR_ROTATION_IDENTITY;
    }
}

}  // namespace

HRESULT FrameConverter::open(
    ID3D11Device* device,
    ID3D11DeviceContext* context,
    ID3D11Texture2D* source,
    DXGI_MODE_ROTATION rotation,
    UINT width,
    UINT height) {
    RETURN_IF_FAILED(device->QueryInterface(IID_PPV_ARGS(&videoDevice_)));
    RETURN_IF_FAILED(context->QueryInterface(IID_PPV_ARGS(&videoContext_)));

    D3D11_TEXTURE2D_DESC sourceDescription{};
    source->GetDesc(&sourceDescription);

    D3D11_VIDEO_PROCESSOR_CONTENT_DESC content{};
    content.InputFrameFormat = D3D11_VIDEO_FRAME_FORMAT_PROGRESSIVE;
    content.InputWidth = sourceDescription.Width;
    content.InputHeight = sourceDescription.Height;
    content.OutputWidth = width;
    content.OutputHeight = height;
    content.Usage = D3D11_VIDEO_USAGE_OPTIMAL_SPEED;
    RETURN_IF_FAILED(videoDevice_->CreateVideoProcessorEnumerator(&content, &enumerator_));
    RETURN_IF_FAILED(videoDevice_->CreateVideoProcessor(enumerator_.Get(), 0, &processor_));

    D3D11_VIDEO_PROCESSOR_INPUT_VIEW_DESC inputView{};
    inputView.ViewDimension = D3D11_VPIV_DIMENSION_TEXTURE2D;
    RETURN_IF_FAILED(videoDevice_->CreateVideoProcessorInputView(source, enumerator_.Get(), &inputView, &input_));

    D3D11_TEXTURE2D_DESC target{};
    target.Width = width;
    target.Height = height;
    target.MipLevels = 1;
    target.ArraySize = 1;
    target.Format = DXGI_FORMAT_NV12;
    target.SampleDesc.Count = 1;
    target.Usage = D3D11_USAGE_DEFAULT;
    target.BindFlags = D3D11_BIND_RENDER_TARGET | D3D11_BIND_VIDEO_ENCODER;

    D3D11_VIDEO_PROCESSOR_OUTPUT_VIEW_DESC outputView{};
    outputView.ViewDimension = D3D11_VPOV_DIMENSION_TEXTURE2D;
    for (size_t index = 0; index < RING_SIZE; ++index) {
        RETURN_IF_FAILED(device->CreateTexture2D(&target, nullptr, &targets_[index]));
        RETURN_IF_FAILED(videoDevice_->CreateVideoProcessorOutputView(
            targets_[index].Get(), enumerator_.Get(), &outputView, &outputs_[index]));
    }

    ComPtr<ID3D11VideoContext1> colorContext;
    if (SUCCEEDED(videoContext_.As(&colorContext))) {
        colorContext->VideoProcessorSetStreamColorSpace1(processor_.Get(), 0, DXGI_COLOR_SPACE_RGB_FULL_G22_NONE_P709);
        colorContext->VideoProcessorSetOutputColorSpace1(processor_.Get(), DXGI_COLOR_SPACE_YCBCR_STUDIO_G22_LEFT_P709);
    }

    const RECT sourceRect{0, 0, static_cast<LONG>(sourceDescription.Width), static_cast<LONG>(sourceDescription.Height)};
    const RECT targetRect{0, 0, static_cast<LONG>(width), static_cast<LONG>(height)};
    videoContext_->VideoProcessorSetStreamFrameFormat(processor_.Get(), 0, D3D11_VIDEO_FRAME_FORMAT_PROGRESSIVE);
    videoContext_->VideoProcessorSetStreamSourceRect(processor_.Get(), 0, TRUE, &sourceRect);
    videoContext_->VideoProcessorSetStreamDestRect(processor_.Get(), 0, TRUE, &targetRect);
    videoContext_->VideoProcessorSetOutputTargetRect(processor_.Get(), TRUE, &targetRect);
    videoContext_->VideoProcessorSetStreamAutoProcessingMode(processor_.Get(), 0, FALSE);
    videoContext_->VideoProcessorSetStreamRotation(processor_.Get(), 0, TRUE, toProcessorRotation(rotation));
    return S_OK;
}

ID3D11Texture2D* FrameConverter::convert() {
    const size_t index = next_;
    next_ = (next_ + 1) % RING_SIZE;

    D3D11_VIDEO_PROCESSOR_STREAM stream{};
    stream.Enable = TRUE;
    stream.pInputSurface = input_.Get();
    const HRESULT result = videoContext_->VideoProcessorBlt(processor_.Get(), outputs_[index].Get(), 0, 1, &stream);
    return SUCCEEDED(result) ? targets_[index].Get() : nullptr;
}
