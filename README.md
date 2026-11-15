# Telegram Audio Listener App

Android app that listens for Telegram bot commands and records/uploads audio.

## Commands (send to the bot)
- `/record` or `record` — start recording
- `/stop` or `stop` — stop and upload
- `/status` or `status` — check status

## Build with GitHub Actions
1. Push this repo to GitHub
2. Go to **Actions** tab → **Build APK** workflow
3. Click **Run workflow**
4. Download the APK from the Artifacts section after the job finishes

## Local build (optional)
```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

## Permissions
- RECORD_AUDIO
- INTERNET
- FOREGROUND_SERVICE / MICROPHONE
- POST_NOTIFICATIONS
- RECEIVE_BOOT_COMPLETED
- WAKE_LOCK
