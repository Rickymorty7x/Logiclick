# Logiclick Android Remote Touch API

Logiclick lets a device owner send signed touch gestures to an unlocked Android phone through a private Raspberry Pi API. It supports taps, long presses, swipes, drags, arbitrary paths, simultaneous multi-touch strokes, Back, Home, Recents, notification shade actions, and launching ordinary apps by package name. The phone can also send a low-frame-rate JPEG mirror to the controller so a human or a separately configured intelligence endpoint can choose the next action.

The Android owner must install the app, manually enable its Accessibility Service, and arm remote control in the app. Ongoing notifications show whenever remote control or screen sharing is active and include a **STOP** action. Android security, permission, and package-installer screens are deliberately blocked. The app cannot unlock the phone or bypass Android secure windows.

## Three layers

```text
Intelligence endpoint (optional; proposes one validated action)
                            ↓
Web controller on the Pi (human view, approval, taps and drags)
                            ↕ signed commands / JPEG frames
Android app (MediaProjection mirror + Accessibility gestures)
```

- **Phone layer:** receives only signed, short-lived actions and performs them through Android Accessibility. Its mirror output URL can be changed independently of its action input URL.
- **Controller layer:** stores the latest frame, signs and queues actions, and serves the private web controller.
- **Intelligence layer:** receives the current frame and a goal, then proposes one action. Human approval is enabled by default in the web controller.

## Raspberry Pi API endpoint

The working private Tailnet endpoint is:

```text
http://100.93.193.74:8765
```

Submit commands here:

```text
POST http://100.93.193.74:8765/v1/devices/phone-1/commands
```

Open the controller from a Tailnet-connected browser:

```text
http://100.93.193.74:8765/controller/
```

A private hosted copy is also deployed at [logiclick-controller.ritikyadav7x.chatgpt.site](https://logiclick-controller.ritikyadav7x.chatgpt.site). Because that page is HTTPS, enable Tailscale Serve before connecting it to the Pi; the Pi-hosted `/controller/` URL works immediately inside the Tailnet.

The service binds only to the Pi's Tailscale address, so the endpoint is available only to devices connected to the same Tailscale network. The `http://` application traffic still travels inside Tailscale's encrypted WireGuard tunnel. The Android app accepts cleartext HTTP only for Tailscale's `100.64.0.0/10` range; all other endpoints must use HTTPS. Every submit request requires the admin bearer token. The Android polling endpoint uses a separate device bearer token. Both tokens are stored only on the Pi in `~/.config/logiclick/environment` with mode `0600`.

Tailscale Serve is not yet enabled for this Tailnet. After the Tailnet owner enables it once, run `tailscale serve --bg --yes 8765` on the Pi and switch the API base URL to `https://ricky.dusky-parore.ts.net`.

To print the Android setup values while logged into the Pi:

```bash
grep -E 'LOGICLICK_(DEVICE_ID|DEVICE_TOKEN)=' ~/.config/logiclick/environment
curl -s http://100.93.193.74:8765/v1/public-key
```

This build keeps the endpoint and public key inside the app as defaults. The connection screen has a source selector with **Built-in Raspberry Pi** and **Custom endpoint**. Switching and saving the selector changes the API from which the phone receives actions. Confirm these values and add the device credential during pairing:

- API base URL: `http://100.93.193.74:8765` (preconfigured)
- Device ID: `phone-1`
- Device token: `LOGICLICK_DEVICE_TOKEN` from the Pi
- Ed25519 public key: `publicKeyBase64` returned by `/v1/public-key` (preconfigured)

## Authentication

For command submission:

```http
Authorization: Bearer YOUR_ADMIN_TOKEN
Content-Type: application/json
```

The Pi validates each request, creates a command ID, adds a short expiry and nonce, signs the complete command envelope with Ed25519, and queues it. The Android app verifies the signature, expiry, nonce, and replay status before dispatching a gesture.

## Always-on screen mirroring

In the Android app, enter the mirror URL and enable **Send screen mirror**. The Pi defaults are:

```text
Screen mirror output URL:
http://100.93.193.74:8765/v1/devices/phone-1/mirror/frame

Mirror bearer token:
the same LOGICLICK_DEVICE_TOKEN used for command polling
```

Android shows its system screen-capture approval once when a new sharing session starts. After approval, Logiclick runs a media-projection foreground service and continues sending frames while the phone is on, even when the Logiclick activity is closed or removed from Recents. The permanent notification makes capture visible and provides a **STOP** control.

The **Keep sharing while device is on; remind after reboot** option is enabled by default. Android deliberately invalidates screen-capture permission when the phone restarts or the projection is stopped, so no legitimate app can silently resume capture after reboot. Logiclick posts a one-tap notification after boot; tapping it opens Android's required approval prompt and restarts the persistent session. Some manufacturer battery managers can still stop foreground services; exempt Logiclick from that manufacturer's battery optimization if this occurs.

Mirror upload request:

```http
POST /v1/devices/phone-1/mirror/frame
Authorization: Bearer YOUR_DEVICE_TOKEN
Content-Type: image/jpeg
X-Logiclick-Device-ID: phone-1
X-Captured-At: 1791917800000
```

The Pi accepts JPEG frames from 100 bytes to 2.5 MB and atomically replaces the previous frame. It does not build a video archive. The controller reads the latest frame with the admin token:

```http
GET /v1/devices/phone-1/mirror/latest
Authorization: Bearer YOUR_ADMIN_TOKEN
```

`GET /v1/devices/phone-1/mirror/status` returns the capture timestamp and byte count instead of image data.

## Web controller

Open `/controller/`, enter the admin token, and select **Connect to device**. Clicking the displayed phone sends a normalized `tap`; dragging sends a normalized `drag`. The Back, Home, Recents, and notification-shade buttons send global Android actions. The admin token stays only in the current browser tab and is not written to browser storage.

The Pi-hosted HTTP controller works immediately inside the encrypted Tailnet. A separately hosted HTTPS controller must use the Pi's HTTPS Tailscale Serve address because browsers block HTTPS pages from fetching an HTTP API. After enabling Tailscale Serve, use `https://ricky.dusky-parore.ts.net` as the controller's API base URL.

## Intelligence endpoint

The intelligence layer is optional and is disabled until these are added to `~/.config/logiclick/environment` on the Pi:

```bash
LOGICLICK_INTELLIGENCE_URL=https://your-intelligence-api.example/propose
LOGICLICK_INTELLIGENCE_TOKEN=optional-secret-for-that-api
```

Then restart `logiclick.service`. The web controller calls:

```http
POST /v1/devices/phone-1/intelligence/propose
Authorization: Bearer YOUR_ADMIN_TOKEN
Content-Type: application/json

{"goal":"Open the calculator"}
```

The Pi sends the configured intelligence URL the goal, device ID, current JPEG as Base64, capture time, and a response contract. That endpoint must return:

```json
{
  "rationale": "The calculator icon is centered near the bottom.",
  "confidence": 0.91,
  "action": {
    "type": "tap",
    "coordinateSpace": "normalized",
    "area": {"left": 0.45, "top": 0.82, "right": 0.55, "bottom": 0.90},
    "holdMs": {"min": 60, "max": 100}
  }
}
```

The Pi validates the returned action against the same gesture schema before showing it. By default it is only a proposal: the human must click **Approve & execute**. Turning off **Require human approval** allows the web controller to queue a valid proposal automatically; use that only on a supervised device you own.

## Connect any API as the input source

Choose **Custom endpoint** in the app to poll an exact URL instead of the built-in Raspberry Pi route. The custom API may be implemented by any backend, workflow platform, home-automation system, or API gateway. Logiclick sends:

```http
GET https://your-api.example/next-action
Accept: application/json
Authorization: Bearer OPTIONAL_TOKEN
```

Return `204 No Content` when no action is waiting. When an action is available, return `200 OK` and the same signed envelope used by the Pi:

```json
{
  "commandId": "cmd_2af51c6ce2ee4e058154bb10b3fd0af1",
  "issuedAtEpochMs": 1791917800000,
  "expiresAtEpochMs": 1791917860000,
  "nonce": "a-random-value-at-least-12-characters",
  "payload": "BASE64_OF_UTF8_ACTION_JSON",
  "signature": "BASE64_ED25519_SIGNATURE"
}
```

The signature covers these five values joined by newline characters, in order: `commandId`, `issuedAtEpochMs`, `expiresAtEpochMs`, `nonce`, and `payload`. Configure the matching Base64 X.509 Ed25519 public key in the app. A custom source may omit the bearer token because signature verification remains mandatory. It must use HTTPS, except that a Tailscale `100.64.0.0/10` HTTP address is accepted because its traffic is carried inside the encrypted Tailnet.

Export these values on a Tailnet-connected computer to use the examples:

```bash
export LOGICLICK_URL='http://100.93.193.74:8765'
export LOGICLICK_DEVICE='phone-1'
export LOGICLICK_ADMIN_TOKEN='copy LOGICLICK_ADMIN_TOKEN from the Pi environment file'
```

## Coordinate model and randomness

Use `"coordinateSpace":"normalized"` for portable coordinates. Both axes run from `0.0` at the top/left to `1.0` at the bottom/right. For example, the screen center is `{left:0.5, top:0.5, right:0.5, bottom:0.5}`.

Every point is an **area**, not only a fixed coordinate. The phone independently chooses a random point inside the inclusive rectangle. Timing objects work the same way: the phone chooses a duration between `min` and `max` milliseconds. To disable randomness, make both edges or both timing values equal.

Pixel coordinates are also supported with `"coordinateSpace":"pixels"`, but normalized coordinates work across different Android screen sizes.

## Tap

```bash
curl -sS "$LOGICLICK_URL/v1/devices/$LOGICLICK_DEVICE/commands" \
  -H "Authorization: Bearer $LOGICLICK_ADMIN_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{
    "type":"tap",
    "coordinateSpace":"normalized",
    "area":{"left":0.47,"top":0.47,"right":0.53,"bottom":0.53},
    "holdMs":{"min":60,"max":110}
  }'
```

## Long press

```json
{
  "type": "long_press",
  "coordinateSpace": "normalized",
  "area": {"left": 0.49, "top": 0.49, "right": 0.51, "bottom": 0.51},
  "holdMs": {"min": 650, "max": 900}
}
```

## Double tap

```json
{
  "type": "double_tap",
  "coordinateSpace": "normalized",
  "area": {"left": 0.48, "top": 0.48, "right": 0.52, "bottom": 0.52},
  "holdMs": {"min": 50, "max": 80},
  "gapMs": {"min": 90, "max": 140}
}
```

## Swipe or drag

`swipe`, `drag`, `scroll`, and `fling` use the same path primitive; the name communicates intent to API callers.

```json
{
  "type": "swipe",
  "coordinateSpace": "normalized",
  "startArea": {"left": 0.45, "top": 0.78, "right": 0.55, "bottom": 0.82},
  "endArea": {"left": 0.45, "top": 0.18, "right": 0.55, "bottom": 0.22},
  "durationMs": {"min": 300, "max": 550}
}
```

## Arbitrary path and multi-touch

Use `gesture` for curved/polyline motion, delayed strokes, or simultaneous fingers. Each stroke accepts 1–64 point areas. A one-point stroke is a press; two or more points form a path. Up to 10 strokes may be submitted in one Android gesture.

This example performs a two-finger pinch. Both strokes start together:

```json
{
  "type": "gesture",
  "coordinateSpace": "normalized",
  "strokes": [
    {
      "startDelayMs": 0,
      "durationMs": {"min": 350, "max": 450},
      "points": [
        {"left": 0.18, "top": 0.48, "right": 0.22, "bottom": 0.52},
        {"left": 0.43, "top": 0.48, "right": 0.47, "bottom": 0.52}
      ]
    },
    {
      "startDelayMs": 0,
      "durationMs": {"min": 350, "max": 450},
      "points": [
        {"left": 0.78, "top": 0.48, "right": 0.82, "bottom": 0.52},
        {"left": 0.53, "top": 0.48, "right": 0.57, "bottom": 0.52}
      ]
    }
  ]
}
```

## Global Android actions

Supported actions are `back`, `home`, `recents`, `notifications`, `quick_settings`, and `dismiss_notification_shade`.

```json
{"type":"global_action","action":"back"}
```

## Launch an app

```json
{"type":"launch_app","packageName":"com.google.android.youtube"}
```

System Settings, permission controllers, and package installers cannot be launched or touched through Logiclick.

## Optional command-line client

The included client is convenient for basic actions:

```bash
python3 server/logiclickctl.py --url "$LOGICLICK_URL" --token "$LOGICLICK_ADMIN_TOKEN" tap 0.47 0.47 0.53 0.53
python3 server/logiclickctl.py --url "$LOGICLICK_URL" --token "$LOGICLICK_ADMIN_TOKEN" swipe 0.45 0.78 0.55 0.82 0.45 0.18 0.55 0.22
python3 server/logiclickctl.py --url "$LOGICLICK_URL" --token "$LOGICLICK_ADMIN_TOKEN" global home
python3 server/logiclickctl.py --url "$LOGICLICK_URL" --token "$LOGICLICK_ADMIN_TOKEN" launch com.google.android.youtube
```

For arbitrary/multi-touch gestures, use `raw` with the JSON shown above.

## Other API routes

```text
GET /v1/health
GET /v1/public-key
GET /controller/                                  # private web controller
GET /v1/devices/phone-1/commands/next   # Android app; device token required
POST /v1/devices/phone-1/commands       # controller; admin token required
POST /v1/devices/phone-1/mirror/frame   # Android JPEG upload; device token required
GET /v1/devices/phone-1/mirror/latest   # latest JPEG; admin token required
GET /v1/devices/phone-1/mirror/status   # frame metadata; admin token required
POST /v1/devices/phone-1/intelligence/propose # validated proposal; admin token required
```

`POST` returns `201 Created` with the command ID, expiry, and queue position. A command defaults to a 60-second lifetime. Add `"ttlSeconds":1..120` to change it. The queue holds at most 100 undelivered commands and delivers each command once.

## Build and install the Android app

The verified Android 0.4.0 debug APK is attached to the [GitHub v0.4.0 release](https://github.com/Rickymorty7x/Logiclick/releases/tag/v0.4.0). Its SHA-256 is:

```text
a56aa31e88af37fcf1bb69fe7dd689535e84e89a9edd38253fcc8e2230098f6d
```

Repository QR:

![Logiclick GitHub repository QR code](docs/logiclick-github-qr.png)

Direct public APK download QR:

![Logiclick Android APK QR code](docs/logiclick-apk-qr.png)

Open this project in current Android Studio with Android SDK 36 and JDK 17, then build the `app` debug or release APK. The minimum supported Android version is Android 11 (API 30).

After installation:

1. Open Logiclick. The Pi endpoint, device ID, and signing public key are already filled in; enter the device token from the Pi.
2. Tap **Enable Accessibility Service** and enable Logiclick manually in Android Settings.
3. Confirm the screen mirror URL and bearer token, then tap **Save connection settings**.
4. Enable **Receive signed action commands**.
5. Enable **Send screen mirror** and approve Android's screen-capture dialog. It remains active while the device is on; after reboot, use the resume notification to approve again.
6. Keep the phone unlocked while sending commands.
7. Use the notification **STOP** action or the in-app **STOP AND DISARM** button to end control immediately.

## Limits

- Accessibility gestures cannot unlock the device, bypass lock screens, defeat `FLAG_SECURE`, or guarantee interaction with every custom-rendered Android surface.
- Android may limit the number or shape of simultaneous paths on a particular device build.
- Mirroring is intentionally a replace-in-place JPEG feed, not a video recorder. Android redacts `FLAG_SECURE` windows, and a reboot always requires fresh MediaProjection approval.
- This build does not inject shell commands, enter text, approve permissions, install apps, or touch Android security UI.
- Publishing an accessibility-based automation app through Google Play requires policy declarations, prominent disclosure, and informed consent.
