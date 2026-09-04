# Tracking energy measurement and validation protocol

This document is the reproducible measurement protocol for RegattaTracker-Public #68, Task 4.

The implementation can be validated automatically for functional sampling rules, persistence, localization and payload semantics. Actual energy consumption, GPS power behavior and wakeups must be measured on a physical Android device; those results must not be inferred from unit tests or emulator runs.

## Preconditions

Use one physical Android device for all comparable runs where possible.

Record before every run:

- device model
- Android version
- app build / commit SHA
- battery health if known
- initial battery percentage
- charging state (must normally be unplugged during measurement)
- screen state/brightness policy
- network type and signal conditions
- event/course used
- sail number / whether the run is a `MARK:` sender
- selected tracking profile
- server reachable or intentionally unreachable
- start/end time and run duration

Use Android Studio Power Profiler and/or System Trace. ADB/batterystats may be added as supporting evidence, but the same collection method must be used for comparable runs.

Minimum run duration: **30 minutes**. Preferred duration: **60 minutes**.

## Required comparison matrix

Run at least the following scenarios:

| ID | Client role | Profile | Server | Expected sampling behavior |
| --- | --- | --- | --- | --- |
| B0 | participant | pre-#68 baseline | reachable | previous implementation |
| N1 | participant | Normal | reachable | 1 / 2 / 5 / 10 s by geometry |
| S1 | participant | Battery Saver | reachable | 2 / 10 / 30 / 60 s by geometry |
| M1 | `MARK:` sender | Normal | reachable | fixed 30 s |
| M2 | `MARK:` sender | Battery Saver | reachable | fixed 60 s |
| N0 | participant | Normal | unreachable | same sampling rules; local persistence continues |
| S0 | participant | Battery Saver | unreachable | same sampling rules; local persistence continues |

If a pre-#68 baseline APK/build is no longer available, document that explicitly and compare against the last known pre-change build rather than inventing a baseline.

## Geometry test course

The course used for the participant runs must make all four distance bands observable. Record GPS positions or planned route sections that cover:

- `< 250 m` from a relevant course element
- `250 m .. 1 nm`
- `1 .. 10 nm`
- `> 10 nm`

At least one route section must pass close to a **later** course mark while the client UI still displays another mark as `Next`. The sampling interval must follow the geometrically nearest relevant course element and must not follow `passed_marks` or the displayed `Next` target.

Start and finish lines must also be exercised close to an endpoint so that the line **segment** distance, not distance to an infinite extension of the line, is validated.

## Manual progress independence

During a stable route section:

1. Record current position, selected profile and observed sampling interval.
2. Manually change course progress in the client.
3. Do not change course geometry or tracking profile.
4. Verify that the sampling interval remains in the same geometry-derived band.
5. Restore progress if needed for the rest of the test.

A manual progress correction must not reduce or increase the sampling rate by itself.

## Hysteresis validation

Near each threshold (250 m, 1 nm, 10 nm):

1. Approach the threshold from the inner/faster band.
2. Move slightly outside it and remain close to the boundary.
3. Confirm that normal GPS jitter does not repeatedly switch the interval back and forth.
4. Move clearly beyond the outward hysteresis threshold and confirm the slower band is selected.
5. Approach inward again and confirm the faster band is selected when the nominal boundary is crossed.

Record the observed transition distances.

## MARK validation

For a sail number starting exactly with `MARK:`:

- Normal must use a fixed 30 s interval.
- Battery Saver must use a fixed 60 s interval.
- Proximity to start line, finish line or a course mark must not change those intervals.
- Local progress changes must not change those intervals.

Repeat with a normal sail number to confirm the participant policy is restored.

## Battery and tracking-profile telemetry

During each relevant run, verify the stored/uploaded telemetry:

- `battery_percent` is present approximately every 60 s, not on every GPS sample.
- `battery_charging` represents the device state at the same sample time.
- `tracking_profile` is present on the first generated sample of the tracking session, on the next generated sample after a profile change, and approximately every 60 s as a heartbeat.
- Samples between those status updates may omit these fields.

### Offline correctness

For at least one unreachable-server run:

1. Track long enough to create several battery/profile status samples.
2. Change the tracking profile at least once while offline.
3. Record the device battery/profile state at the relevant sample times.
4. Restore server/network availability.
5. Allow pending samples to upload.
6. Verify uploaded `battery_*` and `tracking_profile` values equal the values persisted with those historical samples, not the state at upload time.

The upload path remains single-sample in #68. Batch/backlog optimization belongs to #69.

## Energy measurements

For every scenario, capture at least:

- total energy / battery consumption
- CPU activity
- wakeups
- GPS/location activity
- Sensor Core / IMU activity
- network activity as supporting value
- number of locally generated samples
- resulting track resolution
- duration of the run

Use equivalent route/activity conditions for comparisons wherever practical.

### Result table

| Scenario | Duration | Battery start/end | Energy | CPU/Wakeups | GPS | IMU | Network | Samples | Notes |
| --- | ---: | --- | --- | --- | --- | --- | --- | ---: | --- |
| B0 | | | | | | | | | |
| N1 | | | | | | | | | |
| S1 | | | | | | | | | |
| M1 | | | | | | | | | |
| M2 | | | | | | | | | |
| N0 | | | | | | | | | |
| S0 | | | | | | | | | |

Do not compare raw battery-percentage loss alone when run durations, signal conditions or device state differ materially.

## Course-detection regression checks

For normal participants verify that the reduced sampling rate does not break:

- local start detection
- OCS handling
- mark detection
- finish detection
- manual progress correction
- offline recording
- later upload of all locally generated samples

All automated course-detection/tracking tests must remain green in CI.

## Additional energy consumers to measure

These are measurement targets only in #68 Task 4. Material changes belong in separate follow-up issues.

### IMU

Accelerometer and gyroscope currently use `SENSOR_DELAY_GAME`. Measure Sensor Core/CPU impact and whether the application gains useful information from that rate, given that a telemetry point stores only the latest sensor values.

### `/event` polling

The tracking service polls `/event` every 10 s. Measure network/wakeup contribution separately from GPS/sample generation.

### Work after finish

The service may remain active for up to five minutes after a locally detected finish. Measure which components remain active and their cost.

## Functional evidence available from CI

The branch for #68 contains automated tests for:

- persisted profile value/fallback semantics
- exact Normal and Battery Saver interval tables
- geometrically nearest later mark controlling the interval
- line-segment distance behavior
- hysteresis behavior
- fixed MARK intervals
- safe fastest-band fallback when course geometry is unavailable
- battery/profile 60 s cadence policy
- profile emission after a profile change
- optional upload payload fields
- historical sample metadata remaining unchanged after the current profile changes
- DB v4 -> v5 nullable metadata migration

These tests prove the policy and persistence semantics. They do **not** substitute for the physical-device energy measurements above.

## Task 4 completion gate

Task 4 is complete only when:

- the required physical scenarios have documented results,
- energy consumption and track resolution are compared,
- progress independence is verified on-device,
- MARK behavior is verified on-device,
- reachable/unreachable-server behavior is covered,
- relevant IMU, event-polling and post-finish observations are recorded,
- any material additional optimization is moved to a separate follow-up issue rather than added to #68 scope.
