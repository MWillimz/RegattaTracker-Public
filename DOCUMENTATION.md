# Regatta Tracker Technical Documentation

## Overview

Regatta Tracker is an Android/Kotlin app using Jetpack Compose. It tracks a boat during a regatta, uploads tracking samples to a server, and displays race/course state to the user.

Core responsibilities:

- boat setup
- race setup
- GPS tracking
- local race-state calculation
- OCS detection
- mark progress
- finish detection
- upload retry queue
- static course map display
- local recovery after app or phone restart

Package:

```text
de.williserv.regattaclient
```

## Main files

```text
MainActivity.kt
HomeScreen.kt
SetupScreens.kt
CourseScreen.kt
MapScreen.kt
LegalScreen.kt
QRScannerScreen.kt
RegattaTrackingService.kt
StartLineMath.kt
TrackingDbHelper.kt
```

## UI structure

### Home

Home is intentionally simple and visual.

It shows:

- large status panel
- OCS / countdown / general tracker state
- next target
- distance
- GPS / race / upload status
- Setup buttons
- Event-specific Course/Map buttons
- Advanced / Legal

### Setup

Setup is split into:

```text
Boat
Race
```

Boat setup contains:

- boat name
- skipper
- hull color
- sail number
- boat type
- yardstick

Race setup contains:

- server URL
- event name
- shared secret
- QR code scan
- load race data
- enter race
- retire / finish
- clear race setup

### Racecourse

Racecourse is event-specific.

Screens:

```text
Course
Map
```

Course displays:

- event
- status
- start
- stop
- course shortened
- start line
- marks
- finish line
- race info

Map displays a static PNG loaded from the server.

## Server API

The client expects API version:

```text
x-api-version: v1
```

The app also sends:

```text
x-event-name: <event name>
x-shared-secret: <secret>
```

The Android client is configured to reject cleartext HTTP traffic. Compatible production servers must therefore be reachable via HTTPS.

### Event endpoint

Request:

```http
GET /event?event_name=<event>&shared_secret=<secret>
Accept: application/json
x-api-version: v1
```

Expected relevant response fields:

```json
{
  "event_name": "example-regatta",
  "start_time": "2026-05-16T12:00",
  "stop_time": null,
  "race_info": "Important information for participants",
  "race_status": "racing",
  "course_shortened": false,
  "course": {
    "start_line": {
      "ref": {
        "lat": 54.0000,
        "lon": 10.0000,
        "label": "Start Referee"
      },
      "mark": {
        "lat": 54.0000,
        "lon": 10.0010,
        "label": "Start Buoy"
      }
    },
    "finish_line": {
      "ref": {
        "lat": 54.0000,
        "lon": 10.0000,
        "label": "Finish Referee"
      },
      "mark": {
        "lat": 54.0000,
        "lon": 10.0010,
        "label": "Finish Buoy"
      }
    },
    "marks": [
      {
        "order": 1,
        "name": "Mark 1",
        "lat": 54.0020,
        "lon": 10.0030,
        "radius_m": 100
      }
    ]
  }
}
```

Notes:

- `start_time` is treated as local/naive time if no timezone suffix is present.
- UTC or offset timestamps are also supported.
- Race data is refreshed periodically while in race.
- Race data is persisted locally after successful load.

### Ingest endpoint

Request:

```http
POST /ingest
Content-Type: application/json
Accept: application/json
x-event-name: <event name>
x-shared-secret: <secret>
x-api-version: v1
```

Payload fields:

```json
{
  "sequence_id": 1,
  "timestamp": "2026-05-16T12:05:52",
  "boat_name": "Boat name",
  "captain_name": "Max Mustermann",
  "hull_color": "white",
  "sail_number": "GER 1234",
  "yardstick": 100.0,
  "boat_type": "J/22",
  "lat": 54.0010,
  "lon": 10.0020,
  "accuracy": 5.0,
  "cog": 120.0,
  "sog": 2.4,
  "accel_x": 0.0,
  "accel_y": 0.0,
  "accel_z": 0.0,
  "gyro_x": 0.0,
  "gyro_y": 0.0,
  "gyro_z": 0.0
}
```

The client builds the ingest URL from the configured base server URL:

```text
<base>/ingest
```

If the configured URL ends with `/ingest`, the app strips it before rebuilding endpoint URLs.

### Course map endpoint

Request:

```http
GET /course-map?event_name=<event>&shared_secret=<secret>
Accept: image/png
x-api-version: v1
```

Expected response:

```text
Content-Type: image/png
```

The app decodes the response as a PNG bitmap and shows it in `MapScreen`.

Recommended server behavior:

- render course map once per event/race
- cache PNG server-side
- include attribution directly in the image
- include start line, finish line, marks, labels, and course geometry

## QR code format

The app expects a JSON QR code:

```json
{
  "server": "https://regatta.example.org",
  "event": "example-regatta",
  "secret": "example-secret"
}
```

The server field should normally be the HTTPS base URL. Both these forms are tolerated:

```text
https://regatta.example.org
https://regatta.example.org/ingest
```

The app normalizes the URL internally. Cleartext HTTP is disabled by the Android application configuration.

QR camera frames are decoded locally in the app using ZXing Core. They are not intentionally stored or uploaded as part of QR scanning.

## Local storage

### SharedPreferences

Used for:

- boat setup
- race setup
- consent
- local race status
- persisted UI race state

Important preferences:

```text
boat_setup
race_setup
regatta_consent
regatta_local_status
regatta_race_state
```

Race setup includes the configured server credential/shared secret.

### SQLite

`TrackingDbHelper.kt` stores tracking samples in:

```text
regatta_tracking.db
tracking_samples
```

Stored fields include:

- sequence id
- timestamp
- boat data
- boat type
- GPS position
- accuracy
- COG / SOG
- accelerometer
- gyroscope
- uploaded flag

Pending samples are retried periodically.

Manual tracking samples are marked uploaded locally and are not transmitted automatically.

### Android backup

App-local configuration, credentials, databases and tracking data are excluded from Android cloud backup and device-transfer backup mechanisms. The manifest also disables Android application backup.

## Race state calculation

Implemented mainly in:

```text
RegattaTrackingService.kt
StartLineMath.kt
```

### Start line

The start line is treated as a virtual infinite line through:

```text
start_line.ref
start_line.mark
```

The app calculates signed distance to this line.

### OCS

Before start time, OCS is determined by comparing the boat side of the start line with the first mark side.

If the boat is on the course side before the start:

```text
OCS = true
```

If the boat crosses back to the pre-start side:

```text
OCS = false
```

A tolerance zone prevents noisy toggling near the line.

### Start

After start time, a valid line crossing starts the local race state:

```text
raceStarted = true
```

### Marks

Marks are passed when the current GPS point is within `radius_m` of the next mark.

The local progress counter:

```text
passedMarks
```

is incremented.

### Finish

After all marks are passed, the finish line is treated like the start line. A crossing sets:

```text
raceFinished = true
```

### Recovery after restart

The service persists UI race state locally:

```text
eventName
sailNumber
raceStarted
raceFinished
passedMarks
isOcs
saved_at
```

This is for UI recovery only. It does not reconstruct positions while the phone was off.

If the event or sail number no longer matches, the persisted state is ignored or cleared.

## Permissions

Required Android permissions:

- fine location
- camera
- foreground service/location service as configured in manifest
- notifications on newer Android versions

The app also has its own GPS tracking consent dialog.

## Build configuration

`BuildConfig` is used for:

```text
APP_VERSION_NAME
BUILD_DATE
```

`BUILD_DATE` can be generated in `app/build.gradle.kts` using `LocalDate.now()`.

The API version is defined in:

```kotlin
RegattaTrackingService.API_VERSION
```

Current value:

```text
v1
```

## Map strategy

The app does not use OpenSeaMap tiles directly.

Instead:

1. server renders a static course map PNG,
2. app loads `/course-map`,
3. app displays the PNG,
4. optional local zoom/pan can be added in Compose.

This avoids offline tile cache complexity and reduces tile-provider policy risk.

Attribution should be included in the generated PNG:

```text
© OpenStreetMap contributors · © OpenSeaMap contributors
```

## Git ignore

Recommended ignored files include:

```text
.gradle/
build/
*/build/
.idea/caches/
.idea/workspace.xml
local.properties
*.apk
*.aab
*.jks
*.keystore
.env
secrets.properties
```

Tracked source files, Gradle wrapper, and project configuration should remain committed.

## Operational notes

### Starting a race

The app allows entering a race only when:

```text
boat setup confirmed
race data loaded successfully
```

Before entering, the user must confirm the displayed boat data.

### During race

Locked:

- boat setup
- QR scan
- race server setup
- manual race data reload

Available:

- course
- map
- retire / finish

### Retire / Finish

This stops local race tracking after warning the user.

The warning text should make clear that this is only for finishing or retiring.

### Auto leave after finish

The app can auto-leave race after the local boat status has been `finished` for five minutes.

## Known limitations

- The app cannot detect a mark passed while the phone is off.
- Local persisted progress is UI recovery, not official scoring.
- Static map does not automatically update unless the app reloads it or the server changes the image URL/ETag behavior.
- GPS accuracy can delay or prevent correct line/mark detection.
- Official race evaluation belongs on the server side.

## License

The Android app in this repository is licensed under the GNU General Public License v3.0 or later.

ZXing Core is used for local QR-code decoding and is licensed under the Apache License 2.0.

The app can be configured to use any compatible server. The reference/private server is not part of this repository.

See [LICENSE](LICENSE).
