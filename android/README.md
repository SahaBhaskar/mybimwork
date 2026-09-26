# WHOOP 5 Lab (Android)

This is an Android app for exploring your WHOOP 5.0 / MG strap directly over
Bluetooth. It lets you:

- **Find the strap.** It scans for straps nearby, lists ones already bonded to
  the phone, and tells a 5.0/MG apart from a 4.0.
- **Connect and bond.** It negotiates a large MTU, bonds with the strap (the
  WHOOP characteristics require an encrypted link) and subscribes to every
  notify characteristic.
- **Show live data.** Heart rate and R-R intervals come from the standard `2A37`
  profile or WHOOP's own `0x28` stream. It also shows the battery level.
- **Send commands.**
  - One-tap buttons: Hello, Version, Battery, Clock, Data range, Realtime HR, IMU on/off.
  - A custom command box takes a name or number plus parameter hex. Commands that
    change strap state (clock, alarms, reboot, feature flags, the history ACK) are
    blocked unless you turn on the switch.
- **Record everything.** Each connection writes a `.jsonl` session log. **Share
  log** sends it anywhere, and on a computer `python -m whoop5 decode whoop_*.jsonl`
  (from `../whoop`) decodes it.

## Get the APK

Every push that touches `android/` is built by GitHub Actions
(`.github/workflows/android.yml`):

1. Open the repo's **Actions** tab, pick the latest **Android app** run, and
   download the `whoop5-lab-debug-apk` artifact (a zip containing `app-debug.apk`).
2. Copy it to the phone and open it. Allow "install unknown apps" for your file
   manager when asked. From a computer you can instead run `adb install app-debug.apk`.

Each CI build is signed with a fresh debug key. To install a newer build over an
old one, uninstall the old one first.

Build locally with Android Studio (open this `android/` folder), or with an
Android SDK installed:

```bash
./gradlew :protocol:test :app:assembleDebug
```

## Using it

1. **Fully close the WHOOP app**, or turn off its Bluetooth access. The strap
   accepts only one connection at a time.
2. For the first bond, put the strap in pairing mode. Then tap **Scan** and pick
   the strap. Accept the Android pairing prompt if it appears.
3. Once the status shows *Ready*, heart rate should start updating. Try the
   command buttons and watch the log.
4. Tap **Share log** to export the session.

After a session, reopen the WHOOP app so it can sync. If it has trouble
reconnecting, toggle phone Bluetooth.

## Code

```
protocol/   pure Kotlin: framing, CRC16/CRC32, reassembly, command builder, decoders (+ JVM unit tests)
app/        Android: WhoopBle (GATT + serialized operation queue), SessionLog, Compose UI
```

`protocol/` is a port of the Python toolkit in `../whoop` and shares its test
vector. The protocol notes are in `../whoop/docs/PROTOCOL.md`.

## Limitations

- The connection lives only while the app is open. There's no background service
  yet, so history syncing over long periods isn't supported.
- Decoders cover only the layouts that are publicly known. Everything else is
  logged as hex for you to study.
- For use with your own device. WHOOP's terms of service may restrict reverse
  engineering.
