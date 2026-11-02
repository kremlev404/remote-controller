#include "Session.h"

#include <algorithm>
#include <cstring>
#include <new>
#include <thread>

namespace {

constexpr uint32_t IDLE_WAIT_MS = 50;
constexpr uint32_t RETRY_WAIT_MS = 250;
constexpr int SETTLE_FRAMES = 8;

Session* sessionOf(jlong handle) {
    return reinterpret_cast<Session*>(static_cast<intptr_t>(handle));
}

jlong open(jint displayIndex, jint maxEdge, jint framesPerSecond, jint bitrate) {
    auto session = openSession(
        displayIndex,
        static_cast<uint32_t>(maxEdge),
        static_cast<uint32_t>(framesPerSecond),
        static_cast<uint32_t>(bitrate));
    return static_cast<jlong>(reinterpret_cast<intptr_t>(session.release()));
}

}  // namespace

void fitInto(uint32_t sourceWidth, uint32_t sourceHeight, uint32_t maxEdge, uint32_t& width, uint32_t& height) {
    const uint32_t longEdge = std::max(sourceWidth, sourceHeight);
    const double scale = longEdge > maxEdge ? static_cast<double>(maxEdge) / longEdge : 1.0;
    width = std::max(2U, static_cast<uint32_t>(sourceWidth * scale + 0.5) & ~1U);
    height = std::max(2U, static_cast<uint32_t>(sourceHeight * scale + 0.5) & ~1U);
}

void writeHeader(uint8_t* buffer, uint32_t width, uint32_t height, jint flags, uint32_t stride) {
    const jint header[] = {
        static_cast<jint>(width),
        static_cast<jint>(height),
        flags,
        static_cast<jint>(stride),
    };
    std::memcpy(buffer, header, HEADER_BYTES);
}

Session::Session(int displayIndex, uint32_t maxEdge, uint32_t framesPerSecond, uint32_t bitrate)
    : displayIndex_(displayIndex),
      maxEdge_(maxEdge),
      framesPerSecond_(framesPerSecond),
      bitrate_(bitrate),
      frameTime_(std::chrono::duration_cast<Clock::duration>(std::chrono::seconds(1)) / framesPerSecond) {}

jint Session::next(uint8_t* buffer, size_t capacity, bool keyframe, uint32_t bitrate) {
    wantKeyframe_ = wantKeyframe_ || keyframe;
    if (!running_) {
        running_ = bringUp();
        if (!running_) {
            std::this_thread::sleep_for(std::chrono::milliseconds(RETRY_WAIT_MS));
            return failure_;
        }
        hasImage_ = false;
        wantKeyframe_ = true;
    }
    if (encoding() && bitrate != 0 && bitrate != bitrate_) {
        bitrate_ = bitrate;
        applyBitrate(bitrate);
    }

    waitForFrameTime();
    const bool repeat = hasImage_ && (wantKeyframe_ || settle_ > 0);
    const Grab result = grab(repeat ? 0 : IDLE_WAIT_MS);
    if (result == Grab::Lost) {
        tearDown();
        running_ = false;
        wantKeyframe_ = true;
        return NEXT_RETRY;
    }
    if (result == Grab::Frame) {
        settle_ = encoding() ? SETTLE_FRAMES : 0;
        hasImage_ = true;
    } else if (!repeat) {
        return NEXT_UNCHANGED;
    } else if (settle_ > 0) {
        // The last picture again: each pass sharpens what the motion left blurred.
        --settle_;
    }

    // A fixed grid: a thread that wakes late must not push every later frame back.
    const auto now = Clock::now();
    nextFrameAt_ = now - nextFrameAt_ > frameTime_ ? now + frameTime_ : nextFrameAt_ + frameTime_;

    const jint produced = produce(buffer, capacity, wantKeyframe_);
    if (produced == NEXT_TOO_LARGE) {
        wantKeyframe_ = true;
        return produced;
    }
    if (produced < 0) {
        tearDown();
        running_ = false;
        wantKeyframe_ = true;
        return produced;
    }
    wantKeyframe_ = false;
    return produced;
}

void Session::waitForFrameTime() {
    if (Clock::now() < nextFrameAt_) std::this_thread::sleep_until(nextFrameAt_);
}

extern "C" {

JNIEXPORT jlong JNICALL Java_com_remotecontroller_host_screencast_ScreencastNative_nativeOpen(
    JNIEnv*, jclass, jint displayIndex, jint maxEdge, jint framesPerSecond, jint bitrate, jstring) {
    if (maxEdge <= 0 || framesPerSecond <= 0 || bitrate <= 0) return 0;
    return open(displayIndex, maxEdge, framesPerSecond, bitrate);
}

/** Only Windows lets the app choose: macOS and GStreamer pick the encoder themselves. */
JNIEXPORT jobjectArray JNICALL Java_com_remotecontroller_host_screencast_ScreencastNative_nativeEncoderGpus(
    JNIEnv* env, jclass) {
    return env->NewObjectArray(0, env->FindClass("java/lang/String"), nullptr);
}

JNIEXPORT jlong JNICALL Java_com_remotecontroller_host_screencast_ScreencastNative_nativeOpenPixels(
    JNIEnv*, jclass, jint displayIndex, jint maxEdge, jint framesPerSecond) {
    if (maxEdge <= 0 || framesPerSecond <= 0) return 0;
    return open(displayIndex, maxEdge, framesPerSecond, 0);
}

JNIEXPORT jint JNICALL Java_com_remotecontroller_host_screencast_ScreencastNative_nativeNext(
    JNIEnv* env, jclass, jlong handle, jobject output, jboolean keyframe, jint bitrate) {
    Session* session = sessionOf(handle);
    auto* buffer = static_cast<uint8_t*>(env->GetDirectBufferAddress(output));
    const jlong capacity = env->GetDirectBufferCapacity(output);
    if (session == nullptr || buffer == nullptr || capacity < static_cast<jlong>(HEADER_BYTES) || bitrate < 0) {
        return NEXT_RETRY;
    }
    return session->next(buffer, static_cast<size_t>(capacity), keyframe == JNI_TRUE, static_cast<uint32_t>(bitrate));
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
