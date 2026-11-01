# Road Ahead UK

A UK traffic and directions web app that tells you what the **smart motorway signs, message signs and incident feeds say about the road ahead, before you get there**, so you can choose to come off at the junction before it.

Waze relies on drivers reporting things they've already reached. The motorway control centres already know: they set the gantries to 50 mph and put a red X over lane 1 several miles upstream of an incident. Road Ahead reads those settings for **every gantry on your route**, not just the one in front of you, and turns them into warnings like:

> **6.9 mi ahead — M1: Lane 1 closed, 50 mph limit**
> Speed limits drop 60 → 50 mph, merge arrows 2.7 mi into the zone, red X 3.4 mi in. Cause: accident. +12 min.
> **Last exit before it: J13 in 2.1 mi.** [Find a diversion] [Stay on route]

## Features

- **Look-ahead along your route.** Every gantry, message sign and incident is snapped onto *your* carriageway. Bearing and road-number checks drop the opposite carriageway and bridges over the motorway. Each one gets a distance-to-go.
- **Restriction zones.** Back-to-back restricted gantries are merged into one zone: where the limit starts, the lowest limit, where the lanes close, how long it lasts, and the estimated delay.
- **Real-looking signs.** Gantries are drawn lane by lane (speed roundels, flashing red X, merge arrows, end-of-restriction), and message signs are shown in amber text.
- **Spoken and on-screen alerts** at your chosen horizon (5/10/15/25 mi), again at 2 mi and again at ½ mi. Each one says where the last exit before the problem is.
- **"Find a diversion".** Tries alternative routes and detours to either side of the hazard. Each one is checked against live data and compared with staying put ("+5 min, avoids the closure").
- **Route choice by real arrival time.** Each route's score is its drive time plus the expected delay from what's on it.
- **Drive modes.** GPS (with screen wake-lock and automatic re-route if you go off route) or a simulated drive at 1–30× speed for trying it out.
- **Live refresh.** Hazards are re-checked every 60 s while driving, because gantry settings change by the minute.

## Android app (APK, with Android Auto)

There is a standalone Android version in [`android/`](android/README.md). It runs the whole engine on the phone and adds Android Auto support, Waze-style alerts and reporting, speed cameras and turn-by-turn voice. A ready-to-install APK is at `dist/RoadAhead-0.1.1.apk`. See the [Android README](android/README.md) for install steps and the Android Auto developer setting.

## Quick start (web version)

```bash
npm install
npm start            # http://localhost:3000
npm test
```

Enter a start and destination (or tap ◎ for GPS), press **Plan route**, then **Drive (GPS)** or **Simulate drive**.

Phones need HTTPS for GPS. Put the app behind a reverse proxy with TLS, or use a tunnel (e.g. `cloudflared`, `ngrok`) while testing. The page is a PWA, so you can "Add to Home Screen".

## Data sources

| Source | What it gives | Access | Status in this app |
|---|---|---|---|
| **National Highways NTIS** (DATEX II) | England's motorways/trunk roads: incidents, roadworks, **matrix-signal (gantry) settings, VMS text**, plus the location table for every sign | Free, but you must subscribe with National Highways to get endpoint URLs and credentials | Supported. Configure `NH_*` in `.env` |
| **Traffic Scotland** (DATEX II) | Incidents, roadworks, VMS | Registration | Supported. Configure `TS_*` |
| **Other DATEX II feeds** (e.g. Traffic Wales) | Same format | Varies | Supported via `DATEX2_FEEDS` |
| **TfL Road Disruptions** | London incidents and works, with lane closures parsed from text | Public (key optional) | **Live out of the box** |
| **OSRM** | Routing, plus motorway diverge points (the exits) | Public demo server | Live (self-host for real use) |
| **Nominatim** | Place search | Public, 1 req/s (throttled) | Live |
| **Overpass / OSM** | Junction numbers (J13) for the exits | Public | Live when reachable, otherwise exits show as "Exit" |

### ⚠ Demo mode

Gantry and sign data for England only comes from NTIS, which needs a subscription. Until you configure it, the app runs a **simulator**: it puts plausible gantries every ~1.1 km on the motorways your route uses, and generates incidents, roadworks and queues with matching gantry sequences and message signs. Everything simulated has a purple **DEMO** badge. The simulator turns off automatically once a DATEX II feed is configured (or force it with `DEMO_MODE=false`).

### Connecting real National Highways data

1. Apply for NTIS / DATEX II access through National Highways. You will be given the publication URLs (unplanned events, planned events, VMS and signal settings, and the VMS/signal location tables) and either basic-auth credentials or a subscription key.
2. Copy `.env.example` to `.env` and fill in the `NH_*` URLs and credentials.
3. Restart. The status line at the bottom of the panel shows each feed's event count, or its error.

The DATEX II parser (`server/lib/datex2.js`) is built to be tolerant. It walks the XML for the standard elements (`situationRecord`, `vmsUnit`/`vmsControllerStatus`, `vmsUnitRecord`, `pointCoordinates`, `tpegLinearLocation`, pictogram descriptions, and so on) and handles both v2.3 and v3 layouts. **It has been tested against hand-written fixtures that follow the DATEX II schema, not against a live NTIS feed yet.** Expect to adjust it for NH's exact profile once you have real data:
- the lane-index convention on gantries
- pictogram codes
- how sign locations are referenced

Save a sample response into `test/fixtures/` and add a test.

## How it works

```
             ┌───────────── providers (polled) ──────────────┐
NTIS DATEX II ─┐                                              │
Traffic Scot. ─┼─► datex2.js parser ─┐                        │
TfL JSON ──────┘   tfl.js ───────────┼─► normalised events ───┤
Demo simulator ──────────────────────┘                        │
                                                              ▼
OSRM routes ──► RouteLine (grid-indexed) ──► analyse.js: snap each event to the route
                                              (distance, bearing, road checks)
                                              → zones from consecutive gantries
                                              → attach causes (incidents, VMS)
                                              → delay estimates
                                              → last exit before each hazard
                                                       │
                                     planner.js: score routes, build diversions
                                                       │
                     browser: road-ahead list, alerts, voice, map, drive mode
```

- `shared/geo.js`: geometry used by both server and browser (snapping, distance along route, bearings).
- `server/lib/analyse.js`: the core "road ahead" logic.
- `server/lib/datex2.js`: the DATEX II parser.
- `server/providers/*`: TfL, the generic DATEX II feed, and the demo simulator.
- `server/lib/planner.js`: route scoring and diversion search.
- `public/`: the front end (Leaflet, no build step).

### API

| Method | Path | Body / query | Returns |
|---|---|---|---|
| GET | `/api/status` | | demo flag, per-provider status |
| GET | `/api/geocode?q=` | | `[{name, location}]` (GB only) |
| GET | `/api/events?bbox=w,s,e,n` | | normalised events |
| POST | `/api/route` | `{from:[lon,lat], to:[lon,lat]}` | routes with `analysis.items` (the road ahead) |
| POST | `/api/analyse` | `{routeId}` | refreshed analysis |
| POST | `/api/divert` | `{routeId, hazardId, position, to}` | `{stay, options[]}` |

## Limitations and next steps

- **Use hands-free only.** Alerts are spoken, and the app shouldn't be handled while driving. Have a passenger operate it, or mount the phone.
- The public OSRM, Nominatim and Overpass servers are for light use only. For real traffic, self-host OSRM (or move to Valhalla/GraphHopper, which can route around a polygon) and use a commercial geocoder.
- Delay figures for gantry zones are estimates (time lost at the posted limit plus a lane-closure penalty), unless the feed provides a delay.
- Native apps (CarPlay / Android Auto) would need a native wrapper. The look-ahead logic is server-side, so it can be reused as-is.
- Possible additions: user hazard reports (Waze-style), historic journey-time data (NH WebTRIS), and push alerts for saved commutes.
