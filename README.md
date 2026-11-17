# Bose QC Ultra Earbuds (2nd Gen) — Android control

An Android app that shows the battery level and switches the noise cancelling mode and the
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

## License

GPL-3.0. See [LICENSE](LICENSE).
