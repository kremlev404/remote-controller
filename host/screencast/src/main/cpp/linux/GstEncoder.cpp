#include "GstEncoder.h"

#include <dlfcn.h>

#include <algorithm>
#include <cstring>
#include <mutex>

namespace {

constexpr int STATE_NULL = 1;
constexpr int STATE_PLAYING = 4;
constexpr int STATE_CHANGE_FAILURE = 0;
constexpr int FLOW_OK = 0;
constexpr int MAP_READ = 1;
constexpr int MESSAGE_ERROR = 1 << 1;
constexpr uint64_t CLOCK_TIME_NONE = ~0ULL;
constexpr uint64_t NANOS_PER_MS = 1000000ULL;
constexpr uint32_t WARM_UP_WAIT_MS = 300;
constexpr int WARM_UP_FRAMES = 5;
constexpr uint32_t KEYFRAME_INTERVAL_SECONDS = 600;
constexpr uint8_t NV12_BLACK_LUMA = 16;
constexpr uint8_t NV12_BLACK_CHROMA = 128;
constexpr uint8_t NAL_TYPE_MASK = 0x1F;
constexpr uint8_t NAL_SLICE = 1;
constexpr uint8_t NAL_IDR = 5;
constexpr uint8_t NAL_SPS = 7;

struct GError {
    uint32_t domain;
    int code;
    char* message;
};

/** GStreamer's own struct. Its layout is part of the 1.x ABI. */
struct GstMapInfo {
    void* memory;
    int flags;
    uint8_t* data;
    size_t size;
    size_t maxsize;
    void* userData[4];
    void* reserved[4];
};

/** Only what this file calls. Everything is looked up at run time: no build needs the GStreamer headers. */
struct Gst {
    int (*init_check)(int*, char***, GError**);
    void* (*parse_launch)(const char*, GError**);
    void* (*element_factory_find)(const char*);
    int (*element_set_state)(void*, int);
    int (*element_send_event)(void*, void*);
    void* (*element_get_bus)(void*);
    void* (*bin_get_by_name)(void*, const char*);
    void (*object_unref)(void*);
    void (*mini_object_unref)(void*);
    void* (*buffer_new_allocate)(void*, size_t, void*);
    size_t (*buffer_fill)(void*, size_t, const void*, size_t);
    int (*buffer_map)(void*, GstMapInfo*, int);
    void (*buffer_unmap)(void*, GstMapInfo*);
    void* (*sample_get_buffer)(void*);
    void* (*bus_pop_filtered)(void*, int);
    void (*message_parse_error)(void*, GError**, char**);
    int (*app_src_push_buffer)(void*, void*);
    void* (*app_sink_try_pull_sample)(void*, uint64_t);
    void* (*video_event_new_upstream_force_key_unit)(uint64_t, int, unsigned);
    void (*object_set)(void*, const char*, ...);
    void (*error_free)(GError*);
    void (*free_memory)(void*);
};

struct Candidate {
    const char* element;
    /** Set after the element's name: a low latency, single pass setup with keyframes on demand. */
    const char* options;
    const char* keyframeInterval;
    /** Most encoders take kbit/s. */
    uint32_t bitrateUnit;
    /** Encoders that do not take NV12 need a conversion in front. */
    bool convert;
};

// Hardware first. The software ones are real H.264, so they still beat every other route for the viewer.
constexpr Candidate CANDIDATES[] = {
    {"vah264lpenc", "rate-control=cbr b-frames=0 ref-frames=1", "key-int-max", 1000, false},
    {"vah264enc", "rate-control=cbr b-frames=0 ref-frames=1", "key-int-max", 1000, false},
    {"vaapih264enc", "rate-control=cbr max-bframes=0 refs=1", "keyframe-period", 1000, false},
    {"nvh264enc", "preset=low-latency-hq rc-mode=cbr bframes=0 zerolatency=true", "gop-size", 1000, false},
    {"qsvh264enc", "low-latency=true b-frames=0", "gop-size", 1000, false},
    {"v4l2h264enc", "", nullptr, 0, false},
    {"x264enc", "tune=zerolatency speed-preset=ultrafast bframes=0 threads=0", "key-int-max", 1000, false},
    {"openh264enc", "complexity=low usage-type=screen rate-control=bitrate", "gop-size", 1, true},
};

template <typename Function>
bool bind(void* library, const char* symbol, Function& function) {
    function = reinterpret_cast<Function>(dlsym(library, symbol));
    return function != nullptr;
}

bool bindAll(Gst& gst) {
    void* core = dlopen("libgstreamer-1.0.so.0", RTLD_NOW | RTLD_GLOBAL);
    void* app = dlopen("libgstapp-1.0.so.0", RTLD_NOW);
    void* video = dlopen("libgstvideo-1.0.so.0", RTLD_NOW);
    void* gobject = dlopen("libgobject-2.0.so.0", RTLD_NOW);
    void* glib = dlopen("libglib-2.0.so.0", RTLD_NOW);
    if (core == nullptr || app == nullptr || video == nullptr || gobject == nullptr || glib == nullptr) return false;
    return bind(core, "gst_init_check", gst.init_check) &&
        bind(core, "gst_parse_launch", gst.parse_launch) &&
        bind(core, "gst_element_factory_find", gst.element_factory_find) &&
        bind(core, "gst_element_set_state", gst.element_set_state) &&
        bind(core, "gst_element_send_event", gst.element_send_event) &&
        bind(core, "gst_element_get_bus", gst.element_get_bus) &&
        bind(core, "gst_bin_get_by_name", gst.bin_get_by_name) &&
        bind(core, "gst_object_unref", gst.object_unref) &&
        bind(core, "gst_mini_object_unref", gst.mini_object_unref) &&
        bind(core, "gst_buffer_new_allocate", gst.buffer_new_allocate) &&
        bind(core, "gst_buffer_fill", gst.buffer_fill) &&
        bind(core, "gst_buffer_map", gst.buffer_map) &&
        bind(core, "gst_buffer_unmap", gst.buffer_unmap) &&
        bind(core, "gst_sample_get_buffer", gst.sample_get_buffer) &&
        bind(core, "gst_bus_pop_filtered", gst.bus_pop_filtered) &&
        bind(core, "gst_message_parse_error", gst.message_parse_error) &&
        bind(app, "gst_app_src_push_buffer", gst.app_src_push_buffer) &&
        bind(app, "gst_app_sink_try_pull_sample", gst.app_sink_try_pull_sample) &&
        bind(video, "gst_video_event_new_upstream_force_key_unit", gst.video_event_new_upstream_force_key_unit) &&
        bind(gobject, "g_object_set", gst.object_set) &&
        bind(glib, "g_error_free", gst.error_free) &&
        bind(glib, "g_free", gst.free_memory);
}

/** Null when GStreamer is not on this PC. The library stays loaded for the life of the app. */
const Gst* gstreamer() {
    static Gst gst{};
    static bool ready = false;
    static std::once_flag once;
    std::call_once(once, [] {
        if (!bindAll(gst)) return;
        ready = gst.init_check(nullptr, nullptr, nullptr) != 0;
    });
    return ready ? &gst : nullptr;
}

size_t startCodeAt(const std::vector<uint8_t>& bytes, size_t from) {
    for (size_t index = from; index + 3 < bytes.size(); ++index) {
        if (bytes[index] == 0 && bytes[index + 1] == 0 && bytes[index + 2] == 1) return index;
    }
    return bytes.size();
}

/** Stops at the first slice: everything that matters here comes before it. */
void readHeaders(const std::vector<uint8_t>& bytes, std::vector<uint8_t>& sequenceHeader, bool& keyframe, bool& hasHeader) {
    keyframe = false;
    hasHeader = false;
    size_t headerStart = bytes.size();
    for (size_t start = startCodeAt(bytes, 0); start < bytes.size(); start = startCodeAt(bytes, start + 3)) {
        const uint8_t type = bytes[start + 3] & NAL_TYPE_MASK;
        if (type == NAL_SPS && !hasHeader) {
            hasHeader = true;
            headerStart = start > 0 && bytes[start - 1] == 0 ? start - 1 : start;
        }
        if (type == NAL_SLICE || type == NAL_IDR) {
            keyframe = type == NAL_IDR;
            if (hasHeader) sequenceHeader.assign(bytes.begin() + static_cast<std::ptrdiff_t>(headerStart), bytes.begin() + static_cast<std::ptrdiff_t>(start));
            return;
        }
    }
}

}  // namespace

GstEncoder::~GstEncoder() {
    close();
}

bool GstEncoder::open(uint32_t width, uint32_t height, uint32_t framesPerSecond, uint32_t bitrate) {
    if (gstreamer() == nullptr) {
        problem_ = "GStreamer is not installed";
        return false;
    }
    problem_ = "GStreamer has no H.264 encoder: install gstreamer1.0-plugins-ugly or the VA-API plugins";
    for (size_t candidate = 0; candidate < std::size(CANDIDATES); ++candidate) {
        if (tryEncoder(candidate, width, height, framesPerSecond, bitrate)) return true;
        close();
    }
    return false;
}

bool GstEncoder::tryEncoder(size_t candidate, uint32_t width, uint32_t height, uint32_t framesPerSecond, uint32_t bitrate) {
    const Gst& gst = *gstreamer();
    const Candidate& encoder = CANDIDATES[candidate];
    void* factory = gst.element_factory_find(encoder.element);
    if (factory == nullptr) return false;
    gst.object_unref(factory);

    std::string description = "appsrc name=source is-live=true format=time do-timestamp=true max-bytes=0 caps=\"video/x-raw,format=NV12,width=";
    description += std::to_string(width) + ",height=" + std::to_string(height);
    description += ",framerate=" + std::to_string(framesPerSecond) + "/1,colorimetry=bt709\" ! ";
    if (encoder.convert) description += "videoconvert ! ";
    description += std::string(encoder.element) + " name=encoder " + encoder.options;
    if (encoder.bitrateUnit != 0) description += " bitrate=" + std::to_string(bitrate / encoder.bitrateUnit);
    // Keyframes are costly and a socket does not lose frames: they come when a viewer needs one.
    if (encoder.keyframeInterval != nullptr) {
        description += std::string(" ") + encoder.keyframeInterval + "=" + std::to_string(framesPerSecond * KEYFRAME_INTERVAL_SECONDS);
    }
    description += " ! video/x-h264,stream-format=byte-stream,alignment=au ! appsink name=sink sync=false max-buffers=8";

    GError* error = nullptr;
    pipeline_ = gst.parse_launch(description.c_str(), &error);
    if (error != nullptr) {
        // A property this GStreamer does not have comes back as an error next to a working pipeline.
        problem_ = std::string(encoder.element) + ": " + error->message;
        gst.error_free(error);
    }
    if (pipeline_ == nullptr) return false;

    source_ = gst.bin_get_by_name(pipeline_, "source");
    sink_ = gst.bin_get_by_name(pipeline_, "sink");
    encoder_ = gst.bin_get_by_name(pipeline_, "encoder");
    bus_ = gst.element_get_bus(pipeline_);
    if (source_ == nullptr || sink_ == nullptr || encoder_ == nullptr || bus_ == nullptr) return false;

    if (gst.element_set_state(pipeline_, STATE_PLAYING) == STATE_CHANGE_FAILURE) return false;
    candidate_ = candidate;
    name_ = encoder.element;
    return warmUp(width, height);
}

/** An element can exist with no driver under it. Only real output proves this one works. */
bool GstEncoder::warmUp(uint32_t width, uint32_t height) {
    const uint32_t stride = strideOf(width);
    std::vector<uint8_t> black(static_cast<size_t>(stride) * height * 3 / 2, NV12_BLACK_CHROMA);
    std::fill_n(black.begin(), static_cast<size_t>(stride) * height, NV12_BLACK_LUMA);
    std::vector<uint8_t> output;
    bool keyframe = false;
    bool failed = false;
    for (int frame = 0; frame < WARM_UP_FRAMES; ++frame) {
        if (!push(black.data(), black.size(), false)) return false;
        if (pull(output, keyframe, failed, WARM_UP_WAIT_MS)) return true;
        if (failed) return false;
    }
    problem_ = name_ + " gave no output";
    return false;
}

bool GstEncoder::push(const uint8_t* nv12, size_t size, bool keyframe) {
    const Gst& gst = *gstreamer();
    if (keyframe) {
        gst.element_send_event(encoder_, gst.video_event_new_upstream_force_key_unit(CLOCK_TIME_NONE, 1, 0));
    }
    void* buffer = gst.buffer_new_allocate(nullptr, size, nullptr);
    if (buffer == nullptr) return false;
    gst.buffer_fill(buffer, 0, nv12, size);
    return gst.app_src_push_buffer(source_, buffer) == FLOW_OK;
}

bool GstEncoder::pull(std::vector<uint8_t>& output, bool& keyframe, bool& failed, uint32_t waitMs) {
    const Gst& gst = *gstreamer();
    void* sample = gst.app_sink_try_pull_sample(sink_, waitMs * NANOS_PER_MS);
    if (sample == nullptr) {
        failed = busFailed();
        return false;
    }
    GstMapInfo map{};
    void* buffer = gst.sample_get_buffer(sample);
    const bool mapped = buffer != nullptr && gst.buffer_map(buffer, &map, MAP_READ) != 0;
    if (mapped) {
        output.assign(map.data, map.data + map.size);
        gst.buffer_unmap(buffer, &map);
    }
    gst.mini_object_unref(sample);
    if (!mapped) return false;

    bool hasHeader = false;
    readHeaders(output, sequenceHeader_, keyframe, hasHeader);
    // A viewer starts from a keyframe: it has to carry the sequence header in the stream.
    if (keyframe && !hasHeader && !sequenceHeader_.empty()) {
        output.insert(output.begin(), sequenceHeader_.begin(), sequenceHeader_.end());
    }
    return true;
}

void GstEncoder::setBitrate(uint32_t bitrate) {
    const uint32_t unit = CANDIDATES[candidate_].bitrateUnit;
    if (encoder_ == nullptr || unit == 0) return;
    gstreamer()->object_set(encoder_, "bitrate", bitrate / unit, nullptr);
}

bool GstEncoder::busFailed() {
    const Gst& gst = *gstreamer();
    void* message = bus_ == nullptr ? nullptr : gst.bus_pop_filtered(bus_, MESSAGE_ERROR);
    if (message == nullptr) return false;
    GError* error = nullptr;
    char* debug = nullptr;
    gst.message_parse_error(message, &error, &debug);
    if (error != nullptr) {
        problem_ = name_ + ": " + error->message;
        gst.error_free(error);
    }
    gst.free_memory(debug);
    gst.mini_object_unref(message);
    return true;
}

void GstEncoder::close() {
    const Gst* gst = gstreamer();
    if (gst == nullptr) return;
    if (pipeline_ != nullptr) gst->element_set_state(pipeline_, STATE_NULL);
    for (void** object : {&source_, &sink_, &encoder_, &bus_, &pipeline_}) {
        if (*object != nullptr) gst->object_unref(*object);
        *object = nullptr;
    }
    sequenceHeader_.clear();
}
