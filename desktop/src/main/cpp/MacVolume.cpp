#include <jni.h>

#include <cmath>
#include <CoreAudio/AudioHardware.h>

namespace {

constexpr AudioObjectPropertySelector kVirtualMainVolume = 'vmvc';
constexpr AudioObjectPropertySelector kVolumeScalar = 'volm';
constexpr AudioObjectPropertySelector kMute = 'mute';

AudioObjectID defaultOutputDevice() {
    AudioObjectPropertyAddress address = {
        kAudioHardwarePropertyDefaultOutputDevice,
        kAudioObjectPropertyScopeGlobal,
        kAudioObjectPropertyElementMain,
    };
    AudioObjectID device = kAudioObjectUnknown;
    UInt32 size = sizeof(device);
    OSStatus status = AudioObjectGetPropertyData(
        kAudioObjectSystemObject,
        &address,
        0,
        nullptr,
        &size,
        &device
    );
    if (status != noErr || device == kAudioObjectUnknown) {
        return kAudioObjectUnknown;
    }
    return device;
}

bool readFloat(
    AudioObjectID objectId,
    AudioObjectPropertySelector selector,
    Float32* value
) {
    AudioObjectPropertyAddress address = {
        selector,
        kAudioObjectPropertyScopeOutput,
        kAudioObjectPropertyElementMain,
    };
    UInt32 size = sizeof(Float32);
    return AudioObjectGetPropertyData(objectId, &address, 0, nullptr, &size, value) == noErr;
}

bool writeFloat(
    AudioObjectID objectId,
    AudioObjectPropertySelector selector,
    Float32 value
) {
    AudioObjectPropertyAddress address = {
        selector,
        kAudioObjectPropertyScopeOutput,
        kAudioObjectPropertyElementMain,
    };
    return AudioObjectSetPropertyData(
        objectId,
        &address,
        0,
        nullptr,
        sizeof(value),
        &value
    ) == noErr;
}

bool readMuted(AudioObjectID objectId, UInt32* muted) {
    AudioObjectPropertyAddress address = {
        kMute,
        kAudioObjectPropertyScopeOutput,
        kAudioObjectPropertyElementMain,
    };
    UInt32 size = sizeof(UInt32);
    return AudioObjectGetPropertyData(objectId, &address, 0, nullptr, &size, muted) == noErr;
}

bool writeMuted(AudioObjectID objectId, UInt32 muted) {
    AudioObjectPropertyAddress address = {
        kMute,
        kAudioObjectPropertyScopeOutput,
        kAudioObjectPropertyElementMain,
    };
    return AudioObjectSetPropertyData(
        objectId,
        &address,
        0,
        nullptr,
        sizeof(muted),
        &muted
    ) == noErr;
}

bool readVolume(AudioObjectID device, Float32* value) {
    if (readFloat(device, kVirtualMainVolume, value)) return true;
    return readFloat(device, kVolumeScalar, value);
}

bool writeVolume(AudioObjectID device, Float32 value) {
    if (writeFloat(device, kVirtualMainVolume, value)) return true;
    return writeFloat(device, kVolumeScalar, value);
}

}  // namespace

extern "C" {

JNIEXPORT jfloat JNICALL
Java_com_remotecontroller_desktop_executor_MacCoreAudio_nativeReadVolume(
    JNIEnv*,
    jclass
) {
    AudioObjectID device = defaultOutputDevice();
    Float32 value = 0;
    if (device == kAudioObjectUnknown || !readVolume(device, &value)) {
        return nanf("");
    }
    return value;
}

JNIEXPORT jint JNICALL
Java_com_remotecontroller_desktop_executor_MacCoreAudio_nativeReadMuted(
    JNIEnv*,
    jclass
) {
    AudioObjectID device = defaultOutputDevice();
    UInt32 muted = 0;
    if (device == kAudioObjectUnknown || !readMuted(device, &muted)) {
        return -1;
    }
    return muted == 0 ? 0 : 1;
}

JNIEXPORT jboolean JNICALL
Java_com_remotecontroller_desktop_executor_MacCoreAudio_nativeSetVolume(
    JNIEnv*,
    jclass,
    jfloat scalar
) {
    AudioObjectID device = defaultOutputDevice();
    if (device == kAudioObjectUnknown) return JNI_FALSE;
    return writeVolume(device, scalar) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_remotecontroller_desktop_executor_MacCoreAudio_nativeSetMuted(
    JNIEnv*,
    jclass,
    jboolean muted
) {
    AudioObjectID device = defaultOutputDevice();
    if (device == kAudioObjectUnknown) return JNI_FALSE;
    return writeMuted(device, muted == JNI_TRUE ? 1 : 0) ? JNI_TRUE : JNI_FALSE;
}

}
