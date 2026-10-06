# Logiclick Android MVP

Logiclick is a native Android 11+ proof of concept for a narrow, user-configured workflow:

1. Receive an HTTPS API command.
2. Verify its Ed25519 signature, expiry, nonce and workflow name.
3. Confirm that the configured target app is currently visible.
4. Screenshot and OCR only the configured region on-device.
5. Continue only when the OCR text matches the user's rule.
6. Tap the configured input field, enter the numeric value one digit at a time through the accessibility node, wait a bounded interval, and tap the configured action point.

It intentionally does **not** provide arbitrary remote coordinates, scripts, hidden execution, rapid-fire tapping, CAPTCHA handling, security-prompt interaction, or anti-bot evasion.

## Production-preview safeguards

- A persistent low-priority notification clearly shows when automation is armed and includes a one-tap **Stop** action.
- The app records a local 30-entry activity history without storing entered numbers or captured screen content.
- API failures use exponential retry backoff up to one minute rather than continuously polling.
- API settings reject non-HTTPS endpoints, embedded URL credentials, and malformed Ed25519 keys.
- The foreground package is rechecked before input, typing, and the final action.
- Commands remain serialized, short-lived, signed, and replay-protected.

## Current workflow editor

After enabling Logiclick in Android Accessibility settings:

- Enter the target app's Android package name.
- Choose **Open target app and capture for setup**. Logiclick launches it and captures the visible screen after two seconds.
- Return to Logiclick; the visual editor opens with that capture.
- Drag the blue OCR rectangle.
- Select **Tap number field**, then mark the editable number field in green.
- Select **Tap action button**, then mark the final action in red.
- Enter the exact Android package allowlist and an OCR regular expression.
- Save the workflow.

Navigate to the required screen within the two-second capture window. A later production version should replace this countdown with a visible Accessibility overlay button so the user controls the exact capture moment.

## Command API

The configured endpoint is polled while the Accessibility Service is alive and automation is armed. Return `204 No Content` when there is no work or this JSON for one command:

```json
{
  "commandId": "cmd_019",
  "workflowName": "enter-number",
  "number": "4821",
  "issuedAtEpochMs": 1791250000000,
  "expiresAtEpochMs": 1791250060000,
  "nonce": "b6f9a21d4ea748d4",
  "signature": "BASE64_ED25519_SIGNATURE"
}
```

Sign these fields joined by a newline, in this exact order:

```text
commandId
workflowName
number
issuedAtEpochMs
expiresAtEpochMs
nonce
```

The app expects a Base64-encoded X.509 Ed25519 public key. Commands expire within two minutes, accept only 1–18 digits, and command IDs are retained for replay prevention. Digits are paced at 70–160 ms and workflow transitions at 450–900 ms to serialize UI work and avoid flooding the device; these timings are not intended to conceal that automation is occurring.

## Build

Open this directory in a current Android Studio installation with Android SDK 36 and JDK 17. The repository does not yet contain a generated Gradle wrapper because the local workspace did not have Gradle or Android SDK command-line tools installed.

The bundled ML Kit Latin text-recognition model works offline after installation. For other scripts, add the corresponding ML Kit recognizer and expose the model choice in the editor.

## Important constraints

- A PWA cannot perform system-wide taps or read another app's screen.
- The user must manually enable the Accessibility Service.
- Secure windows can block screenshots.
- `ACTION_SET_TEXT` requires the marked target to expose an editable accessibility node. Custom-rendered fields may need a separately disclosed input method.
- Google Play requires an Accessibility API declaration, prominent disclosure and consent. Automation must stay narrow and deterministic.
