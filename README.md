# Fly Bird — Online Multiplayer Edition 🐦🎮

A complete 2D Android arcade game written in **Java** using **Android Canvas** for 60 FPS gameplay, **SharedPreferences** for local persistence, **Google Mobile Ads SDK (AdMob)** for banner and rewarded ads, and a standalone **Node.js WebSocket + SQLite server** for real two-player online multiplayer and validated leaderboards.

> **Strict Zero-Firebase Architecture:** This project does **not** use Firebase Authentication, Firestore, Realtime Database, or any Firebase SDKs.

---

## 1. Project Folder Structure

```text
.
├── .env.example                                     # Configurable Server URLs & AdMob Ad Unit IDs
├── build.gradle.kts                                 # Root Gradle configuration
├── settings.gradle.kts                              # Root project settings ("Fly Bird")
├── app/
│   ├── build.gradle.kts                             # App module Gradle config (Java, OkHttp, AdMob)
│   └── src/main/
│       ├── AndroidManifest.xml                      # Permissions (INTERNET) & AdMob App ID metadata
│       ├── java/com/example/
│       │   ├── MainActivity.java                    # Fullscreen host, AdMob container & config dialogs
│       │   ├── GameView.java                        # Custom 60 FPS 2D Canvas renderer & touch controller
│       │   ├── GameManager.java                     # SharedPreferences state, coins, best score & 6 birds
│       │   ├── MultiplayerManager.java              # 2-player room state machine & WebSocket protocol
│       │   ├── WebSocketClient.java                 # Thread-safe OkHttp WebSocket client
│       │   ├── SoundManager.java                    # SoundPool SFX & MediaPlayer looping background music
│       │   ├── AdManager.java                       # Google Mobile Ads Banner & Rewarded Ad controller
│       │   └── LeaderboardManager.java              # Local SharedPreferences & Online REST Leaderboards
│       └── res/
│           ├── raw/
│           │   ├── sfx_flap.wav                     # Synthesized wing flap sound effect
│           │   ├── sfx_coin.wav                     # Gold coin chime sound effect
│           │   ├── sfx_hit.wav                      # Pipe/ground collision sound effect
│           │   ├── sfx_button.wav                   # UI button tap sound effect
│           │   └── bgm_arcade.wav                   # Looping chiptune background music
│           └── values/
│               ├── strings.xml                      # App name, default server placeholders & test AdMob IDs
│               └── themes.xml                       # Fullscreen NoActionBar theme
└── server/
    ├── package.json                                 # Node.js server dependencies (ws, express, better-sqlite3)
    ├── .env.example                                 # Server port, SQLite path & anti-cheat settings
    ├── schema.sql                                   # SQLite / PostgreSQL table definitions
    ├── README.md                                    # Detailed server setup & deployment guide
    └── src/
        ├── index.js                                 # HTTP REST + WebSocket server entry point
        ├── roomManager.js                           # 6-digit room codes, 2P capacity, sync & cleanup
        ├── scoreManager.js                          # Server-side score rate anti-cheat validation
        └── database.js                              # Persistent SQLite leaderboard & match storage
```

---

## 2. Audio Assets (`app/src/main/res/raw/`)

Pre-generated `.wav` arcade audio assets are included in `app/src/main/res/raw/` so sound effects and background music work immediately out of the box:
- `sfx_flap.wav` — Played when tapping to fly (`SoundManager.playFlap()`)
- `sfx_coin.wav` — Played when collecting gold coins or unlocking birds (`SoundManager.playCoin()`)
- `sfx_hit.wav` — Played upon colliding with pipes or the ground (`SoundManager.playHit()`)
- `sfx_button.wav` — Played on UI button presses (`SoundManager.playButton()`)
- `bgm_arcade.wav` — Looping background music (`SoundManager` `MediaPlayer`)

To replace these with custom studio audio files, drop replacement `.wav`, `.mp3`, or `.ogg` files with the exact same resource names (`sfx_flap`, `sfx_coin`, `sfx_hit`, `sfx_button`, `bgm_arcade`) into `app/src/main/res/raw/`.

---

## 3. AdMob Registration & Production Setup

During development, the project uses official Google sample/test Ad Unit IDs so you can safely test Banner and Rewarded Ads without violating AdMob policy:
- **Test App ID** (`res/values/strings.xml`): `ca-app-pub-3940256099942544~3347511713`
- **Test Banner Ad Unit ID**: `ca-app-pub-3940256099942544/6300978111`
- **Test Rewarded Ad Unit ID**: `ca-app-pub-3940256099942544/5224354917`

To switch to production AdMob ads before publishing:
1. Sign in to [https://admob.google.com](https://admob.google.com) and register your Android app.
2. Copy your **AdMob App ID** (`ca-app-pub-XXXXXXXXXXXXXXXX~YYYYYYYYYY`) into `app/src/main/res/values/strings.xml` under `admob_app_id`.
3. Create a **Banner** ad unit and a **Rewarded** ad unit in AdMob, and set `ADMOB_BANNER_AD_UNIT_ID` and `ADMOB_REWARDED_AD_UNIT_ID` in your `.env` file (or in AI Studio Secrets).
4. Banner ads automatically hide during active single-player and multiplayer gameplay and never block gameplay if ads fail to load.

---

## 4. Multiplayer Server Deployment & Two-Phone Testing

See [`server/README.md`](server/README.md) for the complete 8-step guide covering:
1. Installing Node.js (`>= 18.0.0`).
2. Running `cd server && npm install`.
3. Configuring `server/.env`.
4. Starting the server locally (`npm start`).
5. Deploying to Render, Railway, Fly.io, or an Nginx/TLS VPS.
6. Enabling `wss://` secure WebSocket connections over HTTPS/TLS.
7. Setting the server URL inside the Android app (**⚙ SERVER / ID** button on the Main Menu or via `.env`).
8. Creating and joining a 6-digit room across two physical Android phones.

---

## 5. Building the Android APK

### In Android Studio:
1. Open the root project folder in **Android Studio**.
2. Let Gradle sync dependencies.
3. Select **Build → Build Bundle(s) / APK(s) → Build APK(s)**.
4. Output debug APK path: `app/build/outputs/apk/debug/app-debug.apk`.

### Via Command Line:
```bash
gradle assembleDebug
```
