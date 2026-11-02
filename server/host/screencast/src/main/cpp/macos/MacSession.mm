#import <CoreGraphics/CoreGraphics.h>
#import <CoreMedia/CoreMedia.h>
#import <CoreVideo/CoreVideo.h>
#import <Foundation/Foundation.h>
#import <ScreenCaptureKit/ScreenCaptureKit.h>
#import <VideoToolbox/VideoToolbox.h>

#include <chrono>
#include <condition_variable>
#include <cstring>
#include <deque>
#include <mutex>
#include <string>
#include <vector>

#include "../posix/Session.h"

namespace {

constexpr int64_t CONTENT_WAIT_SECONDS = 5;
constexpr int QUEUE_DEPTH = 4;
constexpr int32_t TIME_SCALE = 1000000;
constexpr int KEYFRAME_INTERVAL_SECONDS = 600;
constexpr uint32_t OUTPUT_WAIT_MS = 40;
constexpr uint8_t START_CODE[] = {0, 0, 0, 1};

struct EncodedFrame {
    std::vector<uint8_t> bytes;
    bool keyframe = false;
};

/** Hands the newest finished frame from the capture queue to the session's thread. */
struct FrameMailbox {
    std::mutex mutex;
    std::condition_variable arrived;
    CVPixelBufferRef latest = nullptr;
    bool fresh = false;
    bool stopped = false;
    std::string failure;

    ~FrameMailbox() {
        if (latest != nullptr) CVPixelBufferRelease(latest);
    }
};

/** Encoded frames, in the order the encoder finished them. */
struct EncoderMailbox {
    std::mutex mutex;
    std::condition_variable arrived;
    std::deque<EncodedFrame> frames;
    OSStatus error = noErr;
};

/** H.264 in the Annex B form viewers read, with the sequence header in front of every keyframe. */
void appendAnnexB(CMSampleBufferRef sample, bool keyframe, std::vector<uint8_t>& output) {
    if (keyframe) {
        CMFormatDescriptionRef format = CMSampleBufferGetFormatDescription(sample);
        size_t count = 0;
        int lengthBytes = 0;
        CMVideoFormatDescriptionGetH264ParameterSetAtIndex(format, 0, nullptr, nullptr, &count, &lengthBytes);
        for (size_t index = 0; index < count; ++index) {
            const uint8_t* set = nullptr;
            size_t size = 0;
            if (CMVideoFormatDescriptionGetH264ParameterSetAtIndex(format, index, &set, &size, nullptr, nullptr) != noErr) {
                continue;
            }
            output.insert(output.end(), START_CODE, START_CODE + sizeof(START_CODE));
            output.insert(output.end(), set, set + size);
        }
    }

    CMBlockBufferRef block = CMSampleBufferGetDataBuffer(sample);
    char* data = nullptr;
    size_t length = 0;
    if (CMBlockBufferGetDataPointer(block, 0, nullptr, &length, &data) != noErr) return;
    size_t offset = 0;
    while (offset + 4 <= length) {
        uint32_t unitLength = 0;
        std::memcpy(&unitLength, data + offset, sizeof(unitLength));
        unitLength = CFSwapInt32BigToHost(unitLength);
        offset += sizeof(unitLength);
        if (unitLength > length - offset) return;
        output.insert(output.end(), START_CODE, START_CODE + sizeof(START_CODE));
        output.insert(output.end(), data + offset, data + offset + unitLength);
        offset += unitLength;
    }
}

void onEncoded(void* owner, void*, OSStatus status, VTEncodeInfoFlags, CMSampleBufferRef sample) {
    auto* mailbox = static_cast<EncoderMailbox*>(owner);
    EncodedFrame frame;
    if (status == noErr && sample != nullptr && CMSampleBufferDataIsReady(sample)) {
        CFArrayRef attachments = CMSampleBufferGetSampleAttachmentsArray(sample, false);
        bool notSync = false;
        if (attachments != nullptr && CFArrayGetCount(attachments) > 0) {
            auto first = static_cast<CFDictionaryRef>(CFArrayGetValueAtIndex(attachments, 0));
            notSync = CFDictionaryContainsKey(first, kCMSampleAttachmentKey_NotSync) &&
                CFBooleanGetValue(static_cast<CFBooleanRef>(CFDictionaryGetValue(first, kCMSampleAttachmentKey_NotSync)));
        }
        frame.keyframe = !notSync;
        appendAnnexB(sample, frame.keyframe, frame.bytes);
    }
    std::lock_guard<std::mutex> lock(mailbox->mutex);
    if (status != noErr) mailbox->error = status;
    if (!frame.bytes.empty()) mailbox->frames.push_back(std::move(frame));
    mailbox->arrived.notify_all();
}

}  // namespace

/** ScreenCaptureKit calls this on its own queue. It only reports complete frames, so a still screen is silent. */
@interface FrameTap : NSObject <SCStreamOutput, SCStreamDelegate>
- (instancetype)initWithMailbox:(FrameMailbox*)mailbox;
@end

@implementation FrameTap {
    FrameMailbox* _mailbox;
}

- (instancetype)initWithMailbox:(FrameMailbox*)mailbox {
    self = [super init];
    if (self != nil) _mailbox = mailbox;
    return self;
}

- (void)stream:(SCStream*)stream didOutputSampleBuffer:(CMSampleBufferRef)sample ofType:(SCStreamOutputType)type {
    if (type != SCStreamOutputTypeScreen || !CMSampleBufferIsValid(sample)) return;
    CFArrayRef attachments = CMSampleBufferGetSampleAttachmentsArray(sample, false);
    if (attachments == nullptr || CFArrayGetCount(attachments) == 0) return;
    NSDictionary* info = (__bridge NSDictionary*)CFArrayGetValueAtIndex(attachments, 0);
    NSNumber* status = info[SCStreamFrameInfoStatus];
    if (status == nil || status.integerValue != SCFrameStatusComplete) return;
    CVPixelBufferRef pixels = CMSampleBufferGetImageBuffer(sample);
    if (pixels == nullptr) return;

    CVPixelBufferRetain(pixels);
    std::lock_guard<std::mutex> lock(_mailbox->mutex);
    if (_mailbox->latest != nullptr) CVPixelBufferRelease(_mailbox->latest);
    _mailbox->latest = pixels;
    _mailbox->fresh = true;
    _mailbox->arrived.notify_all();
}

- (void)stream:(SCStream*)stream didStopWithError:(NSError*)error {
    std::lock_guard<std::mutex> lock(_mailbox->mutex);
    _mailbox->stopped = true;
    _mailbox->failure = error.localizedDescription.UTF8String ?: "capture stopped";
    _mailbox->arrived.notify_all();
}

@end

namespace {

/** ScreenCaptureKit scales and converts on the GPU, VideoToolbox compresses on the media engine. */
class MacSession final : public Session {
public:
    using Session::Session;

    ~MacSession() override { tearDown(); }

protected:
    bool bringUp() override {
        failure_ = NEXT_RETRY;
        if (!CGPreflightScreenCaptureAccess()) {
            // Shows the system prompt once. macOS applies the answer the next time the app starts.
            if (!asked_) CGRequestScreenCaptureAccess();
            asked_ = true;
            failure_ = NEXT_NEEDS_ACCESS;
            status_ = "Screen Recording is off for this app";
            return false;
        }
        if (!openCapture()) return false;
        if (encoding() && !openEncoder()) {
            tearDown();
            return false;
        }
        status_ = encoding() ? "ready: VideoToolbox" : "ready";
        return true;
    }

    void tearDown() override {
        if (stream_ != nil) {
            dispatch_semaphore_t stopped = dispatch_semaphore_create(0);
            [stream_ stopCaptureWithCompletionHandler:^(NSError*) {
                dispatch_semaphore_signal(stopped);
            }];
            dispatch_semaphore_wait(stopped, dispatch_time(DISPATCH_TIME_NOW, CONTENT_WAIT_SECONDS * NSEC_PER_SEC));
            stream_ = nil;
            tap_ = nil;
        }
        if (encoder_ != nullptr) {
            VTCompressionSessionInvalidate(encoder_);
            CFRelease(encoder_);
            encoder_ = nullptr;
        }
        if (shown_ != nullptr) {
            CVPixelBufferRelease(shown_);
            shown_ = nullptr;
        }
        std::lock_guard<std::mutex> lock(frames_.mutex);
        if (frames_.latest != nullptr) CVPixelBufferRelease(frames_.latest);
        frames_.latest = nullptr;
        frames_.fresh = false;
        frames_.stopped = false;
    }

    Grab grab(uint32_t waitMs) override {
        std::unique_lock<std::mutex> lock(frames_.mutex);
        frames_.arrived.wait_for(lock, std::chrono::milliseconds(waitMs), [this] {
            return frames_.fresh || frames_.stopped;
        });
        if (frames_.stopped) {
            status_ = "capture stopped: " + frames_.failure;
            return Grab::Lost;
        }
        if (!frames_.fresh) return Grab::Unchanged;
        frames_.fresh = false;
        if (shown_ != nullptr) CVPixelBufferRelease(shown_);
        shown_ = CVPixelBufferRetain(frames_.latest);
        return Grab::Frame;
    }

    jint produce(uint8_t* buffer, size_t capacity, bool keyframe) override {
        if (shown_ == nullptr) return NEXT_UNCHANGED;
        return encoding() ? encode(buffer, capacity, keyframe) : copyPixels(buffer, capacity);
    }

    void applyBitrate(uint32_t bitrate) override {
        if (encoder_ == nullptr) return;
        CFNumberRef value = CFNumberCreate(nullptr, kCFNumberSInt32Type, &bitrate);
        VTSessionSetProperty(encoder_, kVTCompressionPropertyKey_AverageBitRate, value);
        CFRelease(value);
    }

private:
    bool openCapture() {
        dispatch_semaphore_t done = dispatch_semaphore_create(0);
        __block SCShareableContent* content = nil;
        __block NSString* problem = nil;
        [SCShareableContent getShareableContentWithCompletionHandler:^(SCShareableContent* found, NSError* error) {
            content = found;
            problem = error.localizedDescription;
            dispatch_semaphore_signal(done);
        }];
        if (dispatch_semaphore_wait(done, dispatch_time(DISPATCH_TIME_NOW, CONTENT_WAIT_SECONDS * NSEC_PER_SEC)) != 0 || content == nil) {
            status_ = std::string("screen capture failed: ") + (problem != nil ? problem.UTF8String : "no answer");
            return false;
        }

        // AWT names a Mac display by its CoreGraphics number, which is what arrives here.
        const CGDirectDisplayID wanted = static_cast<CGDirectDisplayID>(displayIndex_);
        SCDisplay* display = nil;
        for (SCDisplay* candidate in content.displays) {
            if (candidate.displayID == wanted) display = candidate;
        }
        if (display == nil) {
            for (SCDisplay* candidate in content.displays) {
                if (candidate.displayID == CGMainDisplayID()) display = candidate;
            }
        }
        if (display == nil) display = content.displays.firstObject;
        if (display == nil) {
            status_ = "screen capture failed: no display";
            return false;
        }

        CGDisplayModeRef mode = CGDisplayCopyDisplayMode(display.displayID);
        const uint32_t pixelWidth = mode != nullptr ? static_cast<uint32_t>(CGDisplayModeGetPixelWidth(mode)) : static_cast<uint32_t>(display.width);
        const uint32_t pixelHeight = mode != nullptr ? static_cast<uint32_t>(CGDisplayModeGetPixelHeight(mode)) : static_cast<uint32_t>(display.height);
        if (mode != nullptr) CGDisplayModeRelease(mode);
        fitInto(pixelWidth, pixelHeight, maxEdge_, width_, height_);

        SCStreamConfiguration* configuration = [[SCStreamConfiguration alloc] init];
        configuration.width = width_;
        configuration.height = height_;
        configuration.minimumFrameInterval = CMTimeMake(1, static_cast<int32_t>(framesPerSecond_));
        configuration.pixelFormat = encoding() ? kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange : kCVPixelFormatType_32BGRA;
        configuration.colorMatrix = kCGDisplayStreamYCbCrMatrix_ITU_R_709_2;
        configuration.colorSpaceName = kCGColorSpaceSRGB;
        configuration.queueDepth = QUEUE_DEPTH;
        // Like the Windows stream: the viewers draw their own pointer, and moving it is not a new picture.
        configuration.showsCursor = NO;

        SCContentFilter* filter = [[SCContentFilter alloc] initWithDisplay:display excludingWindows:@[]];
        tap_ = [[FrameTap alloc] initWithMailbox:&frames_];
        stream_ = [[SCStream alloc] initWithFilter:filter configuration:configuration delegate:tap_];
        NSError* added = nil;
        dispatch_queue_t queue = dispatch_queue_create("screencast.capture", DISPATCH_QUEUE_SERIAL);
        if (![stream_ addStreamOutput:tap_ type:SCStreamOutputTypeScreen sampleHandlerQueue:queue error:&added]) {
            status_ = std::string("screen capture failed: ") + added.localizedDescription.UTF8String;
            stream_ = nil;
            tap_ = nil;
            return false;
        }

        __block NSString* startProblem = nil;
        [stream_ startCaptureWithCompletionHandler:^(NSError* error) {
            startProblem = error.localizedDescription;
            dispatch_semaphore_signal(done);
        }];
        const bool answered = dispatch_semaphore_wait(done, dispatch_time(DISPATCH_TIME_NOW, CONTENT_WAIT_SECONDS * NSEC_PER_SEC)) == 0;
        if (!answered || startProblem != nil) {
            status_ = std::string("screen capture failed: ") + (startProblem != nil ? startProblem.UTF8String : "no answer");
            stream_ = nil;
            tap_ = nil;
            return false;
        }
        return true;
    }

    bool openEncoder() {
        NSDictionary* lowLatency = @{
            (__bridge NSString*)kVTVideoEncoderSpecification_EnableLowLatencyRateControl: @YES,
        };
        OSStatus result = VTCompressionSessionCreate(
            nullptr, static_cast<int32_t>(width_), static_cast<int32_t>(height_), kCMVideoCodecType_H264,
            (__bridge CFDictionaryRef)lowLatency, nullptr, nullptr, onEncoded, &output_, &encoder_);
        if (result != noErr) {
            // Older Intel Macs have no low latency mode. Their ordinary encoder works the same way for us.
            result = VTCompressionSessionCreate(
                nullptr, static_cast<int32_t>(width_), static_cast<int32_t>(height_), kCMVideoCodecType_H264,
                nullptr, nullptr, nullptr, onEncoded, &output_, &encoder_);
        }
        if (result != noErr) {
            failure_ = NEXT_FAILED;
            status_ = "H.264 encoder failed: " + std::to_string(result);
            return false;
        }

        const int32_t framesPerSecond = static_cast<int32_t>(framesPerSecond_);
        const int32_t keyframeInterval = framesPerSecond * KEYFRAME_INTERVAL_SECONDS;
        const int32_t bitrate = static_cast<int32_t>(bitrate_);
        NSDictionary* properties = @{
            (__bridge NSString*)kVTCompressionPropertyKey_RealTime: @YES,
            (__bridge NSString*)kVTCompressionPropertyKey_AllowFrameReordering: @NO,
            (__bridge NSString*)kVTCompressionPropertyKey_ProfileLevel: (__bridge NSString*)kVTProfileLevel_H264_High_AutoLevel,
            (__bridge NSString*)kVTCompressionPropertyKey_ExpectedFrameRate: @(framesPerSecond),
            (__bridge NSString*)kVTCompressionPropertyKey_AverageBitRate: @(bitrate),
            // Keyframes are costly and a socket does not lose frames: they come when a viewer needs one.
            (__bridge NSString*)kVTCompressionPropertyKey_MaxKeyFrameInterval: @(keyframeInterval),
            (__bridge NSString*)kVTCompressionPropertyKey_ColorPrimaries: (__bridge NSString*)kCVImageBufferColorPrimaries_ITU_R_709_2,
            (__bridge NSString*)kVTCompressionPropertyKey_TransferFunction: (__bridge NSString*)kCVImageBufferTransferFunction_ITU_R_709_2,
            (__bridge NSString*)kVTCompressionPropertyKey_YCbCrMatrix: (__bridge NSString*)kCVImageBufferYCbCrMatrix_ITU_R_709_2,
        };
        result = VTSessionSetProperties(encoder_, (__bridge CFDictionaryRef)properties);
        if (result == noErr) result = VTCompressionSessionPrepareToEncodeFrames(encoder_);
        if (result != noErr) {
            failure_ = NEXT_FAILED;
            status_ = "H.264 encoder failed: " + std::to_string(result);
            return false;
        }
        return true;
    }

    jint encode(uint8_t* buffer, size_t capacity, bool keyframe) {
        const auto now = std::chrono::steady_clock::now().time_since_epoch();
        const int64_t micros = std::chrono::duration_cast<std::chrono::microseconds>(now).count();
        NSDictionary* options = keyframe
            ? @{(__bridge NSString*)kVTEncodeFrameOptionKey_ForceKeyFrame: @YES}
            : nil;
        const OSStatus result = VTCompressionSessionEncodeFrame(
            encoder_, shown_, CMTimeMake(micros, TIME_SCALE), kCMTimeInvalid,
            (__bridge CFDictionaryRef)options, nullptr, nullptr);
        if (result != noErr) {
            status_ = "H.264 encoder failed: " + std::to_string(result);
            return NEXT_RETRY;
        }

        EncodedFrame frame;
        {
            std::unique_lock<std::mutex> lock(output_.mutex);
            output_.arrived.wait_for(lock, std::chrono::milliseconds(OUTPUT_WAIT_MS), [this] {
                return !output_.frames.empty() || output_.error != noErr;
            });
            if (output_.error != noErr) {
                status_ = "H.264 encoder failed: " + std::to_string(output_.error);
                output_.error = noErr;
                return NEXT_RETRY;
            }
            if (output_.frames.empty()) return NEXT_UNCHANGED;
            frame = std::move(output_.frames.front());
            output_.frames.pop_front();
        }

        const size_t total = HEADER_BYTES + frame.bytes.size();
        if (total > capacity) return NEXT_TOO_LARGE;
        writeHeader(buffer, width_, height_, frame.keyframe ? FLAG_KEYFRAME : 0, 0);
        std::memcpy(buffer + HEADER_BYTES, frame.bytes.data(), frame.bytes.size());
        return static_cast<jint>(frame.bytes.size());
    }

    jint copyPixels(uint8_t* buffer, size_t capacity) {
        const size_t width = CVPixelBufferGetWidth(shown_);
        const size_t height = CVPixelBufferGetHeight(shown_);
        const size_t rowBytes = width * 4;
        const size_t total = rowBytes * height;
        if (HEADER_BYTES + total > capacity) return NEXT_TOO_LARGE;
        if (CVPixelBufferLockBaseAddress(shown_, kCVPixelBufferLock_ReadOnly) != kCVReturnSuccess) return NEXT_UNCHANGED;
        const auto* source = static_cast<const uint8_t*>(CVPixelBufferGetBaseAddress(shown_));
        const size_t stride = CVPixelBufferGetBytesPerRow(shown_);
        for (size_t row = 0; row < height; ++row) {
            std::memcpy(buffer + HEADER_BYTES + row * rowBytes, source + row * stride, rowBytes);
        }
        CVPixelBufferUnlockBaseAddress(shown_, kCVPixelBufferLock_ReadOnly);
        writeHeader(buffer, static_cast<uint32_t>(width), static_cast<uint32_t>(height), FLAG_PIXELS, static_cast<uint32_t>(rowBytes));
        return static_cast<jint>(total);
    }

    FrameMailbox frames_;
    EncoderMailbox output_;
    FrameTap* tap_ = nil;
    SCStream* stream_ = nil;
    VTCompressionSessionRef encoder_ = nullptr;
    CVPixelBufferRef shown_ = nullptr;
    uint32_t width_ = 0;
    uint32_t height_ = 0;
    bool asked_ = false;
};

}  // namespace

std::unique_ptr<Session> openSession(int displayIndex, uint32_t maxEdge, uint32_t framesPerSecond, uint32_t bitrate) {
    return std::make_unique<MacSession>(displayIndex, maxEdge, framesPerSecond, bitrate);
}
