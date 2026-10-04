# RegattaLink BLE client contract — GATT v2 / schema 16

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
| `001C` | Phone GNSS Input | exactly 20 B, W with response |

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
| 9 | MAG background-calibration learning |
| 10..13 | reserved |
| 14..15 | NMEA 0183 baud selector |
| 16..31 | reserved |

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

All `0017` changes are persisted immediately but take effect only after RegattaLink restart. Reads expose the desired word; runtime subsystems continue using the boot-applied snapshot.

The first Tracker migration step consumes the already-existing UI functionality through this one word:
- global Boat Data TX from bit 0;
- Heel/Trim TX from bit 1;
- Load precision from bit 8.

The Tracker exposes all assigned schema-16 selectors/configuration from this same authoritative Config Word.

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

## Background MAG

Background MAG learning uses `0017` bit 9. No dedicated reset characteristic or Device Control opcode exists.

Normal user Factory Reset clears learned background calibration while preserving factory MAG calibration. Factory MAG provisioning remains manufacturing/service functionality transported through OTA Control and is not exposed by normal Tracker UI.

## Phone GNSS 001C

The frozen input is exactly 20 bytes, write-with-response:

| Offset | Width | Field |
| ---: | ---: | --- |
| 0 | 1 | version = 1 |
| 1 | 1 | validity flags |
| 2 | 2 | sample age, u16 LE ms |
| 4 | 4 | latitude, i32 LE degrees x1e7 |
| 8 | 4 | longitude, i32 LE degrees x1e7 |
| 12 | 2 | COG, u16 LE centidegrees |
| 14 | 2 | SOG, u16 LE cm/s |
| 16 | 2 | horizontal accuracy, u16 LE cm |
| 18 | 2 | altitude, i16 LE decimetres |

Validity flags:
- bit0 position
- bit1 COG
- bit2 SOG
- bit3 horizontal accuracy
- bit4 altitude
- bits5..7 zero

Tracker forwards fresh Android GPS observations through this characteristic at no more than 1 Hz while desired Config bits 0 and 3 are both enabled. The forwarding queue is latest-wins: at most one write is scheduled/in flight and one newer pending observation replaces the older pending observation.

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
- Load precision and MAG background-learning use their assigned `0017` bits;
- Heading Trim `0018` uses exact signed LE -180..+180° encoding and applies without restart;
- runtime status parses `001B` version 2 and remains separate from desired Config state;
- Phone GNSS `001C` uses the exact 20-byte v1 frame, send-time monotonic age, validity bits and one write-with-response transaction;
- Phone GNSS forwarding is gated by desired Config bits 0+3, stops on disconnect/OTA/disable, is rate-limited to 1 Hz and uses latest-wins buffering rather than a FIFO;
- while Phone GNSS forwarding is enabled, Android GPS acquisition is requested at 1000 ms while local adaptive sample persistence keeps its existing cadence;
- cached last-known locations are never forwarded as fresh Phone GNSS observations;
- firmware OTA uses `0001..0004`;
- post-reboot OTA success can be proven from secured OTA Status if Config/Device Info is temporarily unavailable, after which the normal configured reconnect is restored;
- no production code depends on the old Extension fallback or old `...6b71` characteristic map.

