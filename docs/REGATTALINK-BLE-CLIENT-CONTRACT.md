# RegattaLink BLE client contract — GATT v2 / core schema 16, MAG schema 21

This document is the RegattaTracker-side contract for the production RegattaLink GATT v2 baseline defined by RegattaLink#274.

Legacy prototype firmware in the `...6b71xxxx` UUID namespace is development history. Production Tracker code does not provide a v1/v2 compatibility bridge.

## Namespace and discovery

Base UUID:

`7f2c4b10-6f63-4a8d-9a3e-2e5d6b72xxxx`

Clients discover by UUID and never hard-code ATT handles.

RegattaLink advertises the OTA service `0001`. Tracker discovery and OTA reconnect therefore scan for:

`7f2c4b10-6f63-4a8d-9a3e-2e5d6b720001`

All RegattaLink characteristic value access requires an encrypted, bonded BLE connection. Service UUIDs can be visible before encryption, but characteristic reads, writes and subscriptions are secured.

Device Info reports a GATT schema generation. GATT v2 starts at schema 16. Tracker rejects older schema generations instead of attempting the legacy namespace.

## Frozen GATT v2 core

### OTA — first RegattaLink custom service

| Suffix | Role | Access |
| ---: | --- | --- |
| `0001` | OTA service | service |
| `0002` | OTA Control | W with response |
| `0003` | OTA Data | W + WNR |
| `0004` | OTA Status | R + N |

The firmware-image OTA wire protocol remains version 1.0. Only the GATT namespace/database generation changed.

### Config / Control

| Suffix | Role | Wire |
| ---: | --- | --- |
| `0010` | Config / Control service | service |
| `0011` | Device Name | UTF-8, 1..24 B |
| `0012` | Device Info | existing 32 B record |
| `0013` | Boat Data PGN Inventory | existing 8 B/entry format |
| `0014` | Boat Data Raw CAN FIFO | existing 21 B record |
| `0015` | Global LED Brightness | u8, 0..100 |
| `0016` | Heel/Pitch Damping | u8 seconds, 1..10 |
| `0017` | Config Word | u32 LE, exactly 4 B |
| `0018` | Local Compass Heading Trim | i16 LE degrees |
| `0019` | Diagnostic Log FIFO | existing 22 B record |
| `001A` | Device Control | existing 8 B request / 20 B status |
| `001B` | Boat Data TX Runtime Status v2 | exactly 4 B |
| `001C` | Phone GNSS Input | exactly 28 B, W with response |

The Config/Control block is frozen at `0010..001C`. Extension is not a compatibility container.

### Telemetry

| Suffix | Role |
| ---: | --- |
| `0020` | Telemetry service |
| `0021` | Fast Motion |
| `0022` | Motion Summary |
| `0023` | IMU diagnostics |
| `0024` | normalized Boat State |
| `0025` | Motion 1 Hz |
| `0026` | compact normalized Load telemetry |

Existing telemetry byte layouts remain unchanged; only their full UUID namespace changes to `...6b72`.

### Extension

`0030` is the Extension service. It is intentionally empty in the schema-16 baseline. Future optional protocol growth may use `0031+` without moving the frozen OTA, Config or Telemetry core.

## Device Info and schema handling

Device Info remains the normal connection identity/capability record. Tracker requires:

- protocol major 1;
- product ID 1;
- profile ID 1;
- GATT schema >= 16.

Unknown capability bits are ignored.

Android GATT caching remains relevant even after the namespace reset. Tracker keeps the existing Service Changed / rediscovery / bounded local cache-refresh recovery model for future schema changes. Authentication failures are security/pairing failures, not evidence that a characteristic is missing.

The Device Name characteristic keeps the existing reserved schema-refresh write convention used by the Android cache reconciliation path.

## Config Word 0017

Wire format is exactly four-byte unsigned little-endian.

| Bit | Meaning |
| ---: | --- |
| 0 | global Boat Data TX |
| 1 | TX local IMU / Heel+Trim |
| 2 | TX NMEA 0183 input |
| 3 | TX Phone GPS |
| 4 | TX local compass heading |
| 5 | TX Load, reserved until output contract exists |
| 6..7 | reserved |
| 8 | Load precision: 0=x1, 1=x10 |
| 9 | MAG MANUAL_3D selector (schema 21) |
| 10 | reserved |
| 11 | MAG AUTO_2D selector (schema 21) |
| 12..13 | reserved |
| 14..15 | NMEA 0183 baud selector |
| 16 | IMU current-session availability / next-boot enable request |
| 17 | onboard MAG current-session availability / next-boot enable request |
| 18 | CAN / Boat Data current-session availability / next-boot enable request |
| 19 | NMEA 0183 RX current-session availability / next-boot enable request |
| 20..31 | reserved |

Baud selector:
- `00` = 4800
- `01` = 9600
- `10` = 19200
- `11` = 38400

All `0017` writes are whole-register writes. Tracker must use read-modify-write:

1. read fresh `0017`;
2. change only the requested mask;
3. preserve every unrelated/reserved bit;
4. write all four bytes with response;
5. on ambiguous/failing write, re-read and present the device value.

No setting is automatically enabled as a side effect of another setting.

Ordinary `0017` settings are persisted immediately but reboot-applied. Bits 16..19
have special semantics from RegattaLink #285: a read reports **current-session
availability**, not the next-boot desired state. Firmware stores subsystem enable
intent separately.

For subsystem writes Tracker performs a fresh whole-word RMW and then re-reads
`0017` after a successful write. The session bit therefore remains unchanged until
restart while `configRestartRequired` records the pending change. A session bit
`0` may mean intentionally disabled or initialization failed; writing `1` requests
enable/retry next boot. Writing an unchanged `0` during an unrelated RMW does not
mean the subsystem is persistently disabled.

Tracker uses these session bits to gate dependent controls:
- bit16 IMU -> IMU calibration/motion controls and Heel/Trim TX;
- bit17 MAG -> onboard compass/MAG controls and compass TX;
- bit18 CAN -> local Boat Data TX, CAN inventory and raw-CAN functions;
- bit19 NMEA0183 -> 0183 baud/input/forwarding controls.

The four explicit enable/re-enable switches remain reachable under Advanced /
Diagnostics even when a subsystem is unavailable, so the user can request a retry on
the next boot. Firmware that predates #285 but already reports schema 16 returned
bits16..19 as reserved zero; Tracker therefore treats an all-zero session nibble as
**availability unknown for feature hiding**, while the explicit Advanced switches
still show the raw session bits. Unknown bits 20..31 remain preserved by every RMW.

## Runtime TX status 001B v2

Exactly four bytes:

| Byte | Meaning |
| ---: | --- |
| 0 | version = 2 |
| 1 | master flags: bit0 boot-selected, bit1 actually active |
| 2 | boot-selected application-output mask |
| 3 | actually-active application-output mask |

Application-output mask:
- bit0 local IMU / Heel+Trim
- bit1 NMEA0183
- bit2 Phone GPS
- bit3 local compass
- bit4 Load
- bits5..7 reserved

Tracker keeps desired state from `0017` separate from applied/runtime state from `001B`. A selected bit is not proof that an output is active.

For the existing TX UI, restart-required is reconstructed by comparing desired bit0/bit1 with the boot-selected state in `001B`. A successful `0017` write also marks the current client session as restart-required.

## Heading Trim 0018

The frozen wire contract is:

- exactly 2 bytes;
- signed int16 little-endian;
- 1 degree/count;
- range -180..+180;
- persistent;
- immediate apply;
- local onboard compass only.

Tracker exposes this control in the IMU setup surface. It applies immediately and does not require another GATT change.

## MAG schema 21 (#567)

The MAG setup screen requires schema >=21 and matching characteristic properties/CCCD. Older firmware retains the existing secured OTA-only update/recovery route. There is no schema-20 MAG fallback. Registered UUIDs alone do not establish valid factory calibration, an upright reference, a sensor or an installed model: fresh metadata flags do.

All XYZ below are factory-corrected **MMC5633 native XYZ in µT**, before installation correction or boat-frame rotation. The fixed plane comes from the frozen Set-Upright base: Down `n`, Forward `u`, Side `n × u`. Live orientation trim does not redefine this plane. Bind captures, proposals and backups to stable device ID, factory CRC and upright reference. Only an active capture additionally binds boot ID and stream context; normal unsubscribe or reboot must not invalidate an already completed object solely because these values changed.

| Characteristic | Contract |
| --- | --- |
| `0032` / `0033` | MLG-v2 snapshot open/select/close, 28-byte manifest and long-read pages |
| `0034` / `0035` / `0036` | Existing MAG3 BEGIN/VALIDATE/COMMIT/ABORT, 18-byte data fragments, 20-byte correlated status |
| `0037` | Bonded encrypted notifications, 20 bytes `<BBHI3f>`, version 1, usable flags 7, u16 sequence and u32 sample-ms |
| `0038` | Bonded encrypted read/write-with-response, paged frozen metadata and MAG2 transfer |

Metadata: freeze `[5,1,255]`, then select `[5,1,page]` and read `[1,page,snapshot:u16,data]` for pages 0–5. Data is 88 bytes, 16 bytes/page except the final 8 bytes; responses are 20/12 bytes. A different snapshot ID invalidates the whole read. The freeze/select/read sequence runs on the existing GATT executor and cannot interleave with status polling. Normal polling is every three seconds and skips active operations.

Metadata data offsets are: state/error/flags at 1/2/3, last request at 4, factory CRC at 8, upright reference at 12, generation at 16, last full-object CRC at 20, committed native offset at 24, fixed Down/Forward at 36/48, boot/context/dropped at 60/64/68, durable stored request/full-object CRC at 72/76, active kind at 80 and zero reserved bytes at 81–87. Flags are factory-valid 1, reference-valid 2, auto-stored 4, auto-selected 8, manual-stored 16, off-selected 32, archive-ready 64. `0017` is desired next-boot selection; metadata selection flags describe the running selection. Active kind 0 alone does not establish OFF. Obsolete turn-count bits in `0036` are zero.

### App recording and 2D fit

Subscribe to `0037` through the existing CCCD queue and wait for the write response, freeze metadata, then accept samples. Ignore callbacks until the initial metadata is ready. Keep at most **1800 samples / 180 seconds**, publish UI progress at most four times/second, and stop/unsubscribe on screen dismissal or app background. No executor waits for the duration of a capture. Stop checks fresh metadata before unsubscribe. Invalid frames abort; duplicate/out-of-order sequence numbers are ignored with wrap-aware ordering. Finish rejects more than **10% missing sequence samples** or a timestamp gap over **one second**. These are app quality choices, not new firmware gates. Mailbox drops remain separate firmware telemetry.

Project every original point onto `(u, n × u)`. A centered/scaled double-precision SVD circle fit uses at least 24 observations, five robust-mask passes plus a final refit, >=80% inliers, rank 3 / condition <=1e4, largest observed angular gap <=30°, RMS <=min(2.5 µT, 7% radius), and Down standard deviation <=max(0.5 µT, 10% radius). Clean small circles are allowed. The native offset is `cx*u + cy*(n × u)`; float32 serialization is validated again against the <=80 µT norm and <=0.10 µT fixed-plane limits. Golden datasets in `app/src/test/resources/mag` were generated with RegattaLink `af40043` CLI implementations.

MAG2 is exactly 32 LE bytes `<4sHHII3fI>`: `MAG2`, version 1, length 32, factory CRC, upright reference, native offset, CRC32(first 28). BEGIN is `[1,1,request:u32,32:u16,fullCRC:u32]`; DATA is `[2,1,request:u32,offset:u16,payload]` with **12/12/8** payload bytes; COMMIT/ABORT are `[3 or 4,1,request:u32]`. All values fit ATT MTU 23. A 2D import preserves 3D, mode and heading trim. It neither modifies historical XYZ nor reindexes old archive observations.

### MLG-v2 and original-XYZ 3D

Validate manifest version 2, flags/reserved zero, 144 cells ×5 physical packed slots, 180-byte pages, 66 pages and total **11712 bytes**. The final page contains 12 data bytes (16-byte characteristic value). Android performs ordinary GATT long reads for the manifest and pages; there are no MTU-sized application fragments. Twelve **976-byte** groups each have `MLG\x02`, factory CRC, group CRC (CRC field zeroed), upright reference and identical Forward/Side/Down basis before 12 cells. Each cell has count, zero retained next field and five 15-byte XYZ/down-Q7 slots. Read slots `0..count-1`; there is no age-ring ordering.

The fitter flattens all original XYZ observations (<=720), never per-cell medians or historical label occupancy. It ports weighted nine-parameter SVD ellipsoid fitting and six robust/reweight passes from the CLI. Equal-area 12×12 **temporary analysis groups** balance observations after candidate correction. Acceptance requires >=36 observed groups, >=7 octants, covariance `det/(trace/3)^3 >=0.25`, >=80% original inliers, 10–100 µT radius, RMS <=2.5 µT and <=5%, and <=5% weighted tail beyond 10% radial error. Offset norm, symmetry, eigenvalue, determinant and firmware Gershgorin bounds are checked before and after float32 encoding. A good dataset packed into only 29 historical cells passes; planar data spread across 144 labels fails. Wire `used_sectors` counts analysis groups.

MAG3 remains 80 bytes with 18-byte DATA payloads. Save a durable 2D offset before calculating/installing 3D. Installing while AUTO is selected does not select MANUAL; replacing an already selected MANUAL model may activate it immediately and requires confirmation. Mode changes use fresh reserved-safe `0017` RMW with mask `0xA00`, restart and existing readback handling. There is no automatic 3D mode selection.

### Persistence, recovery and reset

`RegattaLinkMagCoordinator` owns a connection-generation/operation token across each operation, including the complete reset/restore. Wire work uses the existing BLE executor; numerical work uses the math executor. Disconnect/Service Changed invalidates the token, so late progress, exceptions and finally blocks cannot change or release another owner. Public config, Device Control, raw capture and OTA mutation gates also honor MAG ownership and unresolved recovery.

**Before any possible COMMIT**, synchronously save the exact object bytes, type, device/factory/upright binding, request and boot context in one atomic preferences record; abort before COMMIT if local saving fails. ACK is not durability. After a possible COMMIT there is no blind retry/new BEGIN/ABORT. On reconnect, fresh metadata plus the exact durable request, full-object CRC, auto-stored flag and **exact float32 offset bits** prove a MAG2 result even after MCU reboot. A full-object CRC alone is insufficient (the included IEEE CRC yields a codeword residue). MAG3 additionally requires a retained correlated `0036` result in the same boot; an installed-slot bit or 2D receipt cannot prove this 3D transaction. Foreign/ambiguous receipts remain persistently UNKNOWN and block new mutations. Explicit recovery only reads/proceeds from proven state. Explicit discard requires confirmation explaining uncertain device outcome and deletion of the local backup; it sends no device mutation.

Device Control opcode **12** is a full MAG reset, not archive-only deletion: archive, 2D, 3D and heading trim are cleared and the device selects AUTO. Factory calibration and the frozen upright reference remain. The app composite first reads valid stored 2D plus signed `0018` trim and durably saves the exact backup. It journals `RESET_MAY_HAVE_BEEN_SENT` before the existing 8-byte opcode12 request, requires correlated SUCCESS/OK from the 20-byte status, restores MAG2 with a fresh request and durable receipt, then restores trim and verifies readback. Only then may it delete the backup and report completion. Manual 3D is permanently lost and the confirmation states the return to 2D mode.

Backup phases survive process termination and reconnect. Resume never repeats a possibly effective reset. If reset status was lost across MCU reboot, the result stays UNKNOWN with the backup retained; empty model state alone cannot prove the reset. A changed device/factory/upright binding prevents restore. Normal boot/stream context changes alone do not invalidate the backup. After a failed restore import, explicit resume can retry only following a correlated terminal rejection; ambiguous COMMIT remains blocked.

Factory MAG provisioning remains manufacturing/service functionality and is not exposed by normal Tracker UI. Firmware downgrades are unsupported. The known motion-calibration firmware rollback limitation is outside this Android change.

Automated protocol/numerical/recovery tests are necessary but do not replace real Android/HW2 verification of sensor load, BLE coexistence, MTU-23 long reads, live heading, reboot/reset/restore and the legacy OTA-only upgrade path.

## Phone GNSS 001C

The frozen pre-production input is exactly 28 bytes, write-with-response:

| Offset | Width | Field |
| ---: | ---: | --- |
| 0 | 1 | version = 2 |
| 1 | 1 | validity flags |
| 2 | 2 | sample age, u16 LE ms |
| 4 | 4 | latitude, i32 LE degrees x1e7 |
| 8 | 4 | longitude, i32 LE degrees x1e7 |
| 12 | 2 | COG, u16 LE centidegrees |
| 14 | 2 | SOG, u16 LE cm/s |
| 16 | 2 | horizontal accuracy, u16 LE cm |
| 18 | 2 | altitude, i16 LE decimetres |
| 20 | 8 | UTC fix time, Unix epoch milliseconds, u64 LE |

Validity flags:
- bit0 position
- bit1 COG
- bit2 SOG
- bit3 horizontal accuracy
- bit4 altitude
- bit5 UTC fix time
- bits6..7 zero

The monotonic Android `Location.elapsedRealtimeNanos` timestamp is used only to
derive sample age at send time. Absolute UTC comes only from `Location.time` of
the same live GPS observation. A non-positive `Location.time` leaves bit5 clear
and encodes zero in bytes 20..27; it is never replaced with current wall-clock time.

A 28-byte normal ATT write requires negotiated ATT MTU >=31. Tracker requests the
shared connection MTU once per physical BLE connection through the serialized GATT
operation path (currently requesting 247) and forwards Phone GNSS only when the
negotiated value is at least 31. There is no 20-byte fallback. Disconnect/reconnect
resets this connection-local MTU readiness and negotiates again.

Tracker forwards fresh Android GPS observations through this characteristic at no
more than 1 Hz while desired Config bits 0 and 3 are both enabled and CAN is
available for the current session. The forwarding queue is latest-wins: at most one
write is scheduled/in flight and one newer pending observation replaces the older
pending observation. Cached last-known locations are never forwarded as fresh Phone
GNSS observations.

## Existing Config/Control payloads retained

The following existing payload contracts are unchanged under their new v2 UUIDs:

- Device Info `0012`;
- PGN Inventory `0013`;
- Raw CAN FIFO `0014`;
- LED Brightness `0015`;
- Heel/Pitch Damping `0016`;
- Diagnostic Log `0019`;
- Device Control `001A`.

Raw CAN and Diagnostic Log are destructive/bounded reads exactly as before. Device Control retains request IDs, terminal status handling, Restart and Factory Reset lifecycle ownership.

## Normal telemetry consumption

Tracker continues to use Motion 1 Hz as its ordinary IMU telemetry subscription. Boat State and Load telemetry remain independently discovered/consumed under `0024` and `0026`.

Boat State remains the normalized common navigation model. Do not create a separate client model for NMEA0183/local-compass navigation values.

Existing record length, version, validity and stale/freshness checks remain authoritative.

## OTA

Normal Android firmware installation still begins from a fully connected RegattaLink with Device Info available. Existing artifact validation, START metadata, DATA transfer, committed-offset reconciliation, MTU/PHY tuning and FINISH behavior remain unchanged.

The v2 OTA UUIDs are:

- service `0001`
- Control `0002`
- Data `0003`
- Status `0004`

### Post-reboot validation

After FINISH/boot selection, Tracker reconnects to the same bonded/configured BLE device address.

If Config/Device Info is healthy, the existing stable-ID/build validation remains in force.

If Config/Device Info cannot be read after the reboot, the post-boot check may use the secured OTA core alone:

1. discover the advertised OTA service;
2. use OTA Control SNAPSHOT;
3. read full OTA Status;
4. require target running build;
5. require boot result `VALIDATED`.

`ROLLBACK` is terminal failure. Wrong build or UNKNOWN/PENDING_VERIFY is not success.

The OTA-only connection is transport-only and is not presented as a fully operational Tracker connection. After OTA validation, ordinary configured reconnect establishes the normal Config/Telemetry session.

This is not a generic Android service-recovery flasher. Starting a firmware update without Device Info remains RegattaLink#275 CLI/service-tool scope.

## Android GATT operation rules

Tracker serializes callback-producing GATT operations.

Required behavior:
- no overlapping ordinary read/write/descriptor transactions;
- no service discovery race with characteristic traffic;
- Service Changed schedules rediscovery;
- control/config writes use write-with-response;
- OTA WNR is used only where capability/property permits;
- callback success does not equal OTA flash commit;
- ambiguous OTA writes reconcile against authoritative status;
- stale Android bond failures (`0x05` / `0x0f`) are surfaced as pairing/security problems.

## Compatibility policy

- production baseline is GATT v2 / schema 16+;
- no active `...6b71xxxx` production path;
- no dual-namespace OTA bridge;
- fixed v2 core UUID meanings do not move;
- unknown Config Word bits are preserved by client RMW;
- future Extension characteristics are optional;
- future schema generations may append protocol surface without moving the frozen prefix.

## #423 acceptance checklist

The Android migration is complete when:

- discovery scans for OTA `0001`;
- all active core UUIDs use `...6b72`;
- Device Info requires schema 16+;
- existing Device Name, LED, damping, PGN inventory, Raw FIFO, diagnostics and Device Control work at their v2 UUIDs;
- existing Motion 1 Hz, Boat State and Load telemetry work at their v2 UUIDs;
- Config `0017` is the single authoritative source for all assigned selector/config fields;
- every `0017` edit performs a fresh reserved-safe four-byte read-modify-write and marks the current process restart-required when the confirmed word changes;
- Boat Data TX master, Heel/Trim, NMEA0183, Phone GPS and local Compass selectors are exposed without auto-enabling each other;
- NMEA0183 baud exposes exactly 4800 / 9600 / 19200 / 38400;
- Load precision and MAG selection use their assigned `0017` bits;
- Heading Trim `0018` uses exact signed LE -180..+180° encoding and applies without restart;
- runtime status parses `001B` version 2 and remains separate from desired Config state;
- Phone GNSS `001C` uses the exact 28-byte v2 frame, send-time monotonic age, same-fix UTC epoch-ms, validity bits and one write-with-response transaction at negotiated ATT MTU >=31;
- Phone GNSS forwarding is gated by desired Config bits 0+3, stops on disconnect/OTA/disable, is rate-limited to 1 Hz and uses latest-wins buffering rather than a FIFO;
- while Phone GNSS forwarding is enabled, Android GPS acquisition is requested at 1000 ms while local adaptive sample persistence keeps its existing cadence;
- cached last-known locations are never forwarded as fresh Phone GNSS observations;
- firmware OTA uses `0001..0004`;
- post-reboot OTA success can be proven from secured OTA Status if Config/Device Info is temporarily unavailable, after which the normal configured reconnect is restored;
- no production code depends on the old Extension fallback or old `...6b71` characteristic map.

