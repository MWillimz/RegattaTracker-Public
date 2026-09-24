<p align="center">
  <img src="app/src/main/ic_launcher-playstore.png" alt="Regatta Tracker" width="120" />
</p>

<h1 align="center">Regatta Tracker</h1>

<p align="center">
  <strong>Open-source Android race companion for tracked sailing regattas.</strong><br />
  Android 11+ · Kotlin · Jetpack Compose · GPL-3.0-or-later
</p>

Regatta Tracker is the participant-side Android client for GPS-supported sailing regattas. It connects to a compatible regatta server, keeps the sailor informed before and during a race, records telemetry locally, and uploads race data when a server connection is available.

The client is intentionally server-agnostic: the reference server is maintained separately and is not part of this repository.

## Highlights

- **Event onboarding** via QR code or shared event link, including race data, server information and event-specific legal/race notices.
- **Boat setup and race registration** with a clear distinction between registering with the race committee and actually entering the race.
- **Race entry close to the start**: `Enter Race` becomes available 24 hours before the scheduled start.
- **On-water race awareness** with start countdown, optional acoustic start/finish signals, OCS indication, next target, distance, course progress and finish detection.
- **Course information and map** supplied by the connected server.
- **Published results** directly in the app when the event server provides them.
- **Reliable telemetry handling**: samples are stored locally first and pending uploads are retried when connectivity returns.
- **Tracking profiles** including a Battery Saver mode that reduces tracking resolution away from relevant course elements.
- **Manual tracking and CSV export** for local recording outside a race; manual sessions are not uploaded automatically.
- **Localized UI** in English, German, French, Spanish and Italian.

> Regatta Tracker provides participant-side tracking and live feedback. Official race evaluation and scoring remain the responsibility of the event server and race committee.

## Typical race workflow

1. **Set up the boat** with sail number, skipper and boat details.
2. **Load an event** from a QR code, shared link or compatible server configuration.
3. **Review the event information** and accept the race notice when required.
4. **Register with the race committee** when supported. This is a pre-registration only and does not start tracking.
5. **Enter the race** within 24 hours of the scheduled start. Race tracking then runs as an Android foreground location service.
6. **Follow the race** using countdown, OCS/start state, course progress, map and current target information while telemetry is queued and uploaded.
7. **Finish or retire** explicitly. Published results can be viewed in the app when available.

## Connectivity and offline behavior

Tracking data is written to the device before upload. Temporary loss of connectivity therefore does not discard already recorded samples; pending race telemetry is retried later.

Local race state is also persisted so the app can recover useful UI state after an app or phone restart. This cannot reconstruct movement that happened while the phone was switched off, and a mark passed while no position was recorded cannot be detected retroactively.

## Compatible servers

Regatta Tracker is designed to work with compatible HTTPS regatta servers rather than one hard-coded backend. A server supplies the event/course data and may additionally provide race notices, operator/legal information, course maps, results and client compatibility metadata.

The current protocol and endpoint details are documented in [DOCUMENTATION.md](DOCUMENTATION.md).

### RegattaLink BLE client contract

The complete public RegattaLink ↔ RegattaTracker BLE wire contract is documented in [docs/REGATTALINK-BLE-CLIENT-CONTRACT.md](docs/REGATTALINK-BLE-CLIENT-CONTRACT.md). It covers security/bonding, all current service and characteristic UUIDs, Device Info capabilities, NMEA2000 PGN inventory and raw-CAN diagnostics, persistent LED brightness, IMU telemetry, normalized NMEA2000 Boat State v1, OTA framing/state/reconciliation, Android GATT rules, compatibility behavior and the remaining explicitly undefined surfaces.

## Privacy and data handling

The app processes location, boat and technical telemetry required for tracking. Race telemetry may be sent to the server configured for the active event; the operator of that server is responsible for its server-side processing and retention.

Manual training sessions remain local unless the user exports them. QR camera frames are processed locally for scanning and are not intentionally stored or uploaded. App-local credentials and tracking data are excluded from Android backup/device-transfer mechanisms.

See the [privacy policy](docs/privacy/) for the full user-facing information.

## Building from source

The project is a standard Gradle Android application using Kotlin and Jetpack Compose.

```bash
./gradlew test
./gradlew assembleDebug
```

The current build targets Android API 36 and supports Android 11 (API 30) and newer. Release signing is configured separately and is not required for a debug build.

For implementation details, API behavior, persistence and race-state logic, see [DOCUMENTATION.md](DOCUMENTATION.md).

## Repository layout

```text
app/                    Android application
app/src/main/           App source and resources
app/src/test/           Unit and regression tests
docs/                   Privacy and supporting documentation
DOCUMENTATION.md        Technical/API documentation
THIRD_PARTY_NOTICES.md  Third-party notices
```

## License

Regatta Tracker is licensed under the [GNU General Public License v3.0 or later](LICENSE).

Third-party components keep their respective licenses; see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) and the notices bundled with the app.
