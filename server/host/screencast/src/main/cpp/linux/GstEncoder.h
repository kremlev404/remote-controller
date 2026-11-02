#pragma once

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

/**
 * H.264 through GStreamer, loaded at run time. GStreamer keeps one stable ABI, comes with most Linux
 * desktops and reaches every vendor's encoder through one call, so the app ships no codec and needs
 * no build for each distribution. Without it the stream moves to another route.
 */
class GstEncoder {
public:
    GstEncoder() = default;
    ~GstEncoder();

    GstEncoder(const GstEncoder&) = delete;
    GstEncoder& operator=(const GstEncoder&) = delete;

    /** Tries hardware encoders first and software last. False leaves the reason in problem(). */
    bool open(uint32_t width, uint32_t height, uint32_t framesPerSecond, uint32_t bitrate);

    /** Takes one NV12 picture laid out by [strideOf]. */
    bool push(const uint8_t* nv12, size_t size, bool keyframe);

    /** The next access unit. False when the encoder holds it for now, with [failed] set on a broken pipeline. */
    bool pull(std::vector<uint8_t>& output, bool& keyframe, bool& failed, uint32_t waitMs);

    void setBitrate(uint32_t bitrate);

    const std::string& name() const { return name_; }
    const std::string& problem() const { return problem_; }

    /** GStreamer's own layout for NV12 without a video meta. */
    static uint32_t strideOf(uint32_t width) { return (width + 3) & ~3U; }

private:
    bool tryEncoder(size_t candidate, uint32_t width, uint32_t height, uint32_t framesPerSecond, uint32_t bitrate);
    bool warmUp(uint32_t width, uint32_t height);
    void close();
    bool busFailed();

    void* pipeline_ = nullptr;
    void* source_ = nullptr;
    void* sink_ = nullptr;
    void* encoder_ = nullptr;
    void* bus_ = nullptr;
    size_t candidate_ = 0;
    std::string name_;
    std::string problem_;
    std::vector<uint8_t> sequenceHeader_;
};
