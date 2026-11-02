#include "GraphicsCapture.h"

#include <roapi.h>
#include <windows.foundation.h>
#include <windows.graphics.capture.interop.h>
#include <windows.graphics.directx.direct3d11.interop.h>
#include <wrl/event.h>
#include <wrl/wrappers/corewrappers.h>

namespace Capture = ABI::Windows::Graphics::Capture;
namespace Foundation = ABI::Windows::Foundation;

using Microsoft::WRL::Callback;
using Microsoft::WRL::ClassicCom;
using Microsoft::WRL::FtmBase;
using Microsoft::WRL::Implements;
using Microsoft::WRL::RuntimeClassFlags;
using Microsoft::WRL::Wrappers::HStringReference;

namespace {

constexpr INT32 POOL_FRAMES = 2;
constexpr DWORD ACCESS_WAIT_MS = 200;
constexpr DWORD ACCESS_POLL_MS = 10;

using FrameHandler = Foundation::ITypedEventHandler<Capture::Direct3D11CaptureFramePool*, IInspectable*>;

void close(Capture::IDirect3D11CaptureFrame* frame) {
    ComPtr<Foundation::IClosable> closable;
    if (frame != nullptr && SUCCEEDED(frame->QueryInterface(IID_PPV_ARGS(&closable)))) closable->Close();
}

/** The picture carries no pointer, as with duplication. Windows older than 2004 draws it anyway. */
void hidePointer(Capture::IGraphicsCaptureSession* session) {
    ComPtr<Capture::IGraphicsCaptureSession2> pointerless;
    if (SUCCEEDED(session->QueryInterface(IID_PPV_ARGS(&pointerless)))) pointerless->put_IsCursorCaptureEnabled(FALSE);
}

/** Windows 11 lets an app turn the yellow border off. A request that is not answered at once leaves it on. */
void hideBorder(Capture::IGraphicsCaptureSession* session) {
    ComPtr<Capture::IGraphicsCaptureSession3> borderless;
    if (FAILED(session->QueryInterface(IID_PPV_ARGS(&borderless)))) return;

    ComPtr<Capture::IGraphicsCaptureAccessStatics> access;
    const HStringReference className(RuntimeClass_Windows_Graphics_Capture_GraphicsCaptureAccess);
    if (SUCCEEDED(RoGetActivationFactory(className.Get(), IID_PPV_ARGS(&access)))) {
        ComPtr<__FIAsyncOperation_1_Windows__CSecurity__CAuthorization__CAppCapabilityAccess__CAppCapabilityAccessStatus> request;
        ComPtr<Foundation::IAsyncInfo> progress;
        if (SUCCEEDED(access->RequestAccessAsync(Capture::GraphicsCaptureAccessKind_Borderless, &request)) &&
            SUCCEEDED(request.As(&progress))) {
            const ULONGLONG deadline = GetTickCount64() + ACCESS_WAIT_MS;
            AsyncStatus status = AsyncStatus::Started;
            while (SUCCEEDED(progress->get_Status(&status)) && status == AsyncStatus::Started && GetTickCount64() < deadline) {
                Sleep(ACCESS_POLL_MS);
            }
        }
    }
    borderless->put_IsBorderRequired(FALSE);
}

}  // namespace

class GraphicsCapture::Signal {
public:
    Signal() : event_(CreateEventW(nullptr, FALSE, FALSE, nullptr)) {}

    ~Signal() {
        if (event_ != nullptr) CloseHandle(event_);
    }

    Signal(const Signal&) = delete;
    Signal& operator=(const Signal&) = delete;

    void raise() { SetEvent(event_); }

    void wait(DWORD timeoutMs) { WaitForSingleObject(event_, timeoutMs); }

private:
    HANDLE event_;
};

GraphicsCapture::~GraphicsCapture() {
    if (pool_) pool_->remove_FrameArrived(arrivedToken_);
    ComPtr<Foundation::IClosable> closable;
    if (session_ && SUCCEEDED(session_.As(&closable))) closable->Close();
    closable.Reset();
    if (pool_ && SUCCEEDED(pool_.As(&closable))) closable->Close();
}

HRESULT GraphicsCapture::open(IDXGIAdapter1* adapter, HMONITOR monitor) {
    RETURN_IF_FAILED(createDevice(adapter));

    ComPtr<IGraphicsCaptureItemInterop> interop;
    const HStringReference className(RuntimeClass_Windows_Graphics_Capture_GraphicsCaptureItem);
    RETURN_IF_FAILED(RoGetActivationFactory(className.Get(), IID_PPV_ARGS(&interop)));
    RETURN_IF_FAILED(interop->CreateForMonitor(monitor, IID_PPV_ARGS(&item_)));

    ABI::Windows::Graphics::SizeInt32 size{};
    RETURN_IF_FAILED(item_->get_Size(&size));
    RETURN_IF_FAILED(createImage(static_cast<UINT>(size.Width), static_cast<UINT>(size.Height)));
    RETURN_IF_FAILED(createPool(size));

    RETURN_IF_FAILED(pool_->CreateCaptureSession(item_.Get(), &session_));
    hidePointer(session_.Get());
    hideBorder(session_.Get());
    return session_->StartCapture();
}

HRESULT GraphicsCapture::createPool(ABI::Windows::Graphics::SizeInt32 size) {
    ComPtr<IDXGIDevice> dxgiDevice;
    RETURN_IF_FAILED(device_.As(&dxgiDevice));
    ComPtr<IInspectable> inspectable;
    RETURN_IF_FAILED(CreateDirect3D11DeviceFromDXGIDevice(dxgiDevice.Get(), &inspectable));
    ComPtr<ABI::Windows::Graphics::DirectX::Direct3D11::IDirect3DDevice> device;
    RETURN_IF_FAILED(inspectable.As(&device));

    ComPtr<Capture::IDirect3D11CaptureFramePoolStatics2> pools;
    const HStringReference className(RuntimeClass_Windows_Graphics_Capture_Direct3D11CaptureFramePool);
    RETURN_IF_FAILED(RoGetActivationFactory(className.Get(), IID_PPV_ARGS(&pools)));
    // Free threaded: frames arrive on a pool thread, and this thread needs no message loop.
    RETURN_IF_FAILED(pools->CreateFreeThreaded(
        device.Get(),
        ABI::Windows::Graphics::DirectX::DirectXPixelFormat_B8G8R8A8UIntNormalized,
        POOL_FRAMES,
        size,
        &pool_));

    frameArrived_ = std::make_shared<Signal>();
    auto handler = Callback<Implements<RuntimeClassFlags<ClassicCom>, FrameHandler, FtmBase>>(
        [signal = frameArrived_](Capture::IDirect3D11CaptureFramePool*, IInspectable*) -> HRESULT {
            signal->raise();
            return S_OK;
        });
    if (!handler) return E_OUTOFMEMORY;
    return pool_->add_FrameArrived(handler.Get(), &arrivedToken_);
}

DesktopCapture::Grab GraphicsCapture::next(UINT timeoutMs) {
    ComPtr<Frame> frame;
    if (FAILED(takeNewest(frame))) return Grab::Lost;
    if (!frame) {
        frameArrived_->wait(timeoutMs);
        if (FAILED(takeNewest(frame))) return Grab::Lost;
    }
    if (!frame) return Grab::Unchanged;
    const Grab result = keep(frame.Get());
    close(frame.Get());
    return result;
}

/** The pool holds a couple of frames: an older one is already out of date. */
HRESULT GraphicsCapture::takeNewest(ComPtr<Frame>& newest) {
    while (true) {
        ComPtr<Frame> frame;
        RETURN_IF_FAILED(pool_->TryGetNextFrame(&frame));
        if (!frame) return S_OK;
        close(newest.Get());
        newest = frame;
    }
}

DesktopCapture::Grab GraphicsCapture::keep(Frame* frame) {
    // A mode change: the session opens a new capture at the new size.
    ABI::Windows::Graphics::SizeInt32 size{};
    if (FAILED(frame->get_ContentSize(&size))) return Grab::Lost;
    if (static_cast<UINT>(size.Width) != width_ || static_cast<UINT>(size.Height) != height_) return Grab::Lost;

    ComPtr<ABI::Windows::Graphics::DirectX::Direct3D11::IDirect3DSurface> surface;
    if (FAILED(frame->get_Surface(&surface))) return Grab::Lost;
    ComPtr<::Windows::Graphics::DirectX::Direct3D11::IDirect3DDxgiInterfaceAccess> access;
    if (FAILED(surface.As(&access))) return Grab::Lost;
    ComPtr<ID3D11Texture2D> desktop;
    if (FAILED(access->GetInterface(IID_PPV_ARGS(&desktop)))) return Grab::Lost;

    context_->CopyResource(image_.Get(), desktop.Get());
    return Grab::Frame;
}
