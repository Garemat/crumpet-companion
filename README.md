# Crumpet Companion

A native Android **body** for [Crumpet](https://github.com/Garemat/crumpet) — the personal AI
companion. It's the phone gateway: it reads your whole fitness picture from **Health Connect** and
feeds it to Crumpet's brain, is a native **chat** face, and surfaces Crumpet's proactive nudges as
**push notifications** — all over your own WireGuard VPN, no third-party services.

> Single-user, sideloaded, never published. Like the rest of Crumpet.

## What it does (v1)
- **Health Connect sync** — nutrition, weight/body-fat, steps & workouts (MyFitnessPal, Hevy, Bend,
  Fitbit, …), and sleep → posted to the brain's `POST /health/ingest`, landing in Crumpet's existing
  fitness tables. WorkManager syncs every ~3h, on open, and on demand. Read-only; user-permissioned.
- **Chat** — talk to Crumpet over the same gateway WebSocket the web face uses (`/chat`, token-authed).
- **Push (self-hosted)** — a foreground service holds the gateway connection open so Crumpet's morning
  brief / post-gym check-ins arrive as notifications. **No Firebase.**
- **Calendar** — a read-only agenda glance / "Up next" from the system calendar (which DAVx5 syncs from
  Crumpet's Radicale). Adds still flow through the brain.

Design: a warm "cozy-dapper" theme (espresso + brass + jade, Fraunces × Hanken) — the dapper turtle.

## Stack
Kotlin · Jetpack Compose (Material 3) · Health Connect SDK · Ktor (HTTP + WebSockets) · WorkManager ·
DataStore · minSdk 26 / targetSdk 36. Mirrors the structure of the sibling `moonstone-companion` project.

## Build & install
```bash
# JDK 17–21 required (Android Studio's JBR works):
JAVA_HOME=/path/to/android-studio/jbr ./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## First-run setup (in the app → Settings)
1. **Connect:** enter the brain URL (e.g. `https://crumpet` via the Caddy edge, or `http://<tunnel-ip>:8810`)
   and a **device token** — add an entry to the brain's `CHAT_WS_TOKENS` keychain secret, e.g. `phone:<secret>`.
2. **Grant Health Connect** (nutrition/body/activity/sleep) and **calendar + notifications**.
3. **Save & connect** — starts the presence service. **Sync now** to pull your history.

## Layout
- `net/Net.kt` — the brain I/O: ingest POST + the resilient chat/push WebSocket.
- `health/HealthRepo.kt` — Health Connect reads → ingest records + today snapshot.
- `health/CalendarRepo.kt` — read-only system-calendar agenda.
- `sync/SyncWorker.kt` — periodic Health Connect → brain sync (watermark + dedup).
- `push/PresenceService.kt` — foreground connection → notifications.
- `ui/` — Compose theme, the turtle avatar, and the Home / Chat / Health / Setup screens.

Brain side (in the Crumpet repo): `crumpet/gateway/chat_ws.py` (`/health/ingest` + `push()`),
`crumpet/core/health_ingest.py`. See `docs/backlog/phone-companion.md` there.
