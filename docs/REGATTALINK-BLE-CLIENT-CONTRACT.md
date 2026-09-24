# RegattaLink BLE client contract

This document is the public, client-facing wire contract between RegattaTracker and current RegattaLink firmware.

It is intended to be sufficient to implement a compatible BLE client without access to the private RegattaLink repository. Any RegattaLink BLE schema or behavioral change that affects clients must update this document together with the corresponding RegattaTracker implementation/tests.

Contract snapshot: 2026-09-24.

## 1. Scope and current implementation status

The current BLE contract contains three RegattaLink service families. Firmware contract availability and current Android consumption are deliberately tracked separately:

| Area | Service suffix | Firmware contract | Current RegattaTracker consumption |
| --- | ---: | --- | --- |
| configuration/device information/diagnostics | 0001 | implemented; 0006 is the additive brightness contract from RegattaLink #135 / PR #136 and is optional by discovery | 0002-0006 consumed; optional surfaces remain feature-local |
| OTA | 0010 | implemented | implemented |
| telemetry | 0020 | implemented with IMU 0021-0023 and normalized NMEA Boat State 0024 | 0021-0024 consumed; 0024 discovered independently of the IMU capability bit |

The firmware contract currently defines four NMEA2000-facing BLE surfaces/behaviors relevant to clients:

- 0004 exposes a compact inventory of PGNs observed on the live NMEA2000 bus;
- 0005 exposes an optional raw received-CAN FIFO for diagnostics;
- 0024 exposes normalized NMEA2000 Boat State v1 as read + notify telemetry;
- RegattaLink, not Android, owns NMEA source selection and freshness for 0024.

RegattaTracker must discover optional characteristics by UUID and must not infer their presence from unrelated capability bits. A documented firmware surface may exist before RegattaTracker has UI or consumption logic for it; that distinction is intentional and must remain explicit.

Clients must not invent undocumented NMEA, proprietary-load or control layouts beyond the surfaces defined here.

## 2. UUID namespace

Base UUID:

7f2c4b10-6f63-4a8d-9a3e-2e5d6b71xxxx

| Purpose | Suffix | Access | Firmware contract | Current RegattaTracker consumption |
| --- | ---: | --- | --- | --- |
| Configuration service | 0001 | service | implemented | discovery anchor |
| Device name | 0002 | encrypted/bonded read + write | implemented | implemented |
| Device info | 0003 | encrypted/bonded read | implemented | implemented |
| NMEA2000 PGN inventory | 0004 | encrypted/bonded read | implemented | implemented |
| NMEA2000 raw CAN FIFO | 0005 | encrypted/bonded read | implemented | implemented as explicit bounded diagnostic read |
| Global LED brightness | 0006 | encrypted/bonded read + write | additive contract in RegattaLink #135 / PR #136; optional by discovery | implemented when discovered |
| OTA service | 0010 | service | implemented | implemented |
| OTA control | 0011 | encrypted/bonded write with response | implemented | implemented |
| OTA DATA | 0012 | encrypted/bonded write; no-response preferred, response supported | implemented | implemented |
| OTA status | 0013 | encrypted/bonded read + notify | implemented | implemented |
| Telemetry service | 0020 | service | implemented | implemented |
| Fast motion telemetry | 0021 | encrypted/bonded read + notify | implemented | implemented |
| Motion summary telemetry | 0022 | encrypted/bonded read + notify | implemented | implemented |
| Calibration diagnostics telemetry | 0023 | encrypted/bonded read + notify | implemented | implemented |
| Normalized NMEA Boat State v1 | 0024 | encrypted/bonded read + notify | implemented | implemented; UUID-discovered independently of IMU capability |

All multibyte integers in custom RegattaLink records are little-endian unless stated otherwise.

## 3. Advertising, connection and security

RegattaLink advertises:

- a complete GAP device name;
- the configuration service UUID 0001 in the scan response;
- connectable general-discoverable advertising, normally at approximately 250-300 ms intervals.

The default generated device name is RegattaLink-XXXX, derived from the Bluetooth identity. The user-visible configured name may differ.

Only one BLE client connection is supported at a time. A second simultaneous connection is rejected.

Custom RegattaLink application characteristics require an encrypted persistent bond. An unencrypted or non-bonded connection must not be treated as usable merely because service discovery succeeded.

New pairing is accepted only during the firmware pairing window. The current pairing window is five minutes. Previously bonded peers may reconnect after that window.

Android clients must tolerate standard GATT Service Changed behavior. Current firmware emits Service Changed on secured reconnect as a cache-invalidation safeguard. The client must serialize rediscovery and must not continue using cached handles while service rediscovery is pending.

A firmware replacement may add, remove or move characteristics. Clients must discover services/characteristics by UUID, never by hard-coded ATT handle.

## 4. Configuration service 0001

Full UUID:

7f2c4b10-6f63-4a8d-9a3e-2e5d6b710001

### 4.1 Device name 0002

Full UUID:

7f2c4b10-6f63-4a8d-9a3e-2e5d6b710002

Properties:

- read;
- write;
- encrypted/bonded access required.

Wire representation is the raw device-name byte sequence, without a terminating NUL.

Rules:

- length: 1..24 bytes;
- embedded NUL is forbidden;
- ASCII control bytes below 0x20 are forbidden;
- DEL 0x7f is forbidden;
- a successful write is persisted;
- firmware updates the GAP name immediately where the stack permits;
- name writes are rejected as BUSY while OTA owns the device.

The current client should treat device naming as configuration, not as a stable device identifier. Device identity comes from Device Info.

### 4.2 Device Info 0003

Full UUID:

7f2c4b10-6f63-4a8d-9a3e-2e5d6b710003

Properties:

- read only;
- encrypted/bonded access required.

Record size: exactly 32 bytes.

| Offset | Width | Type | Meaning |
| ---: | ---: | --- | --- |
| 0 | 1 | u8 | protocol major, currently 1 |
| 1 | 1 | u8 | protocol minor, currently 0 |
| 2 | 2 | u16 | record size, currently 32 |
| 4 | 4 | u32 | capability bitmap |
| 8 | 6 | bytes | stable ESP Bluetooth identity |
| 14 | 2 | u16 | product ID, currently 1 |
| 16 | 2 | u16 | hardware profile ID, currently 1 |
| 18 | 8 | u64 | running numeric firmware build |
| 26 | 4 | u32 | size of the next OTA application slot |
| 30 | 2 | u16 | maximum receiver in-flight OTA DATA blocks |

Capability bits:

| Bit | Mask | Meaning |
| ---: | ---: | --- |
| 0 | 0x00000001 | naming support |
| 1 | 0x00000002 | OTA backend available |
| 2 | 0x00000004 | signed-image verification enforced |
| 3 | 0x00000008 | telemetry available |
| 4 | 0x00000010 | pipelined OTA DATA |
| 5 | 0x00000020 | OTA DATA write without response |
| 6 | 0x00000040 | OTA DATA write with response fallback |
| 7 | 0x00000080 | best-effort LE 2M PHY support |
| 8 | 0x00000100 | authoritative OTA status SNAPSHOT support |

RegattaTracker compatibility checks:

- protocol major must be 1;
- record size must be 32;
- product ID must be 1;
- hardware profile ID must be 1;
- unknown capability bits must be ignored unless a later contract says otherwise.

Protocol minor is additive within the same major. A client must not reject a newer minor merely because it is newer when all required characteristics/fields it uses are present and compatible.

The stable identity is the logical RegattaLink identity used to reconcile reconnects. The current Bluetooth address shown by Android/Linux is not a substitute for this field.

### 4.3 NMEA2000 PGN inventory 0004

Full UUID:

7f2c4b10-6f63-4a8d-9a3e-2e5d6b710004

Properties:

- read only;
- encrypted/bonded access required;
- optional for compatibility with older firmware.

Presence is determined by GATT discovery. Do not infer this characteristic from the telemetry capability bit.

The value contains zero or more fixed-size 8-byte records:

| Record offset | Width | Type | Meaning |
| ---: | ---: | --- | --- |
| 0 | 4 | u32 | NMEA2000 PGN |
| 4 | 4 | u32 | age in milliseconds since that PGN was last observed |

The value length must be divisible by 8. A non-empty value with another length is malformed and must be rejected.

Important semantics:

- last_seen_ms is an age, not a wall-clock timestamp;
- smaller last_seen_ms means more recently observed;
- record order has no semantic meaning;
- unknown and manufacturer-specific PGNs are valid and must not be discarded;
- the inventory is populated before typed PGN decoding, so a PGN may be listed even when firmware has no decoder for it;
- no source address is included;
- no receive count is included;
- no PGN display-name string is included;
- no raw CAN ID or CAN payload is included.

The firmware stores at most 64 distinct PGNs per boot. Maximum value size is therefore 512 bytes.

Clients must support the normal ATT long-read procedure. Firmware freezes one coherent inventory snapshot at offset zero so Read Blob fragments from one long read represent the same observation instant.

An empty value is valid and means that no valid NMEA2000 PGN has yet been observed during the current boot/session.

When the 64-entry inventory is full, already-known PGNs continue to refresh. Additional previously unseen PGNs are ignored until reboot.

Client UI may map known PGNs to human-readable names locally, but the numeric PGN must remain visible so unknown/proprietary traffic can still be diagnosed.


### 4.4 NMEA2000 raw CAN FIFO 0005

Full UUID:

7f2c4b10-6f63-4a8d-9a3e-2e5d6b710005

Properties:

- read only;
- encrypted/bonded access required;
- optional for compatibility with older firmware;
- diagnostic read-to-drain surface, not a normal telemetry stream.

Firmware keeps a fixed FIFO of up to 128 complete valid received NMEA2000 CAN frames. A successful characteristic read returns exactly one fixed 21-byte value and removes at most one oldest frame.

| Offset | Width | Type | Meaning |
| ---: | ---: | --- | --- |
| 0 | 1 | u8 | format version, currently 1 |
| 1 | 1 | u8 | remaining FIFO count immediately after this read/pop |
| 2 | 1 | u8 | frame_present: 0 if FIFO was empty, otherwise 1 |
| 3 | 1 | u8 | reserved, currently 0 |
| 4 | 4 | u32 | low 32 bits of monotonic receive timestamp, microseconds |
| 8 | 4 | u32 | original 29-bit CAN identifier in the low bits |
| 12 | 1 | u8 | CAN DLC, 0..8 |
| 13 | 8 | bytes | raw CAN payload; bytes beyond DLC are zero |

When the FIFO is empty, frame_present and remaining_count are zero and all frame fields are zero.

Important semantics:

- frames are appended in receive order;
- when the FIFO is full, diagnostic copies of additional frames are ignored until reads free space; old queued frames are not overwritten;
- this FIFO behavior does not block or alter normal NMEA decoding, PGN inventory, Boat State telemetry or OTA;
- a read reserves the complete response before the firmware removes the FIFO head, so an ATT-buffer failure does not lose the oldest queued frame;
- remaining_count is a point-in-time occupancy after the pop; live CAN traffic can increase it again before the next read;
- the original CAN ID, DLC and payload are authoritative; priority/PGN/source/destination are derived client-side;
- NMEA fast-packet traffic remains individual raw CAN frames on this surface;
- there is no START/STOP command, notification stream, capture session, rolling overwrite or overflow counter.

RegattaTracker consumes 0005 only after an explicit diagnostic user action. Each drain operation is bounded to at most 128 reads and never runs as normal background telemetry.

### 4.5 Global LED brightness 0006

Full UUID:

7f2c4b10-6f63-4a8d-9a3e-2e5d6b710006

Properties:

- read;
- write;
- encrypted/bonded access required;
- optional by GATT discovery for firmware predating the additive 0006 contract.

Wire representation is exactly one unsigned byte:

- unit: percent;
- valid range: 0..100;
- default: 50.

A successful write persists the value in RegattaLink, applies it to the LED renderer immediately, and survives reboot. Persistent writes may be rejected with RegattaLink application error BUSY while OTA owns flash.

The wire contract is deliberately a percentage only. Low/High choices, fixed steps or sliders are RegattaTracker UI policy and must not be encoded into the BLE value.

RegattaTracker reads 0006 when present and writes it only on an explicit completed UI change; absence on older firmware remains non-fatal.


## 5. Telemetry service 0020

Full service UUID:

7f2c4b10-6f63-4a8d-9a3e-2e5d6b710020

The telemetry service carries both RegattaLink IMU/motion state (0021-0023) and normalized NMEA2000 Boat State v1 (0024).

All application characteristic values require an encrypted persistent bond. Presence is discovered by UUID.

For the IMU characteristics 0021-0023:

- Device Info capability bit 3 indicates IMU telemetry availability;
- each characteristic is read + notify;
- each current record is exactly 20 bytes and begins with schema byte 1;
- clients must reject an unsupported schema or wrong fixed record size;
- sequence counters are unsigned 16-bit counters and may wrap naturally.

0024 is independent of the legacy IMU telemetry capability bit. Its presence must be discovered directly; absence must not break 0021-0023 or OTA.

Telemetry notifications are paused during active OTA PREPARING/RECEIVING/VERIFYING work. The client must not reinterpret missing notifications during OTA as sensor/NMEA failure. Firmware continues its underlying sensor/NMEA processing and fresh records resume after OTA leaves the active transfer state.

Current publication behavior:

- fast motion: target approximately 10 Hz;
- summary: target approximately 1 Hz;
- calibration diagnostics: target approximately 1 Hz;
- Boat State: complete change-coalesced snapshots, capped at 10 Hz.

Notification delivery is best effort. Read access can obtain a current record when needed.

### 5.1 Fast motion 0021

Full UUID:

7f2c4b10-6f63-4a8d-9a3e-2e5d6b710021

20-byte record:

| Offset | Width | Type | Meaning / scale |
| ---: | ---: | --- | --- |
| 0 | 1 | u8 | schema version = 1 |
| 1 | 1 | u8 | boat-frame confidence, 0..100 percent |
| 2 | 2 | u16 | sequence |
| 4 | 4 | u32 | RegattaLink monotonic timestamp, ms |
| 8 | 2 | i16 | roll, degrees x 100 |
| 10 | 2 | i16 | pitch, degrees x 100 |
| 12 | 2 | i16 | roll rate, deg/s x 100 |
| 14 | 2 | i16 | pitch rate, deg/s x 100 |
| 16 | 2 | i16 | yaw rate, deg/s x 100 |
| 18 | 2 | i16 | vertical acceleration, g x 1000 |

RegattaTracker considers the last received fast record fresh for 2000 ms of phone monotonic time.

### 5.2 Motion summary 0022

Full UUID:

7f2c4b10-6f63-4a8d-9a3e-2e5d6b710022

20-byte record:

| Offset | Width | Type | Meaning / scale |
| ---: | ---: | --- | --- |
| 0 | 1 | u8 | schema version = 1 |
| 1 | 1 | u8 | boat-frame confidence, 0..100 percent |
| 2 | 2 | u16 | sequence |
| 4 | 4 | u32 | RegattaLink monotonic timestamp, ms |
| 8 | 2 | i16 | filtered heel, degrees x 100 |
| 10 | 2 | i16 | filtered trim, degrees x 100 |
| 12 | 2 | u16 | roll RMS, degrees x 100 |
| 14 | 2 | u16 | pitch RMS, degrees x 100 |
| 16 | 2 | u16 | vertical-acceleration RMS, g x 1000 |
| 18 | 2 | u16 | motion intensity |

RegattaTracker considers the last received summary fresh for 3000 ms of phone monotonic time.

### 5.3 Calibration diagnostics 0023

Full UUID:

7f2c4b10-6f63-4a8d-9a3e-2e5d6b710023

20-byte record:

| Offset | Width | Type | Meaning |
| ---: | ---: | --- | --- |
| 0 | 1 | u8 | schema version = 1 |
| 1 | 1 | u8 | overall confidence, 0..100 percent |
| 2 | 1 | u8 | forward-axis confidence, 0..100 percent |
| 3 | 1 | u8 | roll-axis confidence, 0..100 percent |
| 4 | 1 | u8 | learner state |
| 5 | 1 | u8 | flags |
| 6 | 2 | u16 | sequence |
| 8 | 2 | u16 | positive maneuvers |
| 10 | 2 | u16 | negative maneuvers |
| 12 | 2 | u16 | roll-pair observations |
| 14 | 2 | u16 | contradictory maneuvers |
| 16 | 2 | u16 | mounting epoch |
| 18 | 2 | u16 | calibration revision |

Learner states:

| Value | Meaning |
| ---: | --- |
| 0 | idle |
| 1 | turn/maneuver active |
| 2 | waiting for post-maneuver attitude |

Flags:

| Bit | Meaning |
| ---: | --- |
| 0 | gyro bias valid |
| 1 | boat frame valid |

Unknown flag bits must be ignored.

RegattaTracker considers the last received diagnostics record fresh for 3000 ms of phone monotonic time.

### 5.4 Normalized NMEA Boat State v1 0024

Full UUID:

7f2c4b10-6f63-4a8d-9a3e-2e5d6b710024

Properties:

- read;
- notify;
- encrypted/bonded access required;
- optional for compatibility with older firmware.

Presence is determined by GATT discovery and is independent of NMEA bring-up success. With no currently usable NMEA data, firmware still returns a valid v1 record with a zero validity bitmap. Do not infer 0024 from Device Info capability bit 3; that bit retains its legacy IMU-telemetry meaning.

RegattaLink owns NMEA source selection and freshness for this normalized state:

- selection is per coherent logical group;
- the lowest currently eligible NMEA source address wins;
- each source and each logical group expire independently after 60 seconds;
- source timeout/fallback clears or replaces the corresponding validity bits in firmware;
- Android must not apply a second independent NMEA source-selection or 60-second aging policy to the wire record.

Record size: exactly 80 bytes, little-endian schema v1.

| Offset | Width | Type | Meaning / scale |
| ---: | ---: | --- | --- |
| 0 | 1 | u8 | schema version = 1 |
| 1 | 1 | u8 | record size = 80 |
| 2 | 2 | u16 | snapshot sequence |
| 4 | 4 | u32 | monotonic snapshot time, low 32 bits, ms |
| 8 | 4 | u32 | validity bitmap |
| 12 | 2 | u16 | heading, centidegrees |
| 14 | 1 | u8 | heading reference; 0xff unavailable |
| 15 | 1 | u8 | reserved, zero |
| 16 | 2 | i16 | heading deviation, signed centidegrees |
| 18 | 2 | i16 | heading variation, signed centidegrees |
| 20 | 2 | i16 | rate of turn, signed centidegrees/second |
| 22 | 2 | i16 | yaw, signed centidegrees |
| 24 | 2 | i16 | pitch, signed centidegrees |
| 26 | 2 | i16 | roll, signed centidegrees |
| 28 | 2 | u16 | speed through water, centimetres/second |
| 30 | 1 | u8 | speed reference; 0xff unavailable |
| 31 | 1 | u8 | reserved, zero |
| 32 | 4 | u32 | depth below transducer, unsigned centimetres |
| 36 | 4 | i32 | depth offset, signed centimetres |
| 40 | 4 | u32 | depth range, unsigned centimetres |
| 44 | 2 | i16 | water temperature, centi-degrees Celsius |
| 46 | 1 | u8 | NMEA temperature source; 0xff unavailable |
| 47 | 1 | u8 | reserved, zero |
| 48 | 4 | i32 | latitude, degrees x 1e7 |
| 52 | 4 | i32 | longitude, degrees x 1e7 |
| 56 | 2 | u16 | COG, centidegrees |
| 58 | 2 | u16 | SOG, centimetres/second |
| 60 | 1 | u8 | COG reference; 0xff unavailable |
| 61 | 1 | u8 | reserved, zero |
| 62 | 1 | u8 | GNSS type; 0xff unavailable |
| 63 | 1 | u8 | GNSS method/fix; 0xff unavailable |
| 64 | 1 | u8 | satellites; 0xff unavailable |
| 65 | 1 | u8 | reserved, zero |
| 66 | 2 | u16 | HDOP x 100 |
| 68 | 2 | u16 | PDOP x 100 |
| 70 | 4 | i32 | altitude, signed centimetres |
| 74 | 2 | u16 | wind speed, centimetres/second |
| 76 | 2 | u16 | wind angle, centidegrees |
| 78 | 1 | u8 | NMEA wind reference; 0xff unavailable |
| 79 | 1 | u8 | reserved, zero |

Validity bitmap:

| Bit | Field |
| ---: | --- |
| 0 | heading |
| 1 | heading deviation |
| 2 | heading variation |
| 3 | rate of turn |
| 4 | yaw |
| 5 | pitch |
| 6 | roll |
| 7 | speed through water |
| 8 | depth |
| 9 | depth offset |
| 10 | depth range |
| 11 | water temperature |
| 12 | position |
| 13 | COG |
| 14 | SOG |
| 15 | GNSS quality metadata |
| 16 | GNSS altitude |
| 17 | wind speed |
| 18 | wind angle |

A cleared validity bit means absent regardless of the numeric bytes. NMEA NA/error values must not be interpreted as numeric zero. Heading/COG/wind angles are normalized by firmware before encoding.

The 32-bit depth fields are intentional and avoid an artificial 655.35 m limit. If an engineering value cannot be represented by its wire field, firmware clears the corresponding validity bit rather than silently saturating it.

Notifications contain complete snapshots and are change-coalesced, capped at 10 Hz. An 80-byte notification requires ATT MTU at least 83. At smaller MTU, live notifications wait for a sufficient negotiated MTU, while ordinary ATT long reads remain valid.

OTA PREPARING/RECEIVING/VERIFYING pauses 0024 notifications but does not stop NMEA ingestion, source selection or firmware freshness tracking.

RegattaTracker consumes 0024 independently of IMU capability bit 3. It requests a sufficient MTU best-effort for live 80-byte notifications and retains ordinary long-read snapshot access when the negotiated MTU remains smaller than 83.

### 5.5 RegattaTracker telemetry persistence mapping

Normal tracking samples may copy the latest fresh BLE telemetry snapshot into the sample measurements object. This does not increase the server sample rate.

Stable measurement keys currently used by RegattaTracker:

Fast:

- regattalink.fast.confidence_pct
- regattalink.fast.sequence
- regattalink.fast.timestamp_ms
- regattalink.fast.roll_deg
- regattalink.fast.pitch_deg
- regattalink.fast.roll_rate_dps
- regattalink.fast.pitch_rate_dps
- regattalink.fast.yaw_rate_dps
- regattalink.fast.vertical_accel_g

Summary:

- regattalink.summary.confidence_pct
- regattalink.summary.sequence
- regattalink.summary.timestamp_ms
- regattalink.summary.heel_filtered_deg
- regattalink.summary.trim_filtered_deg
- regattalink.summary.roll_rms_deg
- regattalink.summary.pitch_rms_deg
- regattalink.summary.vertical_accel_rms_g
- regattalink.summary.motion_intensity

Calibration:

- regattalink.calibration.overall_confidence_pct
- regattalink.calibration.forward_confidence_pct
- regattalink.calibration.roll_confidence_pct
- regattalink.calibration.learner_state
- regattalink.calibration.gyro_bias_valid
- regattalink.calibration.boat_frame_valid
- regattalink.calibration.sequence
- regattalink.calibration.positive_maneuvers
- regattalink.calibration.negative_maneuvers
- regattalink.calibration.roll_pair_observations
- regattalink.calibration.contradictory_maneuvers
- regattalink.calibration.mounting_epoch
- regattalink.calibration.calibration_revision

Stale records and telemetry paused for OTA are not attached to newly created tracking samples.

## 6. OTA service 0010

Full service UUID:

7f2c4b10-6f63-4a8d-9a3e-2e5d6b710010

OTA correctness is based on device-authoritative committed progress, not Android write completion.

### 6.1 Characteristics

OTA control 0011:

7f2c4b10-6f63-4a8d-9a3e-2e5d6b710011

- encrypted/bonded write;
- use write with response;
- serializes START, FINISH, ABORT and SNAPSHOT control transactions.

OTA DATA 0012:

7f2c4b10-6f63-4a8d-9a3e-2e5d6b710012

- encrypted/bonded write;
- write without response is the preferred transport when capability bit 5 is present;
- write with response is the fallback when capability bit 6 is present;
- both modes use identical DATA framing.

OTA status 0013:

7f2c4b10-6f63-4a8d-9a3e-2e5d6b710013

- encrypted/bonded read;
- notify;
- notifications are progress acceleration only;
- SNAPSHOT + read is the authoritative reconciliation path.

### 6.2 OTA progress notification

Notification size: exactly 20 bytes.

| Offset | Width | Type | Meaning |
| ---: | ---: | --- | --- |
| 0 | 4 | u32 | revision |
| 4 | 4 | u32 | session ID |
| 8 | 4 | u32 | accepted_offset |
| 12 | 4 | u32 | total image size |
| 16 | 1 | u8 | OTA state |
| 17 | 1 | u8 | terminal/last request error |
| 18 | 2 | u16 | maximum DATA image payload for current ATT MTU |

OTA states:

| Value | Meaning |
| ---: | --- |
| 0 | IDLE |
| 1 | PREPARING |
| 2 | RECEIVING |
| 3 | VERIFYING |
| 4 | READY_TO_REBOOT |
| 5 | ERROR |

Revision is a u32 modular counter. Clients comparing revisions must handle natural u32 wrap.

### 6.3 Full OTA status SNAPSHOT

Full status size: exactly 44 bytes.

Offsets 0..19 are identical to the progress notification.

Additional fields:

| Offset | Width | Type | Meaning |
| ---: | ---: | --- | --- |
| 20 | 4 | u32 | current/last request ID |
| 24 | 8 | u64 | running build |
| 32 | 8 | u64 | accepted target build |
| 40 | 1 | u8 | boot result |
| 41 | 1 | u8 | START metadata assembly active, 0/1 |
| 42 | 2 | u16 | metadata bytes assembled |

Boot results:

| Value | Meaning |
| ---: | --- |
| 0 | UNKNOWN |
| 1 | VALIDATED |
| 2 | PENDING_VERIFY |
| 3 | ROLLBACK |

To obtain an authoritative full snapshot:

1. write one byte 0x06 to OTA control using write with response;
2. wait for that ATT write to complete;
3. read the 44-byte OTA status characteristic;
4. do not overlap another SNAPSHOT/control transaction with this operation.

Notifications may be lost or coalesced. Missing notification is never permission to resend ambiguous DATA.

### 6.4 START metadata transaction

Each OTA attempt uses a non-zero u32 request ID.

Metadata is exactly 48 bytes:

| Offset | Width | Type | Meaning |
| ---: | ---: | --- | --- |
| 0 | 2 | u16 | product ID |
| 2 | 2 | u16 | hardware profile ID |
| 4 | 4 | u32 | image size |
| 8 | 8 | u64 | target build number |
| 16 | 32 | bytes | raw SHA-256 digest |

Control messages:

START_BEGIN:
- opcode 0x01;
- request_id u32 LE;
- protocol major u8;
- protocol minor u8;
- metadata length u16 LE, currently 48;
- total 9 bytes.

START_FRAGMENT:
- opcode 0x02;
- request_id u32 LE;
- metadata offset u16 LE;
- fragment bytes.

START_COMMIT:
- opcode 0x03;
- request_id u32 LE;
- total 5 bytes.

Control writes use write with response.

START fragments are sequential. The maximum GATT control value is min(MTU - 3, 244).

After START_COMMIT, ATT success means only that the request was accepted for processing. Before DATA, the client must observe:

- matching request ID;
- non-zero session ID;
- state RECEIVING.

### 6.5 DATA framing and commit authority

Every DATA characteristic value is:

| Offset | Width | Meaning |
| ---: | ---: | --- |
| 0 | 4 | session_id u32 LE |
| 4 | 4 | absolute image offset u32 LE |
| 8 | N | image bytes |

Image payload length is 1..max_data_payload from status.

At ATT MTU 247 the current maximum image payload is 236 bytes, producing a 244-byte GATT value including the 8-byte DATA header.

Offsets are absolute. The receiver accepts only the next expected offset.

The receiver internally distinguishes admitted data from committed data. Only accepted_offset is public and authoritative. accepted_offset means the bytes have been written to flash and incorporated into the streaming hash.

Therefore:

- Android/BLE API success does not equal flash commit;
- an ATT write response confirms transport/admission only;
- write-without-response has no per-block ATT response;
- UI progress must use accepted_offset / total_size;
- retransmitting an ambiguous block is forbidden until authoritative reconciliation proves it was not committed.

The receiver may coalesce progress notifications. One notification may advance across several DATA blocks.

When accepted_offset advances, clients must:

- free all sender credits whose end offset is <= accepted_offset;
- require progress to be monotonic;
- reject progress beyond the last locally admitted boundary;
- require forward progress to land on a known admitted block boundary.

### 6.6 OTA transport selection

Selection order:

1. prefer write without response when capability bit 5 is set and Android can write the required value size;
2. otherwise use write with response when capability bit 6 is set;
3. if neither is usable, fail before DATA.

Current sender policy:

- receiver max window comes from Device Info;
- valid current receiver window is 2..32;
- write-without-response starts with sender window min(4, receiver window);
- committed forward progress permits additive growth toward the receiver maximum;
- explicit local Android GATT queue pressure may reduce the sender window;
- an ambiguous timeout/disconnect must not be treated as a known local queue rejection;
- write-with-response is the compatibility fallback.

Committed DATA throughput policy currently used by the client:

- slow-link diagnostic threshold: 10 KiB/s;
- supported-device floor: 4 KiB/s;
- preferred modern-device target: 25 KiB/s;
- sample only after at least 3 seconds and 64 KiB committed since the last transport adaptation.

Low throughput by itself is not a reason to shrink the no-response sender window. Window reduction is reserved for explicit local queue pressure.

### 6.7 FINISH

FINISH is valid only after:

- all image bytes were locally admitted;
- accepted_offset equals image size;
- no sender credits remain in flight.

Message:

- opcode 0x04;
- session_id u32 LE;
- total 5 bytes.

Use write with response.

The device then enters VERIFYING. SHA-256 is checked first. Firmware that advertises capability bit 2 additionally verifies the signed application.

READY_TO_REBOOT means boot selection succeeded. It does not mean the new application has completed first-boot validation.

### 6.8 Post-reboot success

After READY_TO_REBOOT:

1. close stale local GATT state best effort;
2. reconnect to the same stable Device Info identity;
3. re-establish the encrypted persistent bond;
4. rediscover services;
5. read Device Info and require the expected target build;
6. obtain a full OTA status SNAPSHOT;
7. require boot result VALIDATED.

OTA success requires both:

- expected target build is running;
- the relevant boot result is VALIDATED.

Build equality alone is insufficient, especially for same-build reinstall.

PENDING_VERIFY means keep waiting/reconciling. ROLLBACK is terminal failure. UNKNOWN is not proof of success. Disconnect alone is never success.

### 6.9 ABORT and connection loss

ABORT message:

- opcode 0x05;
- request_id u32 LE;
- session_id u32 LE;
- total 9 bytes.

Before boot-selection admission, abort/disconnect/security loss cancels the attempt and firmware cleans up the inactive-slot write state.

Once boot selection has been admitted during verification, reconnect/status reconciliation owns the outcome.

A transport failure must never be presented as success.

### 6.10 OTA protocol error codes

Where an ATT response exists, RegattaLink application errors use ATT application error 0x80 + code.

| Code | Name |
| ---: | --- |
| 0 | OK |
| 1 | INVALID_LENGTH |
| 2 | NOT_SUPPORTED |
| 3 | BUSY |
| 4 | BAD_STATE |
| 5 | BAD_REQUEST |
| 6 | BAD_OFFSET |
| 7 | BAD_SESSION |
| 8 | BAD_HARDWARE |
| 9 | BAD_SIZE |
| 10 | TIMEOUT |
| 11 | HASH_MISMATCH |
| 12 | IMAGE_MISMATCH |
| 13 | SIGNATURE |
| 14 | FLASH |
| 15 | CANCELLED |
| 16 | ROLLBACK |

For write-without-response DATA, a protocol rejection is learned from notification/SNAPSHOT rather than an ATT response.

## 7. Android GATT rules

RegattaTracker treats Android GATT as an asynchronous operation scheduler, not as a reliable byte stream.

Required behavior:

- serialize ordinary GATT operations that require callbacks;
- do not overlap service discovery with characteristic/descriptor transactions;
- react to Service Changed by scheduling a fresh discovery;
- do not assume cached handles remain valid after firmware replacement;
- use the standard CCCD to enable notifications;
- use WRITE_TYPE_NO_RESPONSE only where the characteristic/capability contract allows it;
- use WRITE_TYPE_DEFAULT for control and response-mode DATA;
- request a useful MTU but do not make correctness depend on the requested MTU being granted;
- high connection priority and 2M PHY are best-effort optimizations only;
- distinguish local enqueue rejection from ambiguous radio/disconnect outcome;
- never equate write callback success with OTA commit.

During OTA, telemetry may pause and service rediscovery may need to be deferred until the OTA transaction is no longer using those characteristics. Rediscovery and OTA characteristic operations must not race.

## 8. Compatibility and optionality rules

Clients must discover by UUID and degrade optional features independently.

Optional/current compatibility behavior:

- 0004 PGN inventory may be absent on older firmware;
- 0005 raw CAN FIFO may be absent on older firmware and must never be required for ordinary NMEA/Boat State operation;
- 0006 LED brightness may be absent on firmware predating the additive brightness contract;
- 0024 normalized Boat State may be absent on older firmware and is not implied by the IMU telemetry capability bit;
- the telemetry service or individual optional telemetry characteristics may be absent/unavailable without breaking Device Info or OTA;
- NMEA bus availability must not be inferred from Device Info capability bit 3;
- an empty NMEA inventory is valid;
- an empty raw-CAN FIFO read is valid and represented by frame_present=0;
- a Boat State record with validity bitmap zero is valid;
- unknown NMEA PGNs must be retained/displayable where 0004 is consumed;
- unknown capability bits must be ignored;
- unknown fixed-record schema versions must be rejected rather than decoded with the wrong layout;
- malformed fixed-size records must be rejected;
- missing optional services/characteristics must degrade only the feature that uses them.

Current RegattaTracker consumption remains capability/UUID-driven:

- Device Info, OTA and IMU telemetry 0021-0023 are consumed;
- 0002 and 0006 are consumed as optional configuration surfaces;
- 0004 is read explicitly for PGN inventory diagnostics;
- 0005 is drained only after explicit user action and with a strict finite bound;
- 0024 is discovered independently of IMU capability bit 3 and consumed as read + notify Boat State telemetry.

Core connection failure conditions remain:

- configuration service or Device Info missing;
- Device Info malformed;
- protocol major unsupported;
- unexpected product/profile;
- security/bonding cannot be established.

OTA has additional capability/manifest requirements and may be unavailable even when ordinary Device Info/config access works.

## 9. Explicitly not part of the current BLE contract

The following must not be implemented by guessing wire layouts:

- any NMEA2000 field not represented by the documented 0024 v1 schema;
- any raw-CAN control/write/notification protocol beyond the documented read-only 0005 FIFO;
- NMEA2000 transmit control;
- Cyclops/proprietary load values over normal telemetry unless a future characteristic explicitly defines them;
- undocumented LED/debug configuration values beyond the one-byte 0006 brightness percentage.

Decoded heading, rate of turn, attitude, STW, depth, water temperature, GNSS, COG/SOG and wind **are** part of the current firmware BLE contract only through the explicitly versioned 0024 Boat State record and its validity bitmap. Raw received CAN **is** part of the current firmware BLE contract only through the explicitly defined 0005 FIFO.

When additional features are added, this document must define their UUIDs, byte layouts, versioning, units, source/freshness semantics, security, rates and backward-compatibility behavior before RegattaTracker relies on them.

## 10. Client acceptance checklist

A RegattaTracker change affecting RegattaLink BLE should verify, as applicable:

- encrypted persistent bonding succeeds;
- service discovery works from a cold GATT cache;
- Service Changed followed by rediscovery works;
- Device Info parses exactly and rejects incompatible major/product/profile;
- optional 0004/0005/0006/0024 absence is tolerated;
- 0004 supports empty, single-record and >ATT-MTU long-read values when consumed;
- malformed 0004 length is rejected;
- unknown PGNs survive 0004 parsing;
- 0005 reads are treated as destructive read-to-drain operations and a 21-byte empty record is valid when consumed;
- 0006 is exactly one byte in range 0..100 and BUSY during OTA is handled as a feature-local write failure when consumed;
- IMU telemetry fixed-size/schema validation works;
- IMU telemetry subscription/read and stale handling work;
- 0024 requires exact v1/80-byte validation, honors validity bits, and is discovered independently of IMU capability when consumed;
- 0024 notification MTU requirements do not prevent long-read fallback;
- OTA notification loss recovers by SNAPSHOT;
- OTA local queue pressure causes bounded adaptation rather than duplicate/gapped DATA;
- FINISH -> reboot -> secured reconnect -> expected build -> VALIDATED is required for OTA success;
- disconnect or ambiguous transport outcome is never reported as successful installation.

## 11. Change-control rule

This file is the public RegattaTracker client contract for RegattaLink BLE.

A client-visible RegattaLink BLE change is incomplete unless the public contract is updated. This includes:

- adding/removing a service or characteristic;
- changing a UUID;
- changing field offset, width, signedness, scale, unit or byte order;
- changing notification/read/write semantics;
- changing required security;
- adding a capability bit used by clients;
- changing stale/freshness semantics;
- defining a new NMEA2000 BLE surface;
- changing OTA transaction or success semantics.

The corresponding RegattaTracker parser/behavior should be covered by tests so documentation and implementation do not silently diverge.
