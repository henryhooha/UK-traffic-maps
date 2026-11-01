# Road Ahead for Android (+ Android Auto)

A standalone Android app. No server is needed: the "road ahead" engine runs on the phone. While you drive, it warns you about these things before you reach them:

- **Smart-motorway gantries**: lane closures (red X), speed reductions, merge arrows. All the gantries on your route are combined into one warning, e.g. "Lane 1 closed in 4 miles, 50 mph from 2 miles, last exit J13 in 1.2 miles".
- **Waze-style hazards**:
  - accidents
  - car stopped or broken down
  - lane blocked
  - object on the road
  - traffic jams and queues
  - roadworks
  - road closures
  - weather
  - police (your own reports)
- **Speed cameras**: fixed cameras and average-speed zones from OpenStreetMap, plus mobile cameras you report.
- **Turn-by-turn voice directions.** Music is ducked (turned down) while it speaks.
- **"Avoid"**: finds ways round a hazard and compares each with staying put.

## Install the APK

1. Copy `RoadAhead-0.1.3.apk` (in `dist/` at the repo root) to your phone, or download it there.
2. Open it. Android will ask you to allow "Install unknown apps" for your browser or file manager. Allow it, then tap **Install**.
3. Open **Road Ahead** and allow:
   - **Location: Precise, While using the app.** GPS precision is required for lane-level warnings, the same as Waze.
   - **Notifications.** These carry the hazard pop-ups and the driving notification.
4. Recommended: Settings → Apps → Road Ahead → Battery → **Unrestricted**, so Android doesn't pause it mid-drive.

Requires Android 8.0+. Built and signed for sideloading, not the Play Store.

**Signing key:** Android only installs an update over the top if it's signed with the same key. The key isn't in git (it's a credential). Keep the `roadahead-personal.jks` keystore you were given, put it in `android/keystore/`, and create `android/keystore.properties` with `storeFile`, `storePassword`, `keyAlias` and `keyPassword`. Without it, builds use a debug key, and you'd have to uninstall the old app (losing your saved reports) before installing.

## Android Auto

> **Android Auto 17.9 and later hide sideloaded car-screen apps, even with Unknown sources on.** That is Google's restriction (see [this report](https://github.com/prototux/lapin/issues/1)). Road Ahead's own car screen only appears if it's installed from the Play Store, for example through a Play Console internal-testing track.
>
> **What works without the Play Store:** run Road Ahead on the phone, and navigate with Waze or Google Maps in the car. Hazard, gantry and camera alerts arrive as **messages from "Road Ahead"**. Android Auto shows them on the car screen and reads them aloud. Tap **Reply** and say "car stopped", "accident", "lane blocked", "police" or "mobile camera" to file a report. Turn on Android Auto settings → Messaging → **Show message notifications**.
>
> The steps below still apply on older Android Auto versions, and once the app is installed from Play.

In the app, ☰ → **Android Auto setup** checks your phone and walks you through these steps.

Android Auto only shows sideloaded apps once you enable developer "unknown sources":

1. On the phone open **Android Auto settings**. Usually Settings → Connected devices → Connection preferences → Android Auto; on some phones search Settings for "Android Auto".
2. Scroll to the bottom and tap **Version** about 10 times until it asks to enable developer settings. Accept.
3. Tap ⋮ (top right) → **Developer settings** → tick **Unknown sources**.
4. **Force-stop Android Auto** (Settings → Apps → Android Auto → Force stop) or restart the phone, so it rescans for apps. Then in Android Auto settings → Display → **Customise launcher**, make sure Road Ahead is ticked.
5. Connect to the car (or start Android Auto). **Road Ahead** appears in the app launcher. You may need to customise the launcher to show it.

On the car screen:
- **Search** (recent destinations first): picking one plans the route and starts navigating.
- **Routing card**: the next turn with its distance, plus ETA. The ETA turns yellow or red when there are delays ahead.
- **Road-ahead view**: a self-drawn view (route, hazard zones, gantries with lane aspects, cameras, your reports) with a look-ahead card for the next problem and the current gantry speed limit. It is a vector drawing, not map tiles.
- **Pop-up alerts** with **Avoid / Stay** buttons.
- **⚠ Report** opens a one-tap grid: accident, car stopped, lane blocked, object, traffic, police, mobile camera, fixed camera, roadworks, weather, closure.

**This build hasn't been run on a car or phone yet.** It compiles, passes its 14 unit tests and passes Android lint with 0 errors. The phone UI was checked in a browser. Treat the first drive as a test: let a passenger watch, and expect fixes to be needed.

## Where the data comes from (and what's simulated)

| What | Source | Notes |
|---|---|---|
| Gantry signals, message signs, incidents (England) | **National Highways NTIS** (DATEX II) | Needs a free NTIS subscription. Paste the URLs and credentials into Settings. |
| Until then | **Demo simulator** | Plausible gantry sequences and incidents on the motorways you drive, all marked **DEMO**. Turns off once an NTIS signal URL is set. |
| London incidents, roadworks, breakdowns | TfL | Live, no key needed |
| Scotland / Wales | Traffic Scotland / Traffic Wales DATEX II | Add the URLs under "Extra DATEX II URLs" |
| Speed cameras, average-speed zones, junction numbers | OpenStreetMap (Overpass) | Live. Coverage depends on OSM mappers. |
| Your reports | Stored on the phone | Expire automatically. Fixed cameras never expire. |
| Routing / search | OSRM / Nominatim public servers | Fine for one user. Change them in Settings if you self-host. |

### About reporting to Waze and Google Maps

**Neither Waze nor Google Maps lets other apps submit reports.** No public API exists. Waze only exchanges data with road authorities through its "Waze for Cities" programme. This app does what is actually possible:

- Your reports are kept on the phone and warn **you** on later drives. A report placed by your heading only triggers for traffic going your way.
- **Fixed speed cameras** can be sent to **OpenStreetMap** as a public map note (My reports → Send to OSM). Mappers verify it and add it to the map, which feeds every OSM-based app, including this one.
- **"Waze"** next to a report opens Waze at that spot, so you can report it there with Waze's own buttons.
- **Export as GPX** gives you a file of all your reports.

Waze's own live reports (police, hazards from other drivers) can't be pulled into this app either. The Waze feed is closed to the public.

## Settings

Tap ☰ in the app to change:
- the first warning distance (5, 10, 15 or 25 mi)
- voice
- turn-by-turn directions
- camera alerts
- minor items
- the demo simulator
- National Highways NTIS URLs and credentials
- the TfL key
- extra DATEX II URLs
- the routing, search and Overpass servers

## Building it yourself

```bash
cd android
echo "sdk.dir=$HOME/Android/Sdk" > local.properties   # path to your Android SDK
./gradlew assembleRelease                            # → app/build/outputs/apk/release/app-release.apk
./gradlew testDebugUnitTest                          # core engine tests
```

### Code layout
- `core/`: pure Kotlin, unit-tested.
  - `Analyse.kt`: zones, snapping, exits.
  - `AlertEngine.kt`: when to warn, and what to say.
  - `Datex2.kt`, `Tfl.kt`, `Overpass.kt`, `Osrm.kt`, `Geocoder.kt`: data sources.
  - `Demo.kt`: the simulator.
  - `Reports.kt`: your reports, plus the OSM-note sharing.
  - `Instructions.kt`: turn text.
- `service/`: Android runtime.
  - `NavController`: shared state.
  - `DriveService`: foreground GPS service.
  - `LocationTracker`: fused high-accuracy location at 1 Hz.
  - `Speaker`: voice, with music ducking.
  - `Settings`.
- `car/`: Android Auto (Car App Library, navigation category).
  - `NavScreen`, `MapRenderer`, `ReportScreen`, `SearchScreen`, `DivertScreen`.
- `ui/MainActivity` + `assets/web/`: the phone UI (a Leaflet map in a WebView, driven by the native engine).
