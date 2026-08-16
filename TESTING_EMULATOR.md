# Testing LR on an Android Emulator

## Prerequisites

- Android Studio (or standalone `sdkmanager`/`avdmanager`) with an SDK platform ≥ API 26.
- JDK 17 on `PATH`/`JAVA_HOME`.
- An AVD running **API 26+** (recommended: API 34, x86_64, "Google APIs" — no Play Store needed).

## 1. Create/start the emulator with a working microphone

1. Android Studio → Device Manager → create a device if you don't have one (API 26+).
2. Start it, open **Extended controls** (`...` on the emulator toolbar) → **Microphone**.
3. Enable **"Virtual microphone uses host audio input"** — without this the emulator feeds silence
   to `RECORD_AUDIO`, so recordings will be flat/silent (still valid for testing the pipeline, just
   not audibly useful).

## 2. Build and install

```bash
./gradlew installDebug
```

Installs as `ai.lequipe.lr.debug` — this coexists with any previously-installed
`org.fossify.voicerecorder` build, so you can keep both on the same emulator for comparison.

## 3. First launch

1. Grant the microphone permission when prompted.
2. Grant the storage permission / pick a recordings folder: a plain "confirm folder" dialog, then
   an in-app folder browser, then (Android 11+) the real system SAF folder-access dialog — three
   screens in a row, all expected, pick any folder (e.g. the emulator's internal `Music` folder).

## 4. Golden-path checklist

- [ ] Tap record → visualizer animates, duration counts up; also prompts for the notification
      permission the first time (needed for the recording foreground-service notification).
- [ ] Pause/resume via the same button.
- [ ] Cancel mid-recording → confirmation dialog → the in-progress file is deleted; nothing shows
      up in the Recordings list.
- [ ] Record again, tap save → new row appears named like `20260729_140512.m4a`.
- [ ] Tap the row → plays (icon becomes pause); tap again → pauses in place (does **not** restart
      from 0:00).
- [ ] Long-press the row to select it, then tap the rename icon in the toolbar → new name sticks
      and still plays.
- [ ] Long-press to select, then tap the delete icon → asks "move to recycle bin?" (no checkbox)
      → item moves to the **Recycle Bin** tab.
- [ ] Recycle Bin tab shows an "Empty recycle bin" toolbar action; the Recordings tab doesn't.
- [ ] Restore the item from the Recycle Bin → reappears in Recordings.
- [ ] Settings screen shows only: save-recordings-folder, keep-screen-on, and the
      language/date-time rows — no format/bitrate/sample-rate/mic-mode rows.
- [ ] About screen's first FAQ entry credits Fossify Voice Recorder (GPLv3) with the fork URL.

## 5. Widget

1. Long-press the emulator home screen → **Widgets** → find **LR** → drag onto the home screen.
2. Tap it → icon turns red, a recording starts (check the notification shade).
3. Tap it again → icon turns white, recording stops and appears in the app.

## Known emulator quirks

- Some system images have no real audio input path even with the virtual-mic toggle on — a
  recording still gets created (near-silent), which is enough to validate save/rename/delete/play
  wiring; it's not useful for judging actual audio quality.
- If the emulator was reset/wiped, you'll be asked for the recordings folder again on next launch.
