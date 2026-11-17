# Bose QC Ultra Earbuds (2nd Gen) — Android control

An Android app that shows the battery level and switches the noise cancelling mode and the
equalizer of the **Bose QuietComfort Ultra Earbuds (2nd generation)**. It is the Android
counterpart of [BoseMenuBar](https://github.com/guilleml/BoseMenuBar) (macOS).

> Unofficial project, not affiliated with Bose Corporation. "Bose" and "QuietComfort" are trademarks
> of their respective owners. The protocol was documented by observing the traffic between the
> official app and a pair of earbuds owned by the author, for interoperability purposes. Use at your
> own risk.

**Status: work in progress.** Battery, mode and equalizer reading and changing work on a Pixel
(Android 17). The full UI, automatic reconnection and a Quick Settings tile are next.

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
