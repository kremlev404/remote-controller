#include <X11/Xlib.h>
#include <X11/Xutil.h>
#include <X11/extensions/XShm.h>
#include <dlfcn.h>
#include <sys/ipc.h>
#include <sys/shm.h>

#include <algorithm>
#include <chrono>
#include <cstdlib>
#include <cstring>
#include <string>
#include <thread>
#include <vector>

#include "../posix/Session.h"
#include "GstEncoder.h"

namespace {

constexpr int BYTES_PER_PIXEL = 4;
constexpr uint32_t PULL_WAIT_MS = 30;
constexpr uint32_t FIXED_ONE = 1U << 16;

/** Xinerama's struct: its header comes in a package nothing else here needs. */
struct XineramaScreen {
    int screen;
    short x;
    short y;
    short width;
    short height;
};

struct Region {
    int x = 0;
    int y = 0;
    uint32_t width = 0;
    uint32_t height = 0;
};

/** The monitor at [index] in the order AWT lists them. The whole desktop if Xinerama is not there. */
Region monitorOf(Display* display, int index) {
    Region region;
    region.width = static_cast<uint32_t>(DisplayWidth(display, DefaultScreen(display)));
    region.height = static_cast<uint32_t>(DisplayHeight(display, DefaultScreen(display)));
    void* library = dlopen("libXinerama.so.1", RTLD_NOW);
    if (library == nullptr) return region;
    using QueryScreens = XineramaScreen* (*)(Display*, int*);
    auto query = reinterpret_cast<QueryScreens>(dlsym(library, "XineramaQueryScreens"));
    int count = 0;
    XineramaScreen* screens = query != nullptr ? query(display, &count) : nullptr;
    if (screens != nullptr) {
        if (index >= 0 && index < count) {
            region.x = screens[index].x;
            region.y = screens[index].y;
            region.width = static_cast<uint32_t>(screens[index].width);
            region.height = static_cast<uint32_t>(screens[index].height);
        }
        XFree(screens);
    }
    // Stays loaded: Xinerama leaves a hook on the connection that XCloseDisplay calls later.
    return region;
}

/** Fixed point bilinear. Runs only when the desktop is larger than the stream. */
void scaleBgra(
    const uint8_t* source, size_t sourceStride, uint32_t sourceWidth, uint32_t sourceHeight,
    uint8_t* target, uint32_t targetWidth, uint32_t targetHeight) {
    const uint32_t stepX = (sourceWidth - 1) * FIXED_ONE / std::max(1U, targetWidth - 1);
    const uint32_t stepY = (sourceHeight - 1) * FIXED_ONE / std::max(1U, targetHeight - 1);
    for (uint32_t y = 0; y < targetHeight; ++y) {
        const uint32_t fixedY = y * stepY;
        const uint32_t row = std::min(fixedY >> 16, sourceHeight - 1);
        const uint32_t nextRow = std::min(row + 1, sourceHeight - 1);
        const uint32_t weightY = (fixedY >> 8) & 0xFF;
        const uint8_t* top = source + row * sourceStride;
        const uint8_t* bottom = source + nextRow * sourceStride;
        uint8_t* out = target + static_cast<size_t>(y) * targetWidth * BYTES_PER_PIXEL;
        for (uint32_t x = 0; x < targetWidth; ++x) {
            const uint32_t fixedX = x * stepX;
            const uint32_t column = std::min(fixedX >> 16, sourceWidth - 1);
            const uint32_t nextColumn = std::min(column + 1, sourceWidth - 1);
            const uint32_t weightX = (fixedX >> 8) & 0xFF;
            for (int channel = 0; channel < BYTES_PER_PIXEL; ++channel) {
                const uint32_t upper = top[column * BYTES_PER_PIXEL + channel] * (256 - weightX) +
                    top[nextColumn * BYTES_PER_PIXEL + channel] * weightX;
                const uint32_t lower = bottom[column * BYTES_PER_PIXEL + channel] * (256 - weightX) +
                    bottom[nextColumn * BYTES_PER_PIXEL + channel] * weightX;
                out[x * BYTES_PER_PIXEL + channel] = static_cast<uint8_t>((upper * (256 - weightY) + lower * weightY) >> 16);
            }
        }
    }
}

/** BT.709 studio range, the same as the Windows and macOS streams. [width] and [height] are even. */
void toNv12(const uint8_t* bgra, size_t stride, uint32_t width, uint32_t height, uint8_t* nv12) {
    const size_t outStride = GstEncoder::strideOf(width);
    uint8_t* chroma = nv12 + outStride * height;
    for (uint32_t y = 0; y < height; ++y) {
        const uint8_t* row = bgra + y * stride;
        uint8_t* luma = nv12 + y * outStride;
        for (uint32_t x = 0; x < width; ++x) {
            const int blue = row[x * BYTES_PER_PIXEL];
            const int green = row[x * BYTES_PER_PIXEL + 1];
            const int red = row[x * BYTES_PER_PIXEL + 2];
            luma[x] = static_cast<uint8_t>(((47 * red + 157 * green + 16 * blue) >> 8) + 16);
        }
    }
    for (uint32_t y = 0; y < height; y += 2) {
        const uint8_t* upper = bgra + y * stride;
        const uint8_t* lower = upper + stride;
        uint8_t* out = chroma + (y / 2) * outStride;
        for (uint32_t x = 0; x < width; x += 2) {
            const int first = x * BYTES_PER_PIXEL;
            const int second = first + BYTES_PER_PIXEL;
            const int blue = upper[first] + upper[second] + lower[first] + lower[second];
            const int green = upper[first + 1] + upper[second + 1] + lower[first + 1] + lower[second + 1];
            const int red = upper[first + 2] + upper[second + 2] + lower[first + 2] + lower[second + 2];
            out[x] = static_cast<uint8_t>(((-26 * red - 87 * green + 112 * blue) >> 10) + 128);
            out[x + 1] = static_cast<uint8_t>(((112 * red - 102 * green - 10 * blue) >> 10) + 128);
        }
    }
}

/** Shared memory capture of an X11 display. X11 has no frame-ready signal worth trusting, so it compares. */
class LinuxSession final : public Session {
public:
    using Session::Session;

    ~LinuxSession() override { tearDown(); }

protected:
    bool bringUp() override {
        failure_ = NEXT_FAILED;
        const char* sessionType = std::getenv("XDG_SESSION_TYPE");
        if (sessionType != nullptr && std::strcmp(sessionType, "wayland") == 0) {
            status_ = "Wayland hides the screen from X11 capture: log in to an X11 session";
            return false;
        }
        display_ = XOpenDisplay(nullptr);
        if (display_ == nullptr) {
            status_ = "no X11 display";
            return false;
        }
        if (XShmQueryExtension(display_) == False) {
            status_ = "this X server has no shared memory capture";
            tearDown();
            return false;
        }
        root_ = DefaultRootWindow(display_);
        region_ = monitorOf(display_, displayIndex_);
        if (!attachImage()) {
            tearDown();
            return false;
        }
        fitInto(region_.width, region_.height, maxEdge_, width_, height_);
        const size_t bytes = static_cast<size_t>(image_->bytes_per_line) * region_.height;
        last_.assign(bytes, 0);
        hasLast_ = false;
        if (width_ != region_.width || height_ != region_.height) {
            scaled_.resize(static_cast<size_t>(width_) * height_ * BYTES_PER_PIXEL);
        }
        if (encoding()) {
            encoder_ = std::make_unique<GstEncoder>();
            if (!encoder_->open(width_, height_, framesPerSecond_, bitrate_)) {
                status_ = encoder_->problem();
                tearDown();
                return false;
            }
            nv12_.resize(static_cast<size_t>(GstEncoder::strideOf(width_)) * height_ * 3 / 2);
            status_ = "ready: " + encoder_->name();
        } else {
            status_ = "ready";
        }
        return true;
    }

    void tearDown() override {
        encoder_.reset();
        if (image_ != nullptr) {
            XShmDetach(display_, &shm_);
            XDestroyImage(image_);
            image_ = nullptr;
        }
        if (shm_.shmaddr != nullptr) {
            shmdt(shm_.shmaddr);
            shm_.shmaddr = nullptr;
        }
        if (display_ != nullptr) {
            XCloseDisplay(display_);
            display_ = nullptr;
        }
    }

    Grab grab(uint32_t waitMs) override {
        const int rootWidth = DisplayWidth(display_, DefaultScreen(display_));
        const int rootHeight = DisplayHeight(display_, DefaultScreen(display_));
        Window ignored = 0;
        int x = 0;
        int y = 0;
        unsigned width = 0;
        unsigned height = 0;
        unsigned border = 0;
        unsigned depth = 0;
        // A mode change would turn the capture below into a protocol error.
        if (XGetGeometry(display_, root_, &ignored, &x, &y, &width, &height, &border, &depth) == 0 ||
            static_cast<int>(width) != rootWidth || static_cast<int>(height) != rootHeight) {
            return Grab::Lost;
        }
        if (XShmGetImage(display_, root_, image_, region_.x, region_.y, AllPlanes) == False) return Grab::Lost;

        const size_t bytes = last_.size();
        if (hasLast_ && std::memcmp(image_->data, last_.data(), bytes) == 0) {
            // Stays quick through a short pause in a video, then settles to the idle pace.
            const bool settled = ++quietPasses_ > framesPerSecond_;
            const auto wait = settled
                ? std::chrono::milliseconds(waitMs)
                : std::chrono::milliseconds(std::min<uint32_t>(waitMs, 1000 / framesPerSecond_));
            std::this_thread::sleep_for(wait);
            return Grab::Unchanged;
        }
        quietPasses_ = 0;
        std::memcpy(last_.data(), image_->data, bytes);
        hasLast_ = true;
        return Grab::Frame;
    }

    jint produce(uint8_t* buffer, size_t capacity, bool keyframe) override {
        const uint8_t* pixels = last_.data();
        size_t stride = static_cast<size_t>(image_->bytes_per_line);
        if (!scaled_.empty()) {
            scaleBgra(pixels, stride, region_.width, region_.height, scaled_.data(), width_, height_);
            pixels = scaled_.data();
            stride = static_cast<size_t>(width_) * BYTES_PER_PIXEL;
        }
        return encoding() ? encode(pixels, stride, buffer, capacity, keyframe) : copy(pixels, stride, buffer, capacity);
    }

    void applyBitrate(uint32_t bitrate) override {
        if (encoder_) encoder_->setBitrate(bitrate);
    }

private:
    bool attachImage() {
        const int screen = DefaultScreen(display_);
        shm_ = XShmSegmentInfo{};
        shm_.shmid = -1;
        image_ = XShmCreateImage(
            display_, DefaultVisual(display_, screen), static_cast<unsigned>(DefaultDepth(display_, screen)),
            ZPixmap, nullptr, &shm_, region_.width, region_.height);
        if (image_ == nullptr || image_->bits_per_pixel != BYTES_PER_PIXEL * 8) {
            status_ = "this X server does not use 32 bit pixels";
            return false;
        }
        shm_.shmid = shmget(IPC_PRIVATE, static_cast<size_t>(image_->bytes_per_line) * region_.height, IPC_CREAT | 0600);
        if (shm_.shmid < 0) {
            status_ = "no shared memory for the capture";
            return false;
        }
        shm_.shmaddr = static_cast<char*>(shmat(shm_.shmid, nullptr, 0));
        image_->data = shm_.shmaddr;
        shm_.readOnly = False;
        const bool attached = shm_.shmaddr != reinterpret_cast<char*>(-1) && XShmAttach(display_, &shm_) != False;
        if (attached) XSync(display_, False);
        // Marked for removal now, so a crash cannot leave the segment behind.
        shmctl(shm_.shmid, IPC_RMID, nullptr);
        if (!attached) {
            shm_.shmaddr = nullptr;
            status_ = "the X server refused the shared memory";
            return false;
        }
        return true;
    }

    jint encode(const uint8_t* pixels, size_t stride, uint8_t* buffer, size_t capacity, bool keyframe) {
        toNv12(pixels, stride, width_, height_, nv12_.data());
        if (!encoder_->push(nv12_.data(), nv12_.size(), keyframe)) {
            status_ = "the H.264 encoder stopped taking frames";
            return NEXT_RETRY;
        }
        bool isKeyframe = false;
        bool failed = false;
        if (!encoder_->pull(bytes_, isKeyframe, failed, PULL_WAIT_MS)) {
            if (!failed) return NEXT_UNCHANGED;
            status_ = encoder_->problem();
            return NEXT_RETRY;
        }
        if (HEADER_BYTES + bytes_.size() > capacity) return NEXT_TOO_LARGE;
        writeHeader(buffer, width_, height_, isKeyframe ? FLAG_KEYFRAME : 0, 0);
        std::memcpy(buffer + HEADER_BYTES, bytes_.data(), bytes_.size());
        return static_cast<jint>(bytes_.size());
    }

    jint copy(const uint8_t* pixels, size_t stride, uint8_t* buffer, size_t capacity) {
        const size_t rowBytes = static_cast<size_t>(width_) * BYTES_PER_PIXEL;
        const size_t total = rowBytes * height_;
        if (HEADER_BYTES + total > capacity) return NEXT_TOO_LARGE;
        for (uint32_t row = 0; row < height_; ++row) {
            std::memcpy(buffer + HEADER_BYTES + row * rowBytes, pixels + row * stride, rowBytes);
        }
        writeHeader(buffer, width_, height_, FLAG_PIXELS, static_cast<uint32_t>(rowBytes));
        return static_cast<jint>(total);
    }

    Display* display_ = nullptr;
    Window root_ = 0;
    XImage* image_ = nullptr;
    XShmSegmentInfo shm_{};
    Region region_;
    uint32_t width_ = 0;
    uint32_t height_ = 0;
    uint32_t quietPasses_ = 0;
    bool hasLast_ = false;
    std::vector<uint8_t> last_;
    std::vector<uint8_t> scaled_;
    std::vector<uint8_t> nv12_;
    std::vector<uint8_t> bytes_;
    std::unique_ptr<GstEncoder> encoder_;
};

}  // namespace

std::unique_ptr<Session> openSession(int displayIndex, uint32_t maxEdge, uint32_t framesPerSecond, uint32_t bitrate) {
    return std::make_unique<LinuxSession>(displayIndex, maxEdge, framesPerSecond, bitrate);
}
