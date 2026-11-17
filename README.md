# Ultra2 Control — Android app for Bose QC Ultra Earbuds (2nd Gen)

**Ultra2 Control** (repository `boseAndroidMgr`) is an Android app that shows the battery level and switches the noise cancelling mode and the
equalizer of the **Bose QuietComfort Ultra Earbuds (2nd generation)**. It is the Android
counterpart of [BoseMenuBar](https://github.com/guilleml/BoseMenuBar) (macOS).

> Unofficial project, not affiliated with Bose Corporation. "Bose" and "QuietComfort" are trademarks
> of their respective owners. The protocol was documented by observing the traffic between the
> official app and a pair of earbuds owned by the author, for interoperability purposes. Use at your
> own risk.

## Features

- **Battery** of the left and right earbuds and the case.
- **Mode selector**: Quiet, Aware, Immersion (shows the name of a custom mode if one is active).
- **Equalizer**: 3-band curve you can drag (sent on release), plus −/+ buttons and numeric fields.
  Always shows the values confirmed by the earbuds.
- **Pull to refresh** re-reads everything (or reconnects).
- **Automatic reconnection** while the app is on screen: retries after 2, 5, 10 and 30 s, then waits
  for the system to report that the earbuds have connected.
- **Quick Settings tile**: shows the last known mode and battery; tap it to pick a mode in a small dialog.

**Battery first**: the control channel is only open while the app (or the tile dialog) is on screen.
In the background there is no connection, polling or retry, and the tile never opens the channel by
itself. With the channel open, the earbuds were observed to take ~2.5 min longer to power off in the
closed case.

The app's user interface is currently in Spanish.

## Install

1. Download `boseAndroidMgr-<version>.apk` from the [latest release](https://github.com/guilleml/boseAndroidMgr/releases/latest).
   It requires Android 12 or later.
2. Open it on the phone. Android asks you to allow installing apps from that source (the browser or
   the file manager) the first time.
3. Pair the earbuds with the phone in the system Bluetooth settings, if they aren't already.
4. Open the app and allow **Nearby devices** (Bluetooth) when asked.
5. Optional: edit the Quick Settings panel and drag the **Ultra2 Control** tile into it.

Release APKs are signed with this certificate (SHA-256), so you can check them with
`apksigner verify --print-certs`:

```
55:2B:CC:68:73:39:9B:A5:96:8A:19:5C:46:54:16:F8:B3:D8:C9:8F:D7:2B:22:85:E3:8F:8B:CB:47:1E:30:29
```

## Protocol

Documented in BoseMenuBar's [`docs/protocol.md`](https://github.com/guilleml/BoseMenuBar/blob/main/docs/protocol.md).
Classic Bluetooth, RFCOMM (SerialPort service, UUID `0x1101`); no initialization sequence is needed.

The protocol code (`app/src/main/java/.../protocol/`) is plain Kotlin with no Android dependencies
and is covered by JVM unit tests built from captured messages.

## Build

Requirements: JDK 17+ and the Android SDK (platform 37). Android Studio works, or the command-line tools.

```sh
./gradlew :app:testDebugUnitTest   # protocol unit tests
./gradlew :app:installDebug        # build and install on a connected device
adb logcat -s BoseUltra            # hex traffic log
```

The earbuds must already be paired with the phone. `minSdk` is 31 (Android 12).

`./gradlew :app:assembleRelease` builds a minified release APK. It is signed only if
`BOSE_RELEASE_STORE_FILE`, `BOSE_RELEASE_STORE_PASSWORD`, `BOSE_RELEASE_KEY_ALIAS` and
`BOSE_RELEASE_KEY_PASSWORD` are defined (e.g. in `~/.gradle/gradle.properties`); otherwise it is unsigned.

## License

GPL-3.0. See [LICENSE](LICENSE).
