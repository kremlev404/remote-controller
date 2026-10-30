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
#include "H264Encoder.h"

namespace {

// Keep in step with ScreencastNative.kt.
constexpr jint NEXT_UNCHANGED = 0;
constexpr jint NEXT_RETRY = -1;
constexpr jint NEXT_TOO_LARGE = -2;
constexpr size_t HEADER_BYTES = 16;
constexpr jint FLAG_KEYFRAME = 1;

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

/** The capture → convert → encode chain. It belongs to one thread, so it takes no locks. */
class Session {
public:
    Session(int displayIndex, UINT maxEdge, UINT framesPerSecond, UINT bitrate)
        : displayIndex_(displayIndex),
          maxEdge_(maxEdge),
          framesPerSecond_(framesPerSecond),
          bitrate_(bitrate),
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
        bitrate_ = bitrate;
        if (!encoder_ && !bringUp()) {
            Sleep(RETRY_WAIT_MS);
            return NEXT_RETRY;
        }
        encoder_->setBitrate(bitrate_);

        waitForFrameTime();
        const bool repeat = hasImage_ && (wantKeyframe_ || settle_ > 0);
        const auto grab = duplicator_->next(repeat ? 0 : IDLE_WAIT_MS);
        if (grab == DesktopDuplicator::Grab::Lost) {
            teardown();
            wantKeyframe_ = true;
            return NEXT_RETRY;
        }
        if (grab == DesktopDuplicator::Grab::Frame) {
            settle_ = SETTLE_FRAMES;
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
    bool bringUp() {
        if (!comReady_ || !mediaReady_) {
            status_ = "Media Foundation is not available";
            return false;
        }
        auto duplicator = std::make_unique<DesktopDuplicator>();
        HRESULT result = duplicator->open(displayIndex_);
        if (FAILED(result)) {
            status_ = "desktop duplication failed: " + hex(result);
            return false;
        }

        // A display turned sideways arrives unrotated.
        const DXGI_MODE_ROTATION rotation = duplicator->rotation();
        const bool sideways = rotation == DXGI_MODE_ROTATION_ROTATE90 || rotation == DXGI_MODE_ROTATION_ROTATE270;
        const UINT desktopWidth = sideways ? duplicator->height() : duplicator->width();
        const UINT desktopHeight = sideways ? duplicator->width() : duplicator->height();
        fitInto(desktopWidth, desktopHeight, maxEdge_, width_, height_);

        auto converter = std::make_unique<FrameConverter>();
        result = converter->open(
            duplicator->device(), duplicator->context(), duplicator->image(), rotation, width_, height_);
        if (FAILED(result)) {
            status_ = "video processor failed: " + hex(result);
            return false;
        }

        auto encoder = std::make_unique<H264Encoder>();
        result = encoder->open(duplicator->device(), duplicator->adapter(), width_, height_, framesPerSecond_, bitrate_);
        if (FAILED(result)) {
            status_ = "hardware encoder failed: " + hex(result);
            return false;
        }

        duplicator_ = std::move(duplicator);
        converter_ = std::move(converter);
        encoder_ = std::move(encoder);
        hasImage_ = false;
        wantKeyframe_ = true;
        status_ = "ready";
        return true;
    }

    void teardown() {
        encoder_.reset();
        converter_.reset();
        duplicator_.reset();
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
    std::unique_ptr<DesktopDuplicator> duplicator_;
    std::unique_ptr<FrameConverter> converter_;
    std::unique_ptr<H264Encoder> encoder_;
};

Session* sessionOf(jlong handle) {
    return reinterpret_cast<Session*>(static_cast<intptr_t>(handle));
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_com_remotecontroller_host_screencast_ScreencastNative_nativeOpen(
    JNIEnv*, jclass, jint displayIndex, jint maxEdge, jint framesPerSecond, jint bitrate) {
    if (maxEdge <= 0 || framesPerSecond <= 0 || bitrate <= 0) return 0;
    auto* session = new (std::nothrow) Session(
        displayIndex,
        static_cast<UINT>(maxEdge),
        static_cast<UINT>(framesPerSecond),
        static_cast<UINT>(bitrate));
    return static_cast<jlong>(reinterpret_cast<intptr_t>(session));
}

JNIEXPORT jint JNICALL Java_com_remotecontroller_host_screencast_ScreencastNative_nativeNext(
    JNIEnv* env, jclass, jlong handle, jobject output, jboolean keyframe, jint bitrate) {
    Session* session = sessionOf(handle);
    auto* buffer = static_cast<uint8_t*>(env->GetDirectBufferAddress(output));
    const jlong capacity = env->GetDirectBufferCapacity(output);
    if (session == nullptr || buffer == nullptr || capacity < static_cast<jlong>(HEADER_BYTES) || bitrate <= 0) {
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
