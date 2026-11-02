# Using Remote Controller

1. Start the laptop app. It shows an **address** (e.g. `192.168.1.20:8765`) and, with **Require access code** on, an **access code** (`XXXX-XXXX-XXXX`). A new code is made on every start.
2. Put the phone on the same Wi-Fi (guest networks that isolate devices will not work), or turn on **Reach from anywhere** ([relay](relay.md)).
3. Connect from the phone app or from a browser.

The text row under the mouse pad and under the screen view, in the app and in the browser, types on the laptop as you type, in any language. Send on the keyboard presses Enter.

In the browser, click the screen to capture the keyboard, as in a virtual machine: every key, Ctrl, Alt, F-keys and shortcuts included, goes to the laptop, and its own layout decides what comes out. Press and let go Ctrl + Alt (Control + Option on a Mac) to get the keyboard back, or click outside the screen. Outside full screen the browser keeps a few shortcuts for itself, such as Ctrl + W and Ctrl + T. **Full screen** captures the keyboard too, and in Chrome and Edge those shortcuts then reach the laptop as well. Hold Esc to leave it.

On the phone's screen view a finger works like a laptop touchpad: drag to move, tap to click, double tap and hold to drag, two fingers to right click (tap) or scroll (drag). **Touch** switches to pressing right where you touch.

**Require access code** (on by default) decides how a device gets in:

| Switch | What the device does | What the laptop does |
|---|---|---|
| On | types the code | nothing |
| Off | connects and shows a six-digit check number | pops up **Allow / Deny** with the same number |

A device that got in once, by code or by Allow, gets a grant and is remembered: it comes back without either, also after a restart or when the laptop's address changes. The laptop keeps grants in `devices.json` in its app folder (owner only), the phone app in its private storage, a browser in that site's local storage. The **Remembered** row lists them. **Disconnect** forgets that one device, **Forget all** forgets every one, and turning the code back on does too. Do not use the page on a shared browser: it stays allowed until you forget it.

## Web client

The browser page is a Compose for Web (Wasm) app. Both the laptop (at `/web`) and the relay (at `/`) serve the same build.

**On the LAN**
1. On the laptop, press **Trust this computer** once. On macOS it adds the local CA to the login keychain; on Windows it adds the local CA to your own certificate store, after Windows asks you to confirm. Restart the browser afterwards. Firefox keeps its own store: import `local-ca.crt` there.
2. Open `https://<address>/web`. To skip typing the code, add it as a fragment: `https://<address>/web#XXXX-XXXX-XXXX`. The page removes the fragment from the address bar and history.
   Devices that do not trust the CA show a certificate warning; install `local-ca.crt` there or accept the warning.
3. Type the code if the laptop asks for one; otherwise wait for **Allow**.

**From anywhere**: open `https://<relay>/#id=123456789`, or enter the laptop's ID on the relay's start page. No certificate warning, because the relay has a public certificate.

The CA, the relay device key (`relay-device.p8`) and their files live in `~/Library/Application Support/Remote Controller` (macOS), `%APPDATA%\Remote Controller` (Windows) or `~/.local/share/remote-controller` (Linux). The open button on the laptop's clipboard card opens the local page, with the code filled in when the code is required.

> Upgrading from an older version: the app makes a new, name-constrained CA. Remove the old **Remote Controller Local CA** from the keychain or certificate store, press **Trust this computer** again, and paste the new fingerprint into phones that pin it.
