# DevBridge

An Android app that controls **another Android phone over ADB** — built for phones with a broken screen, flip phones with a cover display, and anyone who wants a quick audit of a phone they own. It contains its own ADB client, so it needs no computer.

You install DevBridge on a **working** phone (the controller) and connect it to the **target** phone.

## What it does

| Feature | How it works |
|---|---|
| **Remote control** | **Live video** like scrcpy: the phone's `screenrecord` H.264 stream is decoded in hardware and drawn in real time, with touches sent as you drag (Android 12+ gets true live dragging via `input motionevent`; older phones get taps and swipes when you lift). A small **helper** is copied to the phone and started automatically (see below) for fast multi-touch input. Falls back to screenshots if live video isn't available (**Live: off**). Works with a dead panel. Includes wake, lock-screen PIN entry, Back / Home / Recents / Power / volume, and text entry. |
| **Flip cover screen** | Set the *input display* (usually `1`) and, optionally, the *screencap id*; **Detect** lists them. **Launch app** starts a package on that display (`am start --display`). Best-effort: it depends on the manufacturer allowing apps on the cover display. |
| **Hardware check** | Model, Android/patch level, bootloader state, battery level/health/temperature, memory, storage, display, CPU, sensors, cameras/NFC/fingerprint/OTG, radios. Flags overheating, low storage, unlocked bootloader. |
| **Permission analysis** | Reads the permissions each third-party app actually holds (`dumpsys package`) and scores them, calling out SMS, call log, microphone, background location, accessibility, device admin, install-apps and more. |
| **Hidden app audit** | Finds user-installed apps with no launcher icon, disabled apps, apps removed with data kept, enabled accessibility services, notification listeners, device admins, and system-lookalike package names. |
| **ADB shell** | Run any `adb shell` command, with history and quick actions. |

## Connecting

1. **USB (OTG).** Plug the target into the controller with an OTG adapter/cable. The target must have **USB debugging on**. Tap *Connect over USB*.
2. **Network.** On the target run `adb tcpip 5555` once from a computer, then enter `ip:5555` and tap *Connect over network*. (Android 11+ *Wireless debugging* uses a different pairing/TLS protocol that this app does not speak yet.)

### If the target's screen is broken

ADB asks the target to approve a new controller **on its screen**, which you can't do. Two ways around it:

- **Import a key it already trusts.** If the phone was ever connected to a computer with ADB and "Always allow" was ticked, copy that computer's `~/.android/adbkey` (Windows: `C:\Users\<you>\.android\adbkey`) to the controller and use **Import adbkey…**. The target then accepts DevBridge with no prompt. The file must be PKCS#8 (`-----BEGIN PRIVATE KEY-----`); if yours says `BEGIN RSA PRIVATE KEY`, convert it: `openssl pkcs8 -topk8 -nocrypt -in adbkey -out adbkey.pk8`.
- **Use an OTG mouse** on the target to tap *Allow* on the prompt, if the part of the screen that shows it still works.

DevBridge cannot turn USB debugging on for a phone where it is off, and cannot bypass a lock screen — it can only type a PIN you supply.

## Responsible use

DevBridge only controls a phone that has explicitly authorised it (USB debugging enabled plus an accepted or imported key). Use it on devices you own or are permitted to manage. The audit features exist to help you find unwanted apps on your own phone.

## The on-phone helper

When you open Remote control, DevBridge copies its own APK to `/data/local/tmp` on the target and starts a helper from it with `app_process` over ADB — the same technique scrcpy uses. Nothing is installed, it runs with the same rights as `adb shell`, and it exits the moment you disconnect or close the screen. The helper injects touches, keys and text directly into Android's input system, so there is no per-event process start-up: fast, with real **multi-touch** (pinch, two-finger scroll) and proper text entry. If the helper can't start (very old Android, or a vendor build that blocks the hidden input API), DevBridge says why and falls back to the slower `input` commands.

Video has no 3-minute limit: `screenrecord` stops itself after about three minutes, so DevBridge starts the next recording shortly before that and switches over at its first key frame.

## Limits

- Live video still uses the phone's `screenrecord`, so it can't show protected content (some banking apps, DRM video), and after a rotation you need to tap **↻**. Two recordings overlap briefly at each hand-over, which a few phones may not allow; if so the picture pauses for about a second instead.
- The helper depends on a hidden Android API that vendors occasionally change, so it can't be guaranteed on every phone. It is untested on real hardware as of this release.
- Analysis relies on `dumpsys`/`pm` output, which varies between Android versions and manufacturers; sections degrade to "not reported" rather than failing.

## Building

Needs JDK 17 and the Android SDK. `gradle assembleDebug` builds an installable APK; CI builds one on every push (download it from the workflow run's artifacts). Tagging `v*` runs the release workflow, which signs with these repository secrets: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.

The ADB client (`adb/`) and the analysers (`analysis/`) are plain Kotlin and are unit-tested against a scripted fake device over a real socket.
