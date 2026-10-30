#pragma once

#include <d3d11.h>
#include <icodecapi.h>
#include <mfapi.h>
#include <mfidl.h>
#include <mftransform.h>

#include <cstdint>
#include <string>
#include <vector>

#include "Common.h"

class EncoderEvents;

/**
 * The GPU's H.264 encoder as a hardware Media Foundation transform, the same route browsers use
 * for WebRTC on Windows. It covers NVIDIA, AMD and Intel and needs no vendor SDK, and the frames
 * stay on the GPU. Everything here runs on one thread; hardware transforms are asynchronous, so
 * [EncoderEvents] carries their events over from Media Foundation's threads.
 */
class H264Encoder {
public:
    H264Encoder();
    ~H264Encoder();

    H264Encoder(const H264Encoder&) = delete;
    H264Encoder& operator=(const H264Encoder&) = delete;

    HRESULT open(ID3D11Device* device, const LUID& adapter, UINT width, UINT height, UINT framesPerSecond, UINT bitrate);

    /**
     * Encodes [frame] and waits for its bytes, so nothing queues up inside the encoder.
     * Returns S_OK with Annex B bytes in [output], or S_FALSE when the encoder kept the frame.
     */
    HRESULT encode(ID3D11Texture2D* frame, LONGLONG timeHns, bool keyframe, std::vector<uint8_t>& output, bool& isKeyframe);

    void setBitrate(UINT bitrate);

    const std::wstring& name() const { return name_; }

private:
    HRESULT createTransform(const LUID& adapter);
    HRESULT configure(UINT width, UINT height, UINT framesPerSecond, UINT bitrate);
    HRESULT submit(ID3D11Texture2D* frame, LONGLONG timeHns, bool keyframe);
    HRESULT collect(std::vector<uint8_t>& output, bool& isKeyframe);
    void remember(IMFMediaType* outputType);

    ComPtr<IMFDXGIDeviceManager> deviceManager_;
    ComPtr<IMFTransform> transform_;
    ComPtr<ICodecAPI> codec_;
    ComPtr<EncoderEvents> events_;
    std::vector<uint8_t> sequenceHeader_;
    std::wstring name_;
    DWORD inputStream_ = 0;
    DWORD outputStream_ = 0;
    LONGLONG frameDuration_ = 0;
    UINT bitrate_ = 0;
    int inputsWanted_ = 0;
    bool transformProvidesSamples_ = false;
    DWORD outputSampleSize_ = 0;
};
