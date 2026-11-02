#include <jni.h>

#include <mfapi.h>
#include <objbase.h>

#include <algorithm>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <memory>
#include <new>
#include <string>
#include <vector>

#include "DesktopDuplicator.h"
#include "FrameConverter.h"
#include "GraphicsCapture.h"
#include "H264Encoder.h"
#include "PixelReader.h"

namespace {

// Keep in step with ScreencastNative.kt.
constexpr jint NEXT_UNCHANGED = 0;
constexpr jint NEXT_RETRY = -1;
constexpr jint NEXT_TOO_LARGE = -2;
constexpr size_t HEADER_BYTES = 16;
constexpr jint FLAG_KEYFRAME = 1;
constexpr jint FLAG_PIXELS = 2;
constexpr size_t BYTES_PER_PIXEL = 4;

constexpr DWORD IDLE_WAIT_MS = 50;
constexpr DWORD RETRY_WAIT_MS = 250;
constexpr DWORD MS_PER_SECOND = 1000;
constexpr int SETTLE_FRAMES = 8;

/** Even, and never larger than the desktop: encoders work on whole chroma samples. */
void fitInto(UINT sourceWidth, UINT sourceHeight, UINT maxEdge, UINT& width, UINT& height) {
    const UINT longEdge = std::max(sourceWidth, sourceHeight);
    const double scale = longEdge > maxEdge ? static_cast<double>(maxEdge) / longEdge : 1.0;
    width = std::max(2U, static_cast<UINT>(sourceWidth * scale + 0.5) & ~1U);
    height = std::max(2U, static_cast<UINT>(sourceHeight * scale + 0.5) & ~1U);
}

/**
 * The capture → convert → encode chain, or capture → convert → read with a [bitrate] of 0, which
 * hands out pixels for the tile stream. It belongs to one thread, so it takes no locks.
 */
class Session {
public:
    Session(int displayIndex, UINT maxEdge, UINT framesPerSecond, UINT bitrate, std::string preferredGpu)
        : displayIndex_(displayIndex),
          maxEdge_(maxEdge),
          framesPerSecond_(framesPerSecond),
          bitrate_(bitrate),
          preferredGpu_(std::move(preferredGpu)),
          frameTicks_(0),
          timer_(CreateWaitableTimerExW(nullptr, nullptr, CREATE_WAITABLE_TIMER_HIGH_RESOLUTION, TIMER_ALL_ACCESS)) {
        LARGE_INTEGER frequency{};
        QueryPerformanceFrequency(&frequency);
        ticksPerSecond_ = frequency.QuadPart;
        frameTicks_ = ticksPerSecond_ / framesPerSecond_;
        comReady_ = SUCCEEDED(CoInitializeEx(nullptr, COINIT_MULTITHREADED));
        mediaReady_ = SUCCEEDED(MFStartup(MF_VERSION, MFSTARTUP_LITE));
    }

    ~Session() {
        teardown();
        if (timer_ != nullptr) CloseHandle(timer_);
        if (mediaReady_) MFShutdown();
        if (comReady_) CoUninitialize();
    }

    Session(const Session&) = delete;
    Session& operator=(const Session&) = delete;

    jint next(uint8_t* buffer, size_t capacity, bool keyframe, UINT bitrate) {
        wantKeyframe_ = wantKeyframe_ || keyframe;
        if (encoding()) bitrate_ = bitrate;
        if (!capture_ && !bringUp()) {
            Sleep(RETRY_WAIT_MS);
            return NEXT_RETRY;
        }
        if (encoding()) encoder_->setBitrate(bitrate_);

        waitForFrameTime();
        const bool repeat = hasImage_ && (wantKeyframe_ || settle_ > 0);
        const auto grab = capture_->next(repeat ? 0 : IDLE_WAIT_MS);
        if (grab == DesktopCapture::Grab::Lost) {
            teardown();
            wantKeyframe_ = true;
            return NEXT_RETRY;
        }
        if (grab == DesktopCapture::Grab::Frame) {
            settle_ = encoding() ? SETTLE_FRAMES : 0;
            hasImage_ = true;
        } else if (!repeat) {
            return NEXT_UNCHANGED;
        } else if (settle_ > 0) {
            // The last picture again: each pass sharpens what the motion left blurred.
            --settle_;
        }

        ID3D11Texture2D* frame = converter_->convert();
        if (frame == nullptr) {
            teardown();
            return NEXT_RETRY;
        }

        LARGE_INTEGER now{};
        QueryPerformanceCounter(&now);
        // A fixed grid: a timer that wakes late must not push every later frame back.
        nextFrameAt_ = now.QuadPart - nextFrameAt_ > frameTicks_ ? now.QuadPart + frameTicks_ : nextFrameAt_ + frameTicks_;
        if (!encoding()) return copyPixels(frame, buffer, capacity);

        const LONGLONG timeHns = (now.QuadPart / ticksPerSecond_) * 10'000'000LL +
            (now.QuadPart % ticksPerSecond_) * 10'000'000LL / ticksPerSecond_;

        bool isKeyframe = false;
        const HRESULT encoded = encoder_->encode(frame, timeHns, wantKeyframe_, bytes_, isKeyframe);
        if (FAILED(encoded)) {
            status_ = "encoder failed: " + hex(encoded);
            teardown();
            wantKeyframe_ = true;
            return NEXT_RETRY;
        }
        wantKeyframe_ = false;
        if (encoded == S_FALSE || bytes_.empty()) return NEXT_UNCHANGED;

        const size_t total = HEADER_BYTES + bytes_.size();
        if (total > capacity) {
            wantKeyframe_ = true;
            bytes_.clear();
            return NEXT_TOO_LARGE;
        }
        const jint header[] = {
            static_cast<jint>(width_),
            static_cast<jint>(height_),
            isKeyframe ? FLAG_KEYFRAME : 0,
            0,
        };
        std::memcpy(buffer, header, HEADER_BYTES);
        std::memcpy(buffer + HEADER_BYTES, bytes_.data(), bytes_.size());
        const jint size = static_cast<jint>(bytes_.size());
        bytes_.clear();
        return size;
    }

    const std::string& status() const { return status_; }

private:
    bool encoding() const { return bitrate_ != 0; }

    jint copyPixels(ID3D11Texture2D* frame, uint8_t* buffer, size_t capacity) {
        const size_t rowBytes = width_ * BYTES_PER_PIXEL;
        const size_t total = rowBytes * height_;
        if (HEADER_BYTES + total > capacity) return NEXT_TOO_LARGE;
        const HRESULT read = reader_->read(frame, buffer + HEADER_BYTES);
        if (FAILED(read)) {
            status_ = "reading the picture failed: " + hex(read);
            teardown();
            wantKeyframe_ = true;
            return NEXT_RETRY;
        }
        wantKeyframe_ = false;
        const jint header[] = {
            static_cast<jint>(width_),
            static_cast<jint>(height_),
            FLAG_PIXELS,
            static_cast<jint>(rowBytes),
        };
        std::memcpy(buffer, header, HEADER_BYTES);
        return static_cast<jint>(total);
    }

    /**
     * Each way of reaching the screen, cheapest first: duplication on every GPU that lists the
     * display, since on two-GPU laptops one of them only pretends to own it, then Windows Graphics
     * Capture on every GPU, which also reaches a GPU with an encoder when the display's has none.
     * The GPU the user picked goes before all of them, by either capture: it may not be the one
     * driving the display, and duplication on that other GPU would otherwise win. A pick that
     * does not work falls through to the usual order, so it never costs the picture.
     */
    bool bringUp() {
        if (!comReady_ || !mediaReady_) {
            status_ = "Media Foundation is not available";
            return false;
        }
        const HMONITOR monitor = monitorAt(displayIndex_);
        const auto adapters = hardwareAdapters();
        std::string problems;
        bool locked = false;
        const auto duplicateOn = [&](IDXGIAdapter1* adapter) {
            auto duplicator = std::make_unique<DesktopDuplicator>();
            const HRESULT opened = duplicator->open(adapter, monitor);
            if (opened == DXGI_ERROR_NOT_FOUND) return false;
            // The lock screen or a UAC prompt holds the desktop. It comes back without any other route.
            locked = opened == E_ACCESSDENIED;
            return !locked && start(std::move(duplicator), opened, adapter, problems);
        };
        const auto captureOn = [&](IDXGIAdapter1* adapter) {
            auto capture = std::make_unique<GraphicsCapture>();
            const HRESULT opened = capture->open(adapter, monitor);
            return start(std::move(capture), opened, adapter, problems);
        };

        IDXGIAdapter1* picked = nullptr;
        for (const auto& adapter : adapters) {
            if (picked == nullptr && adapterName(adapter.Get()) == preferredGpu_) picked = adapter.Get();
        }
        if (picked != nullptr && (duplicateOn(picked) || (!locked && captureOn(picked)))) return true;

        for (const auto& adapter : adapters) {
            if (locked) break;
            if (adapter.Get() != picked && duplicateOn(adapter.Get())) return true;
        }
        for (const auto& adapter : adapters) {
            if (locked) break;
            if (adapter.Get() != picked && captureOn(adapter.Get())) return true;
        }
        if (locked) status_ = "the desktop is locked";
        else status_ = problems.empty() ? "no GPU can reach this display" : "no capture worked: " + problems;
        return false;
    }

    /** Puts a whole chain together on [capture]. On failure it adds the reason to [problems]. */
    bool start(std::unique_ptr<DesktopCapture> capture, HRESULT opened, IDXGIAdapter1* adapter, std::string& problems) {
        const std::string route = std::string(capture->name()) + " on " + adapterName(adapter);
        const auto note = [&](const char* step, HRESULT result) {
            if (!problems.empty()) problems += "; ";
            problems += route + ": " + step + " " + hex(result);
            return false;
        };
        if (FAILED(opened)) return note("capture", opened);

        // A display turned sideways arrives unrotated.
        const DXGI_MODE_ROTATION rotation = capture->rotation();
        const bool sideways = rotation == DXGI_MODE_ROTATION_ROTATE90 || rotation == DXGI_MODE_ROTATION_ROTATE270;
        const UINT desktopWidth = sideways ? capture->height() : capture->width();
        const UINT desktopHeight = sideways ? capture->width() : capture->height();
        fitInto(desktopWidth, desktopHeight, maxEdge_, width_, height_);

        auto converter = std::make_unique<FrameConverter>();
        HRESULT result = converter->open(
            capture->device(),
            capture->context(),
            capture->image(),
            rotation,
            width_,
            height_,
            encoding() ? DXGI_FORMAT_NV12 : DXGI_FORMAT_B8G8R8A8_UNORM);
        if (FAILED(result)) return note("video processor", result);

        std::unique_ptr<H264Encoder> encoder;
        std::unique_ptr<PixelReader> reader;
        if (encoding()) {
            encoder = std::make_unique<H264Encoder>();
            result = encoder->open(capture->device(), capture->adapter(), width_, height_, framesPerSecond_, bitrate_);
            if (FAILED(result)) return note("hardware encoder", result);
        } else {
            reader = std::make_unique<PixelReader>();
            result = reader->open(capture->device(), capture->context(), width_, height_);
            if (FAILED(result)) return note("pixel reader", result);
        }

        capture_ = std::move(capture);
        converter_ = std::move(converter);
        encoder_ = std::move(encoder);
        reader_ = std::move(reader);
        hasImage_ = false;
        wantKeyframe_ = true;
        status_ = encoder_ ? route + ", " + toUtf8(encoder_->name()) : route;
        return true;
    }

    void teardown() {
        encoder_.reset();
        reader_.reset();
        converter_.reset();
        capture_.reset();
        hasImage_ = false;
    }

    /** Sleeps until the next frame is due, on a high resolution timer, so a 60 fps cap costs no spinning. */
    void waitForFrameTime() {
        LARGE_INTEGER now{};
        QueryPerformanceCounter(&now);
        if (now.QuadPart >= nextFrameAt_) return;
        const LONGLONG waitHns = (nextFrameAt_ - now.QuadPart) * 10'000'000LL / ticksPerSecond_;
        if (timer_ != nullptr) {
            LARGE_INTEGER relative{};
            relative.QuadPart = -waitHns;
            if (SetWaitableTimer(timer_, &relative, 0, nullptr, nullptr, FALSE)) {
                WaitForSingleObject(timer_, static_cast<DWORD>(waitHns / 10'000) + 1);
                return;
            }
        }
        Sleep(MS_PER_SECOND / framesPerSecond_ / 2);
    }

    static std::string hex(HRESULT value) {
        char text[16];
        snprintf(text, sizeof(text), "0x%08lX", static_cast<unsigned long>(value));
        return text;
    }

    int displayIndex_;
    UINT maxEdge_;
    UINT framesPerSecond_;
    UINT bitrate_;
    std::string preferredGpu_;
    LONGLONG ticksPerSecond_ = 1;
    LONGLONG frameTicks_;
    LONGLONG nextFrameAt_ = 0;
    HANDLE timer_;
    bool comReady_ = false;
    bool mediaReady_ = false;
    bool wantKeyframe_ = true;
    bool hasImage_ = false;
    int settle_ = 0;
    UINT width_ = 0;
    UINT height_ = 0;
    std::vector<uint8_t> bytes_;
    std::string status_ = "starting";
    std::unique_ptr<DesktopCapture> capture_;
    std::unique_ptr<FrameConverter> converter_;
    std::unique_ptr<H264Encoder> encoder_;
    std::unique_ptr<PixelReader> reader_;
};

Session* sessionOf(jlong handle) {
    return reinterpret_cast<Session*>(static_cast<intptr_t>(handle));
}

std::string toString(JNIEnv* env, jstring text) {
    if (text == nullptr) return {};
    const char* characters = env->GetStringUTFChars(text, nullptr);
    if (characters == nullptr) return {};
    std::string copy(characters);
    env->ReleaseStringUTFChars(text, characters);
    return copy;
}

/** The GPUs with a hardware encoder. One name once: two of the same card cannot be told apart. */
std::vector<std::string> encoderGpus() {
    std::vector<std::string> names;
    const HRESULT com = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    if (SUCCEEDED(MFStartup(MF_VERSION, MFSTARTUP_LITE))) {
        for (const auto& adapter : hardwareAdapters()) {
            DXGI_ADAPTER_DESC1 description{};
            if (FAILED(adapter->GetDesc1(&description)) || !H264Encoder::availableOn(description.AdapterLuid)) continue;
            const std::string name = toUtf8(description.Description);
            if (std::find(names.begin(), names.end(), name) == names.end()) names.push_back(name);
        }
        MFShutdown();
    }
    if (SUCCEEDED(com)) CoUninitialize();
    return names;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_com_remotecontroller_host_screencast_ScreencastNative_nativeOpen(
    JNIEnv* env, jclass, jint displayIndex, jint maxEdge, jint framesPerSecond, jint bitrate, jstring preferredGpu) {
    if (maxEdge <= 0 || framesPerSecond <= 0 || bitrate <= 0) return 0;
    auto* session = new (std::nothrow) Session(
        displayIndex,
        static_cast<UINT>(maxEdge),
        static_cast<UINT>(framesPerSecond),
        static_cast<UINT>(bitrate),
        toString(env, preferredGpu));
    return static_cast<jlong>(reinterpret_cast<intptr_t>(session));
}

JNIEXPORT jobjectArray JNICALL Java_com_remotecontroller_host_screencast_ScreencastNative_nativeEncoderGpus(
    JNIEnv* env, jclass) {
    const auto names = encoderGpus();
    jobjectArray array = env->NewObjectArray(static_cast<jsize>(names.size()), env->FindClass("java/lang/String"), nullptr);
    if (array == nullptr) return nullptr;
    for (size_t index = 0; index < names.size(); ++index) {
        env->SetObjectArrayElement(array, static_cast<jsize>(index), env->NewStringUTF(names[index].c_str()));
    }
    return array;
}

JNIEXPORT jlong JNICALL Java_com_remotecontroller_host_screencast_ScreencastNative_nativeOpenPixels(
    JNIEnv*, jclass, jint displayIndex, jint maxEdge, jint framesPerSecond) {
    if (maxEdge <= 0 || framesPerSecond <= 0) return 0;
    auto* session = new (std::nothrow) Session(
        displayIndex,
        static_cast<UINT>(maxEdge),
        static_cast<UINT>(framesPerSecond),
        0,
        std::string());
    return static_cast<jlong>(reinterpret_cast<intptr_t>(session));
}

JNIEXPORT jint JNICALL Java_com_remotecontroller_host_screencast_ScreencastNative_nativeNext(
    JNIEnv* env, jclass, jlong handle, jobject output, jboolean keyframe, jint bitrate) {
    Session* session = sessionOf(handle);
    auto* buffer = static_cast<uint8_t*>(env->GetDirectBufferAddress(output));
    const jlong capacity = env->GetDirectBufferCapacity(output);
    if (session == nullptr || buffer == nullptr || capacity < static_cast<jlong>(HEADER_BYTES) || bitrate < 0) {
        return NEXT_RETRY;
    }
    return session->next(buffer, static_cast<size_t>(capacity), keyframe == JNI_TRUE, static_cast<UINT>(bitrate));
}

JNIEXPORT jstring JNICALL Java_com_remotecontroller_host_screencast_ScreencastNative_nativeStatus(
    JNIEnv* env, jclass, jlong handle) {
    Session* session = sessionOf(handle);
    return env->NewStringUTF(session == nullptr ? "closed" : session->status().c_str());
}

JNIEXPORT void JNICALL Java_com_remotecontroller_host_screencast_ScreencastNative_nativeClose(
    JNIEnv*, jclass, jlong handle) {
    delete sessionOf(handle);
}

}  // extern "C"
