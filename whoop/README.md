# whoop5: reverse-engineering the WHOOP 5.0 / MG

This is a toolkit for studying how the WHOOP app talks to a WHOOP 5.0 or MG strap
over Bluetooth Low Energy, so you can read your own strap's data without the
cloud. It does three things:

| Tool | What it does |
| --- | --- |
| `decode` | Reads an Android **HCI snoop log** of the official app talking to your strap and decodes each frame. This is the main reverse-engineering loop. |
| `scan` / `gatt` / `sniff` / `hr` | Connects to the strap directly, bonds with it, dumps the GATT table, logs every notification to `.jsonl` and streams live heart rate. |
| `frame` / `build` | Decodes or builds single frames by hand, for testing a guess about a command. |

The wire protocol is in [`docs/PROTOCOL.md`](docs/PROTOCOL.md). The framing and
CRCs are checked against a publicly documented frame; see `tests/`.

## Install

```bash
cd whoop
python -m venv .venv && . .venv/bin/activate
pip install -e '.[dev]'
pytest            # offline tests, no strap needed
```

Direct BLE works best on **Linux (BlueZ ≥ 5.66)** or Windows, where the tool can
bond with the strap itself. macOS cannot start pairing from a third-party app, so
there the strap must already be bonded to that Mac.

## Workflow 1: capture what the official app does (recommended)

This is how you learn the commands, their parameters and the response layouts.

1. On an Android phone, turn on **Developer options → Enable Bluetooth HCI snoop log**,
   then toggle Bluetooth off and on.
2. So that the capture includes GATT discovery (which maps handles to UUIDs):
   in Android Bluetooth settings, forget the strap and pair it again through the WHOOP app.
3. Do the thing you want to study in the WHOOP app, for example a sync, opening
   live HR, a haptic alarm or a battery check. Write down the time of each action.
4. Pull the log:
   ```bash
   adb bugreport bugreport.zip
   unzip -o bugreport.zip 'FS/data/misc/bluetooth/logs/*'   # path varies by vendor
   ```
   On some phones the file is `/sdcard/btsnoop_hci.log`, or `adb pull /data/misc/bluetooth/logs/`.
5. Decode it:
   ```bash
   python -m whoop5 decode btsnoop_hci.log                 # every frame + summary
   python -m whoop5 decode btsnoop_hci.log --type EVENT    # just one packet type
   python -m whoop5 decode btsnoop_hci.log --raw           # include non-frame chunks
   ```
   Each command the app sends (`app->strap COMMAND cmd=…`) and its reply
   (`strap->app COMMAND_RESPONSE`) appears in order, so you can line them up
   with what you did in the app. You can also open the same file in Wireshark to
   see the link layer.

## Workflow 2: talk to the strap directly

BLE allows one central at a time, so **fully close the WHOOP app** (or turn off
phone Bluetooth) first. For the first bond, put the strap in pairing mode.

```bash
python -m whoop5 scan                          # find the address, and whether it's 5.0/MG
python -m whoop5 gatt  AA:BB:CC:DD:EE:FF       # bond + dump every service/characteristic
python -m whoop5 hr    AA:BB:CC:DD:EE:FF       # live heart rate + R-R intervals
python -m whoop5 sniff AA:BB:CC:DD:EE:FF \
    --send GET_HELLO --send REPORT_VERSION_INFO --send GET_BATTERY_LEVEL \
    --out session.jsonl                        # log everything; Ctrl-C to stop
python -m whoop5 decode session.jsonl          # decode it later
```

`--send NAME:hexparams` sends raw parameters, e.g.
`--send TOGGLE_IMU_MODE:0101`. Commands that change strap state (clock, alarms,
reboot, feature flags, the history-ACK) are refused unless you pass
`--allow-write`. Be careful with the history commands: acknowledging history
(`HISTORICAL_DATA_RESULT`) can let the strap free that data before the WHOOP
app has uploaded it.

## Layout

```
whoop5/protocol.py   UUIDs, packet/command enums, CRC16/CRC32, frame encode/decode, reassembly
whoop5/decoders.py   known payload layouts (live HR, history, command responses, 0x2A37)
whoop5/btsnoop.py    Android HCI log → ATT writes/notifications (handles ACL fragmentation)
whoop5/ble.py        bleak client: bond, subscribe, send, GATT dump
whoop5/cli.py        the commands above
```

## Caveats

- Everything here comes from public community research and hasn't yet been checked
  against **your** strap. Your first captures will confirm or correct the
  offsets in `decoders.py`. Anything the tool doesn't recognise is still printed
  as raw hex, and those unknown records are the ones worth studying.
- WHOOP firmware updates can change the protocol. Keep your captures so you can
  diff them later.
- This is for interoperability with a device you own. Don't redistribute WHOOP's
  app or firmware, and remember that the WHOOP terms of service may restrict
  reverse engineering.
