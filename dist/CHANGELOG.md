# Road Ahead — release history

Each version folder holds the builds for that release. Nothing is overwritten.
`.apk` = install directly for testing. `.aab` = upload to Google Play (from 0.1.4).

| Version | versionCode | Files | Changes |
|---|---|---|---|
| 0.1.4 | 5 | apk, aab | First Google Play-ready build: targets Android 16 (API 36), as Play requires. Release builds only accept Google's official Android Auto hosts. Adds the `.aab` bundle. |
| 0.1.3 | 4 | apk | Hazard/camera alerts reach Android Auto as messages from "Road Ahead". Voice reply files a report. |
| 0.1.2 | 3 | apk | ☰ → Android Auto setup check screen. |
| 0.1.1 | 2 | apk | UI kept clear of the status and navigation bars (Android 15 edge-to-edge). |
| 0.1.0 | 1 | apk | First Android release: on-device road-ahead engine, GPS drive mode, voice, reporting, speed cameras, Android Auto screens. |

All builds are signed with the same key (SHA-256 `b107b238…2d4c02`), so each installs over the previous one.
