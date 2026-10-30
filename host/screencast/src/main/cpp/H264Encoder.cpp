#include "H264Encoder.h"

#include <initguid.h>

#include <codecapi.h>
#include <mferror.h>
#include <wrl/implements.h>

#include <algorithm>
#include <deque>
#include <mutex>

namespace {

constexpr DWORD EVENT_WAIT_MS = 100;
constexpr UINT KEYFRAME_INTERVAL_SECONDS = 600;
constexpr UINT RATE_BUFFER_DIVISOR = 4;
constexpr BYTE NAL_TYPE_MASK = 0x1F;
constexpr BYTE NAL_SPS = 7;

HRESULT setUint(ICodecAPI* codec, const GUID& key, ULONG value) {
    VARIANT variant{};
    variant.vt = VT_UI4;
    variant.ulVal = value;
    return codec->SetValue(&key, &variant);
}

HRESULT setBool(ICodecAPI* codec, const GUID& key, bool value) {
    VARIANT variant{};
    variant.vt = VT_BOOL;
    variant.boolVal = value ? VARIANT_TRUE : VARIANT_FALSE;
    return codec->SetValue(&key, &variant);
}

/** Whether these Annex B bytes carry a sequence parameter set. */
bool hasSequenceHeader(const std::vector<uint8_t>& bytes) {
    for (size_t index = 0; index + 3 < bytes.size(); ++index) {
        if (bytes[index] == 0 && bytes[index + 1] == 0 && bytes[index + 2] == 1) {
            if ((bytes[index + 3] & NAL_TYPE_MASK) == NAL_SPS) return true;
            index += 3;
        }
    }
    return false;
}

}  // namespace

/** Queues the transform's events, and lets the encoding thread sleep until the next one. */
class EncoderEvents
    : public Microsoft::WRL::RuntimeClass<Microsoft::WRL::RuntimeClassFlags<Microsoft::WRL::ClassicCom>, IMFAsyncCallback> {
public:
    EncoderEvents() : signal_(CreateEventW(nullptr, FALSE, FALSE, nullptr)) {}

    ~EncoderEvents() override {
        if (signal_ != nullptr) CloseHandle(signal_);
    }

    HRESULT start(IMFMediaEventGenerator* generator) {
        generator_ = generator;
        return generator_->BeginGetEvent(this, nullptr);
    }

    void stop() {
        std::lock_guard<std::mutex> lock(mutex_);
        stopped_ = true;
    }

    /** The next event, or MEUnknown after [timeoutMs]. */
    MediaEventType take(DWORD timeoutMs) {
        const ULONGLONG deadline = GetTickCount64() + timeoutMs;
        while (true) {
            {
                std::lock_guard<std::mutex> lock(mutex_);
                if (!queue_.empty()) {
                    const MediaEventType type = queue_.front();
                    queue_.pop_front();
                    return type;
                }
            }
            const ULONGLONG now = GetTickCount64();
            if (now >= deadline) return MEUnknown;
            WaitForSingleObject(signal_, static_cast<DWORD>(deadline - now));
        }
    }

    STDMETHODIMP GetParameters(DWORD*, DWORD*) override { return E_NOTIMPL; }

    STDMETHODIMP Invoke(IMFAsyncResult* result) override {
        ComPtr<IMFMediaEvent> event;
        if (FAILED(generator_->EndGetEvent(result, &event))) return S_OK;
        MediaEventType type = MEUnknown;
        event->GetType(&type);
        {
            std::lock_guard<std::mutex> lock(mutex_);
            if (stopped_) return S_OK;
            queue_.push_back(type);
        }
        SetEvent(signal_);
        generator_->BeginGetEvent(this, nullptr);
        return S_OK;
    }

private:
    ComPtr<IMFMediaEventGenerator> generator_;
    std::mutex mutex_;
    std::deque<MediaEventType> queue_;
    HANDLE signal_;
    bool stopped_ = false;
};

H264Encoder::H264Encoder() = default;

H264Encoder::~H264Encoder() {
    if (events_) events_->stop();
    if (transform_) {
        transform_->ProcessMessage(MFT_MESSAGE_NOTIFY_END_OF_STREAM, 0);
        transform_->ProcessMessage(MFT_MESSAGE_NOTIFY_END_STREAMING, 0);
        transform_->ProcessMessage(MFT_MESSAGE_SET_D3D_MANAGER, 0);
        ComPtr<IMFShutdown> shutdown;
        if (SUCCEEDED(transform_.As(&shutdown))) shutdown->Shutdown();
    }
}

HRESULT H264Encoder::open(
    ID3D11Device* device,
    const LUID& adapter,
    UINT width,
    UINT height,
    UINT framesPerSecond,
    UINT bitrate) {
    frameDuration_ = 10'000'000LL / framesPerSecond;
    bitrate_ = bitrate;

    RETURN_IF_FAILED(createTransform(adapter));

    UINT token = 0;
    RETURN_IF_FAILED(MFCreateDXGIDeviceManager(&token, &deviceManager_));
    RETURN_IF_FAILED(deviceManager_->ResetDevice(device, token));
    RETURN_IF_FAILED(transform_->ProcessMessage(
        MFT_MESSAGE_SET_D3D_MANAGER,
        reinterpret_cast<ULONG_PTR>(deviceManager_.Get())));

    RETURN_IF_FAILED(configure(width, height, framesPerSecond, bitrate));

    ComPtr<IMFMediaEventGenerator> generator;
    RETURN_IF_FAILED(transform_.As(&generator));
    events_ = Microsoft::WRL::Make<EncoderEvents>();
    if (!events_) return E_OUTOFMEMORY;
    RETURN_IF_FAILED(events_->start(generator.Get()));

    RETURN_IF_FAILED(transform_->ProcessMessage(MFT_MESSAGE_NOTIFY_BEGIN_STREAMING, 0));
    return transform_->ProcessMessage(MFT_MESSAGE_NOTIFY_START_OF_STREAM, 0);
}

HRESULT H264Encoder::createTransform(const LUID& adapter) {
    const MFT_REGISTER_TYPE_INFO input{MFMediaType_Video, MFVideoFormat_NV12};
    const MFT_REGISTER_TYPE_INFO output{MFMediaType_Video, MFVideoFormat_H264};

    // Only the encoder of the GPU that owns the display can read its textures.
    ComPtr<IMFAttributes> filter;
    RETURN_IF_FAILED(MFCreateAttributes(&filter, 1));
    RETURN_IF_FAILED(filter->SetBlob(MFT_ENUM_ADAPTER_LUID, reinterpret_cast<const UINT8*>(&adapter), sizeof(adapter)));

    IMFActivate** activates = nullptr;
    UINT32 count = 0;
    RETURN_IF_FAILED(MFTEnum2(
        MFT_CATEGORY_VIDEO_ENCODER,
        MFT_ENUM_FLAG_HARDWARE | MFT_ENUM_FLAG_SORTANDFILTER,
        &input,
        &output,
        filter.Get(),
        &activates,
        &count));

    HRESULT result = MF_E_TOPO_CODEC_NOT_FOUND;
    for (UINT32 index = 0; index < count; ++index) {
        if (FAILED(result)) {
            result = activates[index]->ActivateObject(IID_PPV_ARGS(&transform_));
            if (SUCCEEDED(result)) {
                WCHAR* friendlyName = nullptr;
                UINT32 length = 0;
                if (SUCCEEDED(activates[index]->GetAllocatedString(MFT_FRIENDLY_NAME_Attribute, &friendlyName, &length))) {
                    name_ = friendlyName;
                    CoTaskMemFree(friendlyName);
                }
            }
        }
        activates[index]->Release();
    }
    CoTaskMemFree(activates);
    RETURN_IF_FAILED(result);

    ComPtr<IMFAttributes> attributes;
    RETURN_IF_FAILED(transform_->GetAttributes(&attributes));
    RETURN_IF_FAILED(attributes->SetUINT32(MF_TRANSFORM_ASYNC_UNLOCK, TRUE));
    attributes->SetUINT32(MF_LOW_LATENCY, TRUE);

    const HRESULT streams = transform_->GetStreamIDs(1, &inputStream_, 1, &outputStream_);
    if (FAILED(streams) && streams != E_NOTIMPL) return streams;
    return transform_.As(&codec_);
}

HRESULT H264Encoder::configure(UINT width, UINT height, UINT framesPerSecond, UINT bitrate) {
    setBool(codec_.Get(), CODECAPI_AVLowLatencyMode, true);
    setUint(codec_.Get(), CODECAPI_AVEncCommonRateControlMode, eAVEncCommonRateControlMode_CBR);
    setUint(codec_.Get(), CODECAPI_AVEncCommonMeanBitRate, bitrate);
    setUint(codec_.Get(), CODECAPI_AVEncCommonBufferSize, bitrate / RATE_BUFFER_DIVISOR);
    setUint(codec_.Get(), CODECAPI_AVEncMPVDefaultBPictureCount, 0);
    // Keyframes are costly and a socket does not lose frames: they come when a viewer needs one.
    setUint(codec_.Get(), CODECAPI_AVEncMPVGOPSize, framesPerSecond * KEYFRAME_INTERVAL_SECONDS);

    ComPtr<IMFMediaType> output;
    RETURN_IF_FAILED(MFCreateMediaType(&output));
    RETURN_IF_FAILED(output->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Video));
    RETURN_IF_FAILED(output->SetGUID(MF_MT_SUBTYPE, MFVideoFormat_H264));
    RETURN_IF_FAILED(output->SetUINT32(MF_MT_AVG_BITRATE, bitrate));
    RETURN_IF_FAILED(output->SetUINT32(MF_MT_INTERLACE_MODE, MFVideoInterlace_Progressive));
    RETURN_IF_FAILED(output->SetUINT32(MF_MT_MPEG2_PROFILE, eAVEncH264VProfile_High));
    RETURN_IF_FAILED(MFSetAttributeSize(output.Get(), MF_MT_FRAME_SIZE, width, height));
    RETURN_IF_FAILED(MFSetAttributeRatio(output.Get(), MF_MT_FRAME_RATE, framesPerSecond, 1));
    RETURN_IF_FAILED(MFSetAttributeRatio(output.Get(), MF_MT_PIXEL_ASPECT_RATIO, 1, 1));
    RETURN_IF_FAILED(transform_->SetOutputType(outputStream_, output.Get(), 0));

    ComPtr<IMFMediaType> input;
    RETURN_IF_FAILED(MFCreateMediaType(&input));
    RETURN_IF_FAILED(input->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Video));
    RETURN_IF_FAILED(input->SetGUID(MF_MT_SUBTYPE, MFVideoFormat_NV12));
    RETURN_IF_FAILED(input->SetUINT32(MF_MT_INTERLACE_MODE, MFVideoInterlace_Progressive));
    RETURN_IF_FAILED(input->SetUINT32(MF_MT_VIDEO_NOMINAL_RANGE, MFNominalRange_16_235));
    RETURN_IF_FAILED(input->SetUINT32(MF_MT_YUV_MATRIX, MFVideoTransferMatrix_BT709));
    RETURN_IF_FAILED(input->SetUINT32(MF_MT_VIDEO_PRIMARIES, MFVideoPrimaries_BT709));
    RETURN_IF_FAILED(input->SetUINT32(MF_MT_TRANSFER_FUNCTION, MFVideoTransFunc_709));
    RETURN_IF_FAILED(MFSetAttributeSize(input.Get(), MF_MT_FRAME_SIZE, width, height));
    RETURN_IF_FAILED(MFSetAttributeRatio(input.Get(), MF_MT_FRAME_RATE, framesPerSecond, 1));
    RETURN_IF_FAILED(MFSetAttributeRatio(input.Get(), MF_MT_PIXEL_ASPECT_RATIO, 1, 1));
    RETURN_IF_FAILED(transform_->SetInputType(inputStream_, input.Get(), 0));

    MFT_OUTPUT_STREAM_INFO streamInfo{};
    RETURN_IF_FAILED(transform_->GetOutputStreamInfo(outputStream_, &streamInfo));
    transformProvidesSamples_ = (streamInfo.dwFlags & MFT_OUTPUT_STREAM_PROVIDES_SAMPLES) != 0;
    outputSampleSize_ = streamInfo.cbSize;

    ComPtr<IMFMediaType> negotiated;
    if (SUCCEEDED(transform_->GetOutputCurrentType(outputStream_, &negotiated))) remember(negotiated.Get());
    return S_OK;
}

void H264Encoder::remember(IMFMediaType* outputType) {
    UINT32 size = 0;
    if (FAILED(outputType->GetBlobSize(MF_MT_MPEG_SEQUENCE_HEADER, &size)) || size == 0) return;
    sequenceHeader_.resize(size);
    if (FAILED(outputType->GetBlob(MF_MT_MPEG_SEQUENCE_HEADER, sequenceHeader_.data(), size, nullptr))) {
        sequenceHeader_.clear();
    }
}

void H264Encoder::setBitrate(UINT bitrate) {
    if (bitrate == bitrate_ || !codec_) return;
    if (SUCCEEDED(setUint(codec_.Get(), CODECAPI_AVEncCommonMeanBitRate, bitrate))) {
        setUint(codec_.Get(), CODECAPI_AVEncCommonBufferSize, bitrate / RATE_BUFFER_DIVISOR);
        bitrate_ = bitrate;
    }
}

HRESULT H264Encoder::encode(
    ID3D11Texture2D* frame,
    LONGLONG timeHns,
    bool keyframe,
    std::vector<uint8_t>& output,
    bool& isKeyframe) {
    bool submitted = false;
    if (inputsWanted_ > 0) {
        RETURN_IF_FAILED(submit(frame, timeHns, keyframe));
        --inputsWanted_;
        submitted = true;
    }
    while (true) {
        const MediaEventType event = events_->take(EVENT_WAIT_MS);
        switch (event) {
            case METransformNeedInput:
                if (submitted) {
                    ++inputsWanted_;
                } else {
                    RETURN_IF_FAILED(submit(frame, timeHns, keyframe));
                    submitted = true;
                }
                break;
            case METransformHaveOutput:
                RETURN_IF_FAILED(collect(output, isKeyframe));
                if (!output.empty()) return S_OK;
                break;
            case MEError:
                return E_FAIL;
            case MEUnknown:
                return submitted ? S_FALSE : MF_E_NOTACCEPTING;
            default:
                break;
        }
    }
}

HRESULT H264Encoder::submit(ID3D11Texture2D* frame, LONGLONG timeHns, bool keyframe) {
    if (keyframe) setUint(codec_.Get(), CODECAPI_AVEncVideoForceKeyFrame, 1);

    ComPtr<IMFMediaBuffer> buffer;
    RETURN_IF_FAILED(MFCreateDXGISurfaceBuffer(__uuidof(ID3D11Texture2D), frame, 0, FALSE, &buffer));
    ComPtr<IMF2DBuffer> buffer2d;
    RETURN_IF_FAILED(buffer.As(&buffer2d));
    DWORD length = 0;
    RETURN_IF_FAILED(buffer2d->GetContiguousLength(&length));
    RETURN_IF_FAILED(buffer->SetCurrentLength(length));

    ComPtr<IMFSample> sample;
    RETURN_IF_FAILED(MFCreateSample(&sample));
    RETURN_IF_FAILED(sample->AddBuffer(buffer.Get()));
    RETURN_IF_FAILED(sample->SetSampleTime(timeHns));
    RETURN_IF_FAILED(sample->SetSampleDuration(frameDuration_));
    return transform_->ProcessInput(inputStream_, sample.Get(), 0);
}

HRESULT H264Encoder::collect(std::vector<uint8_t>& output, bool& isKeyframe) {
    MFT_OUTPUT_DATA_BUFFER data{};
    data.dwStreamID = outputStream_;
    ComPtr<IMFSample> ownSample;
    if (!transformProvidesSamples_) {
        ComPtr<IMFMediaBuffer> buffer;
        RETURN_IF_FAILED(MFCreateMemoryBuffer(outputSampleSize_, &buffer));
        RETURN_IF_FAILED(MFCreateSample(&ownSample));
        RETURN_IF_FAILED(ownSample->AddBuffer(buffer.Get()));
        data.pSample = ownSample.Get();
    }

    DWORD status = 0;
    const HRESULT result = transform_->ProcessOutput(0, 1, &data, &status);
    if (data.pEvents != nullptr) data.pEvents->Release();

    if (result == MF_E_TRANSFORM_STREAM_CHANGE) {
        ComPtr<IMFMediaType> changed;
        RETURN_IF_FAILED(transform_->GetOutputAvailableType(outputStream_, 0, &changed));
        RETURN_IF_FAILED(transform_->SetOutputType(outputStream_, changed.Get(), 0));
        remember(changed.Get());
        return S_OK;
    }
    if (result == MF_E_TRANSFORM_NEED_MORE_INPUT) return S_OK;
    RETURN_IF_FAILED(result);

    // When the transform provides the sample, this call owns the reference it returned.
    ComPtr<IMFSample> sample;
    if (transformProvidesSamples_) sample.Attach(data.pSample);
    else sample = ownSample;
    if (!sample) return S_OK;

    ComPtr<IMFMediaBuffer> contiguous;
    RETURN_IF_FAILED(sample->ConvertToContiguousBuffer(&contiguous));
    BYTE* bytes = nullptr;
    DWORD length = 0;
    RETURN_IF_FAILED(contiguous->Lock(&bytes, nullptr, &length));
    output.assign(bytes, bytes + length);
    contiguous->Unlock();

    UINT32 cleanPoint = 0;
    isKeyframe = SUCCEEDED(sample->GetUINT32(MFSampleExtension_CleanPoint, &cleanPoint)) && cleanPoint != 0;
    // A viewer starts from a keyframe: it has to carry the sequence header in the stream.
    if (isKeyframe && !sequenceHeader_.empty() && !hasSequenceHeader(output)) {
        output.insert(output.begin(), sequenceHeader_.begin(), sequenceHeader_.end());
    }
    return S_OK;
}
