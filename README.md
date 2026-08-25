# Regatta Tracker

Regatta Tracker is an Android app for sailors participating in a tracked regatta. It records GPS-based race progress, detects start-line state including OCS, shows the current course target, and can upload tracking samples to the configured race server.

## What the app does

- Stores boat setup locally.
- Loads race and course data from a race server.
- Starts race tracking after explicit confirmation.
- Shows countdown before the start.
- Shows OCS clearly when detected.
- Shows the next target and distance on the home screen.
- Shows the course description and course map.
- Uploads race tracking samples during a race.
- Keeps manual training data local.
- Provides export and local data cleanup tools in Advanced.

## First start

On first use, Android will ask for location permission. The app also asks for explicit GPS tracking consent before starting race tracking or manual tracking.

These are separate confirmations:

- Android location permission
- GPS tracking consent inside the app

Both are required for race tracking.

## Basic workflow

### 1. Boat setup

Open:

```text
Setup → Boat
```

Enter and confirm:

- Boat name
- Skipper
- Hull color
- Sail number
- Boat type
- Yardstick

After pressing **Confirm Setup**, the Boat button on the home screen turns blue.

Boat setup is locked while a race is running, so the server does not receive changing boat data during a race.

### 2. Race setup

Open:

```text
Setup → Race
```

Use **Scan QR Code** or enter manually:

- Server URL
- Event
- Secret

Then press **Load race data**.

When valid race data has been loaded, **Enter Race** becomes available. If boat setup is not confirmed or race data is invalid, Enter Race stays disabled.

### 3. Enter race

When pressing **Enter Race**, the app shows a confirmation dialog with the current boat data.

Confirm only if the displayed boat data is correct.

After entering the race:

- tracking starts,
- race data is refreshed regularly,
- upload starts,
- boat setup and race setup are locked.

### 4. During the race

The home screen shows:

- GPS status
- Race status / start time
- Upload state
- Next target
- Distance to the next target
- Race information, if available
- Course shortened warning, if applicable

The app detects:

- pre-start OCS
- start-line crossing
- passed marks
- finish-line crossing

### 5. Retire / Finish

In the Race screen, **Retire / Finish** stops race tracking.

Use this only if:

- you have finished, or
- you are retiring from the race.

The app shows a warning before stopping tracking.

## Home screen states

### Setup buttons

- **Boat** is yellow until boat setup is confirmed.
- **Boat** is locked during a race.
- **Race** is yellow until the app has entered the race.
- **Race** turns blue when the race is active.

### Upload

- `off`: not in race, no upload expected.
- `OK`: in race, no relevant upload backlog.
- number: pending upload samples.

### Map and Course

After valid race data is loaded, the event section shows:

```text
Event: <event name>
[Course] [Map]
```

- **Course** shows the course description.
- **Map** shows the static race map image from the server.

## Advanced

Advanced contains technical tools:

- Start/Stop Manual Tracking
- Upload pending status
- Stored rows
- Last error
- Export Session
- Clear Old Data

Manual tracking data is stored locally and is not automatically uploaded.

## Legal / About

The app includes:

- Legal Notice
- Privacy Policy
- License Notices
- Version
- Build date

Map/geodata attribution is shown there and should also be visible in generated map images.

## Data persistence

The app stores locally:

- confirmed boat setup
- race setup
- last valid race data
- local race UI state, such as passed marks and OCS state
- tracking samples until exported or deleted

This allows recovery after app or phone restart.

Limit: if the phone is off while a mark is passed, the app cannot detect that passage retroactively. It can only restore the last known local race state.

## Troubleshooting

### Enter Race is disabled

Check:

- Boat setup is confirmed.
- Race data was loaded successfully.
- Event name, server URL, and secret are correct.

### Upload is not working

Open Advanced and check:

- Pending
- Last error

Common causes:

- wrong server URL
- wrong endpoint
- wrong event name
- wrong shared secret
- server not reachable
- API version mismatch

### Map does not load

Check:

- Race data is loaded.
- Server supports `/course-map`.
- Secret and event name are correct.
- Server returns `image/png`.

### GPS is bad

Go outside or improve phone sky visibility. Poor GPS accuracy can delay start-line, mark, and finish detection. Do not leave your phone inside, GPS signal will be degraded!

## Notes

This app is intended as a race participant client. It is not a race committee scoring system by itself.

## License

The Android app in this repository is licensed under the GNU General Public License v3.0 or later.

The app can be configured to use any compatible server. The reference/private server is not part of this repository.

See [LICENSE](LICENSE).