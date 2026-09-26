# WHOOP 5.0 / MG BLE protocol notes

WHOOP 5.0 and MG share one hardware and protocol family, which the official app
calls "Maverick". These notes collect protocol *facts* (UUIDs, offsets, CRC
parameters, command numbers) from public community research. The code in this
folder is written from these facts; nothing is copied from those projects.

**Status legend:** ✅ checked by a test in this repo · 📚 reported by public
sources, not yet confirmed on our strap · ❓ open question

## GATT

| Role | UUID | |
| --- | --- | --- |
| WHOOP service | `fd4b0001-cce1-4033-93ce-002d5875f58a` | 📚 |
| Command write (app → strap) | `fd4b0002-…` write / write-without-response | 📚 |
| Command responses | `fd4b0003-…` notify | 📚 |
| Events | `fd4b0004-…` notify | 📚 |
| Bulk data (history, often fragmented) | `fd4b0005-…` notify | 📚 |
| Memfault crash dumps | `fd4b0007-…` notify | 📚 |
| Standard Heart Rate | `180D` / `2A37` | 📚 |
| Battery / Device info | `180F`/`2A19`, `180A` | 📚 |

WHOOP 4.0 uses service `61080001-8d6d-82b8-614a-1c8cb0f8dcc6` and a *different*
framing (5-byte header, CRC8). `scan` reports which generation it sees.

**Security:** every `fd4b` characteristic needs an encrypted, bonded link.
Without a bond, subscriptions hang and writes fail with "Insufficient
Authentication". None of the WHOOP characteristics is readable, so iOS and macOS
never start pairing for a third-party app. Bond from Linux or Windows, or reuse a
bond the official app created on the same device. 📚

## Frame ✅

```
off  size  field
0    1     0xAA           start of frame
1    1     0x01           version
2    2     u16 LE         inner length + 4
4    1     0x00           role A
5    1     0x01           role B
6    2     u16 LE         CRC16-MODBUS (poly 0xA001 reflected, init 0xFFFF) over [0..6)
8    n     inner packet
8+n  4     u32 LE         CRC32 (zlib / IEEE) over inner packet
```

Inner packet: `[packet_type][seq][command][params…]`. Commands sent by the app
are zero-padded to a multiple of 4 bytes.

Reference frame (TOGGLE_IMU_MODE on), reproduced in `tests/`:
```
aa 01 0c 00 00 01 e7 41 | 23 f1 6a 01 01 00 00 00 | 58 e9 61 fc
```

## Packet types 📚

| byte | name | byte | name |
| --- | --- | --- | --- |
| 0x23 | COMMAND | 0x30 | EVENT |
| 0x24 | COMMAND_RESPONSE | 0x31 | METADATA |
| 0x25 | PUFFIN_COMMAND (battery pack) | 0x32 | CONSOLE_LOGS |
| 0x26 | PUFFIN_COMMAND_RESPONSE | 0x33 | REALTIME_IMU |
| 0x28 | REALTIME_DATA (live HR) | 0x34 | HISTORICAL_IMU |
| 0x2B | REALTIME_RAW_DATA | 0x35–0x38 | Puffin events / logs / metadata |
| 0x2F | HISTORICAL_DATA | | |

## Commands 📚

The full list is in `whoop5/protocol.py` (`Cmd`). Useful read-only ones to start with:
`GET_HELLO 0x91`, `REPORT_VERSION_INFO 0x07`, `GET_BATTERY_LEVEL 0x1A`,
`GET_EXTENDED_BATTERY_INFO 0x62`, `GET_CLOCK 0x0B`, `GET_DATA_RANGE 0x22`,
`GET_BODY_LOCATION_AND_STATUS 0x54`, `TOGGLE_GENERIC_HR_PROFILE 0x0E` (param `01`
turns on standard `2A37` HR).

Reported parameter formats:
- `TOGGLE_IMU_MODE` `[rev=01][enable]`
- `SET_CLOCK` `[u32 epoch][00]`
- `GET_ALARM_TIME` `[04][index]`
- `RUN_ALARM` / `DISABLE_ALARM` `[02][index]`
- `SET_FF_VALUE` `[flag name ASCII, NUL-padded to 32][value '1'/'2'][7×00]`. The flag `enable_r22_packets` turns on the biometric history stream.

History sync (📚): `ENTER_HIGH_FREQ_SYNC` → `SEND_HISTORICAL_DATA` → the strap
streams `0x2F` chunks on `fd4b0005` → the app ACKs with `HISTORICAL_DATA_RESULT`
→ `EXIT_HIGH_FREQ_SYNC`. **An ACK may let the strap delete that range.**

## Known payloads (offsets from inner byte 0)

**REALTIME_DATA 0x28, record type 2, about 1 Hz** 📚
`[2..6)` u32 unix time · `[8]` bpm · `[9]` valid flag · `[10..12)` u16 R-R ms.
A 116-byte variant appears during sync: bpm at 22, quaternion floats at 41–56.

**HISTORICAL_DATA 0x2F, record type 18 (116 B), one per second** 📚
`[2..4)` u16 sequence · `[14]` bpm · `[15]` flag (0 none, 1 HR+RR, 2 +extra) ·
`[16..18)` u16 R-R ms · `[29]` smoothed bpm · `[33..49)` quaternion W,X,Y,Z float32.
Other sources also report skin temperature, motion and gravity vectors in history
chunks. ❓ Confirm their offsets with your own captures.

## Open questions to settle with your own captures ❓

1. The exact bytes of the app's first `GET_HELLO`, and what the two responses
   (serial, session token) contain.
2. Whether the `0x28` stream flows without any command once bonded.
3. Response layouts for battery, version, clock and data range.
4. Event (`0x30`) type IDs: wrist on/off, charging, double-tap and so on.
5. Where skin temperature, SpO₂ and respiratory data live in history records.
6. Any MG-specific differences, such as ECG and blood-pressure features, which
   are probably separate commands or packet types.

## Sources

- Rudra5417/whoop5-protocol (MIT), `docs/PROTOCOL-WHOOP5.md`: https://github.com/Rudra5417/whoop5-protocol
- Sophonbot0/whoop-vault (MIT), a Python/BlueZ implementation: https://github.com/Sophonbot0/whoop-vault
- ryanbr/noop (PolyForm Noncommercial, used for reference only): https://github.com/ryanbr/noop
- jogolden/whoomp (WHOOP 4.0), plus the Part 4 write-up at https://zulusierra.co/vestigator-part-4-whoop-protocol-cracking/
