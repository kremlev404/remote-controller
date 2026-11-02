#pragma once

#include <jni.h>

#include <chrono>
#include <cstddef>
#include <cstdint>
#include <memory>
#include <string>

// Keep in step with ScreencastNative.kt.
constexpr jint NEXT_UNCHANGED = 0;
constexpr jint NEXT_RETRY = -1;
constexpr jint NEXT_TOO_LARGE = -2;
constexpr jint NEXT_FAILED = -3;
constexpr jint NEXT_NEEDS_ACCESS = -4;
constexpr size_t HEADER_BYTES = 16;
constexpr jint FLAG_KEYFRAME = 1;
constexpr jint FLAG_PIXELS = 2;

/** Even, and never larger than the desktop: encoders work on whole chroma samples. */
void fitInto(uint32_t sourceWidth, uint32_t sourceHeight, uint32_t maxEdge, uint32_t& width, uint32_t& height);

/** Writes the header ScreencastNative.kt reads in front of every picture. */
void writeHeader(uint8_t* buffer, uint32_t width, uint32_t height, jint flags, uint32_t stride);

/**
 * One display, paced to one frame rate, belonging to one thread. It hands out either H.264 or, with
 * no bitrate, the plain pixels of the changed screen. The platforms fill in the steps; the rules of
 * when a frame goes out are the same as in the Windows session.
 */
class Session {
public:
    Session(int displayIndex, uint32_t maxEdge, uint32_t framesPerSecond, uint32_t bitrate);
    virtual ~Session() = default;

    Session(const Session&) = delete;
    Session& operator=(const Session&) = delete;

    jint next(uint8_t* buffer, size_t capacity, bool keyframe, uint32_t bitrate);

    const std::string& status() const { return status_; }

protected:
    enum class Grab { Frame, Unchanged, Lost };

    /** False leaves a reason in status_ and the code for it in failure_. */
    virtual bool bringUp() = 0;
    virtual void tearDown() = 0;
    /** The next picture, waiting up to [waitMs]. The last one stays usable after Unchanged. */
    virtual Grab grab(uint32_t waitMs) = 0;
    /** Bytes written after the header, NEXT_UNCHANGED while the encoder holds them, or a negative code. */
    virtual jint produce(uint8_t* buffer, size_t capacity, bool keyframe) = 0;
    virtual void applyBitrate(uint32_t bitrate) = 0;

    bool encoding() const { return bitrate_ != 0; }

    int displayIndex_;
    uint32_t maxEdge_;
    uint32_t framesPerSecond_;
    uint32_t bitrate_;
    jint failure_ = NEXT_RETRY;
    std::string status_ = "starting";

private:
    void waitForFrameTime();

    using Clock = std::chrono::steady_clock;

    Clock::duration frameTime_;
    Clock::time_point nextFrameAt_{};
    bool running_ = false;
    bool wantKeyframe_ = true;
    bool hasImage_ = false;
    int settle_ = 0;
};

std::unique_ptr<Session> openSession(int displayIndex, uint32_t maxEdge, uint32_t framesPerSecond, uint32_t bitrate);
