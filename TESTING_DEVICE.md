# Testing LR on a Connected Android Phone

## Prerequisites

- A phone running Android 8.0 (API 26) or newer.
- JDK 17 + Android SDK platform-tools on the dev machine.
- A USB cable, or the phone and computer on the same Wi-Fi network for wireless debugging.

## 1. Enable developer options and debugging on the phone

1. Settings → About phone → tap **Build number** 7 times → "You are now a developer."
2. Settings → System → Developer options → enable **USB debugging** (and **Wireless debugging**
   if you'd rather skip the cable).

## 2. Connect

**USB:**
```bash
adb devices
```
Accept the "Allow USB debugging?" prompt on the phone, then re-run `adb devices` — it should list
your device as `device` (not `unauthorized`).

**Wireless (Android 11+):**
```bash
adb pair <ip>:<pairing-port>      # code shown under Developer options → Wireless debugging → Pair device with pairing code
adb connect <ip>:<port>           # port shown on the main Wireless debugging screen
adb devices
```

## 3. Build and install

```bash
./gradlew installDebug
```

Installs as `ai.lequipe.lr.debug` — coexists with any existing `org.fossify.voicerecorder` install,
so you can A/B against the original app if it's still on the phone.

## 4. First launch

1. Grant the microphone permission when prompted.
2. Grant the storage permission / pick a recordings folder: a plain "confirm folder" dialog, then
   an in-app folder browser, then (Android 11+) the real system SAF folder-access dialog — three
   screens in a row, all expected.

## 5. Golden-path checklist

- [ ] Tap record → visualizer animates with real input, duration counts up; also prompts for the
      notification permission the first time.
- [ ] Pause/resume via the same button.
- [ ] Cancel mid-recording → confirmation dialog → the in-progress file is deleted; nothing shows
      up in the Recordings list.
- [ ] Record again, tap save → new row appears named like `20260729_140512.m4a`.
- [ ] Tap the row → plays through the phone's speaker (icon becomes pause); tap again → pauses in
      place (does **not** restart from 0:00).
- [ ] Long-press the row to select it, then tap the rename icon in the toolbar → new name sticks
      and still plays.
- [ ] Long-press to select, then tap the delete icon → asks "move to recycle bin?" (no checkbox)
      → item moves to the **Recycle Bin** tab.
- [ ] Recycle Bin tab shows an "Empty recycle bin" toolbar action; the Recordings tab doesn't.
- [ ] Restore the item from the Recycle Bin → reappears in Recordings.
- [ ] Settings screen shows only: save-recordings-folder, keep-screen-on, and the
      language/date-time rows — no format/bitrate/sample-rate/mic-mode rows.
- [ ] About screen's first FAQ entry credits Fossify Voice Recorder (GPLv3) with the fork URL.
- [ ] Backgrounding the app (home button) mid-recording keeps the foreground-service notification
      and recording running; reopening the app shows the correct elapsed duration.
- [ ] Unplugging headphones (if used) or an equivalent audio-route change doesn't crash playback.

## 6. Widget

1. Long-press the home screen → **Widgets** → find **LR** → drag onto the home screen.
2. Tap it → icon turns red, a recording starts.
3. Tap it again → icon turns white, recording stops and appears in the app.

## Why test on a real device at all

The emulator's audio input, storage/SAF permission dialogs, and battery/background-service
behavior are all approximations. A physical device is the only way to confirm: real microphone
audio quality, actual SAF folder-picker UI from the phone's file manager, and that the foreground
recording service survives being backgrounded or the screen locking.
