# Tracky for Android

A local-first native Android weigh-in instrument for one Tracky TS-CS10B / TR-CS10B scale. It scans for the observed `QN-Scale`, connects directly over BLE, performs the capture-backed FFF0/FFF1/FFF2 handshake, displays each live weight observation, and stores the complete ordered RX/TX evidence locally.

## Current slice

- Material 3 Expressive single-activity UI with dynamic color, curated light/dark palettes, edge-to-edge layout, expressive motion/loading, accessible instrument states, history, evidence detail, and major-state previews.
- Explicit permission, scan, connection, service discovery, notification, handshake, measurement, stopping, disconnection, Bluetooth-off, and recoverable-failure states.
- Pure Kotlin CS10B checksum, command builders, frame decoder, and handshake state machine, covered by sanitized fixture goldens.
- Foreground-only Android `BluetoothGatt` transport with attempt isolation, serialized acknowledged writes, timeouts, deterministic cleanup, and no address exposure in UI or normal logs.
- Room sessions and immutable ordered raw frame evidence. Explicit **Stop & save** closes the recording boundary; cancellation or transport failure retains an aborted partial session.
- Display-only kg/lb conversion. The protocol remains configured for the captured kg path.

The app does **not** average frames or select a final/stable measurement. History surfaces the most recently observed frame only as an observation, never as a scientifically selected result.

## Build

Requirements:

- JDK 17
- Android SDK platform 37 and a compatible Build Tools installation
- Network access for first dependency resolution

Set the SDK path in an uncommitted `local.properties`, then run:

```properties
sdk.dir=/path/to/Android/Sdk
```

```bash
./gradlew testDebugUnitTest assembleDebug lintDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

Pinned build/UI stack:

- Android Gradle Plugin `9.3.2`, Gradle `9.7.1`, Kotlin `2.4.10`, KSP `2.3.11`
- Compose alpha BOM `2026.08.01`
- Compose Material 3 `1.5.0-alpha27`
- Activity Compose `1.13.0`, Lifecycle `2.11.0`, Navigation `2.10.0`
- Room `2.8.4`, coroutines `1.11.0`
- `compileSdk`, `targetSdk`, and `minSdk` 37

Material 3 alpha is intentional: this personal app prioritizes the current official Expressive theme, motion scheme, loading indicator, and wavy progress APIs. See the official [Material 3 releases](https://developer.android.com/jetpack/androidx/releases/compose-material3) and [API reference](https://developer.android.com/reference/kotlin/androidx/compose/material3/package-summary).

## Protocol and evidence

The behavioral reference is [`eggmasonvalue/tracky-mcp`](https://github.com/eggmasonvalue/tracky-mcp) at commit `473b39bc63179fc626406ee4e2448403d157002e`. Exact fixture provenance is recorded in [`docs/PROTOCOL_PROVENANCE.md`](docs/PROTOCOL_PROVENANCE.md).

Capture-backed behavior:

- advertised name `QN-Scale`; FFE0/FFF0 advertising is a candidate signal,
- connected service FFF0, FFF1 notifications, FFF2 writes,
- `0x12` info → unit/time writes → `0x14` → time sync → `0x21` → two `0xA0` writes → repeated `0x10` notifications,
- for the confirmed 18-byte `0x10` layout, unsigned BE bytes 3–4 divided by 100 are kilograms,
- bytes 5 and 6 and all five BE words at bytes 7–16 are preserved.

The five-word block is unitless raw evidence. Its electrical units, frequencies, body segments, and physiological meaning are unknown; an all-zero block is valid recorded evidence. No body-composition formulas or medical claims are implemented.

## Verification status

Verified in the development environment:

- dependency resolution and Material 3 `1.5.0-alpha27` selection,
- debug Kotlin/Java compilation, Room KSP generation, Compose preview compilation,
- JVM protocol, BLE policy, and data invariant tests,
- debug APK assembly and Android lint,
- dependency graph inspection for accidental vendor/cloud/analytics SDKs.

Not yet verified on hardware:

- Android 17 permission/system-dialog behavior on the Pixel 10,
- scanning, connection timing, GATT callback behavior, and handshake writes against the physical scale,
- visual motion, dynamic color, accessibility, and haptic feel on the target phone.

The smallest next step is to install the debug APK on the Pixel 10, keep the app foregrounded, wake the scale, perform one short weigh-in, tap **Stop & save**, and compare the saved ordered RX/TX hex against the reference handshake while confirming that no Bluetooth address appears in UI or logs.

## License

MIT. No vendor SDK, cloud service, telemetry, ads, authentication, or opaque body-composition implementation is included.
