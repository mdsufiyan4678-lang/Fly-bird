# Fly Bird — Standalone Node.js Multiplayer & Leaderboard Server

This folder contains the authoritative Node.js WebSocket (`ws`) + Express REST server and SQLite database (`better-sqlite3`) for **Fly Bird — Online Multiplayer Edition**.

> **Important:** Online two-player rooms and the global online leaderboard require this server to be deployed and reachable by both Android phones. No Firebase services are used anywhere.

---

## 1. Server Architecture & Responsibilities

- **`src/index.js`**: HTTP/WebSocket entry point (`/health`, `GET /api/leaderboard`, `POST /api/leaderboard`, and WebSocket upgrade handler).
- **`src/roomManager.js`**:
  - Generates unique 6-digit room codes (`100000`–`999999`).
  - Enforces a strict maximum of **2 players per room**.
  - Prevents joining rooms where a match is already running (`MATCH_ALREADY_RUNNING`) or rooms at capacity (`ROOM_FULL`).
  - Synchronizes match starts with a shared PRNG obstacle seed (`seed`) so both players fly through identical pipe & coin layouts.
  - Relays live scores, vertical bird coordinates (`normalizedY`), and `alive`/`eliminated` states.
  - Handles transient connection drops with a configurable 30-second reconnect grace period (`RECONNECT_ROOM`) and cleans up abandoned rooms automatically.
- **`src/scoreManager.js`**:
  - Performs server-side anti-cheat validation by checking submitted scores and coins against elapsed server match time (`MAX_PIPES_PER_SECOND`).
  - Computes the authoritative match winner (`MATCH_OVER`) and awards validated coin bonuses.
- **`src/database.js` & `schema.sql`**:
  - Persists validated high scores (`leaderboard_scores`) and 2-player match history (`multiplayer_matches`) in SQLite (`./data/flybird.sqlite`).

---

## 2. Step-by-Step Deployment Guide

### Step 1: Install Node.js
Install **Node.js 18 LTS or newer** (Node 20 LTS recommended) from [https://nodejs.org](https://nodejs.org):
```bash
node -v
npm -v
```

### Step 2: Install Server Dependencies
Navigate into the `server/` directory and install dependencies:
```bash
cd server
npm install
```

### Step 3: Configure Environment Variables
Copy `.env.example` to `.env` and adjust values as needed:
```bash
cp .env.example .env
```
Variables in `.env`:
- `PORT=8080`
- `DB_PATH=./data/flybird.sqlite`
- `ROOM_IDLE_TIMEOUT_MS=300000`
- `RECONNECT_GRACE_MS=30000`
- `MAX_PIPES_PER_SECOND=1.15`

### Step 4: Start the Multiplayer Server Locally
```bash
npm start
```
Verify the server is running by visiting:
```bash
curl http://localhost:8080/health
```

### Step 5: Deploy to a Public Hosting Provider (Render, Railway, Fly.io, or VPS)
- **Render / Railway / Fly.io**:
  1. Push the `server/` directory to a GitHub repository.
  2. Create a new **Web Service** pointing to the `server` root directory.
  3. Set **Build Command** to `npm install` and **Start Command** to `npm start`.
  4. Attach a persistent disk mounted at `/app/data` (or `./data`) so `flybird.sqlite` persists across restarts.

### Step 6: Enable Secure WSS Connections Using HTTPS/TLS
- Managed PaaS providers (Render, Railway, Fly.io, Cloudflare Tunnels) automatically terminate TLS/HTTPS on port 443 and forward WebSocket upgrades (`wss://your-app-name.onrender.com`).
- If deploying on a Linux VPS (Ubuntu/Debian) with Nginx + Let's Encrypt (`certbot`), configure Nginx to proxy WebSocket upgrades:
```nginx
server {
    listen 443 ssl;
    server_name flybird.yourdomain.com;

    ssl_certificate /etc/letsencrypt/live/flybird.yourdomain.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/flybird.yourdomain.com/privkey.pem;

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        proxy_read_timeout 86400;
    }
}
```

### Step 7: Configure the Android Application's Server URL
You can set the server URL in two ways:
1. **In-App Configuration (No Rebuild Needed)**:
   - Open **Fly Bird** on both Android phones.
   - Tap **⚙ SERVER / ID** on the Main Menu (or **✎ CONFIGURE** in the 2-Player Arena).
   - Enter your deployed WebSocket URL (e.g., `wss://flybird.yourdomain.com` or `ws://192.168.1.100:8080` for local Wi-Fi testing) and tap **SAVE**.
2. **Build-Time Configuration (`.env`)**:
   - Copy `/.env.example` to `/.env` in the Android project root and set:
     ```ini
     MULTIPLAYER_WS_URL=wss://flybird.yourdomain.com
     MULTIPLAYER_HTTP_URL=https://flybird.yourdomain.com
     ```

### Step 8: Test Room Creation and Joining on Two Android Phones
1. Connect **Phone A** and **Phone B** to the internet (and ensure both have the deployed `wss://` server URL configured).
2. On **Phone A**, tap **⚡ ONLINE MULTIPLAYER (2P)** → **➕ CREATE ROOM**. A unique 6-digit code (e.g., `482910`) will appear on the Waiting for Opponent screen.
3. On **Phone B**, tap **⚡ ONLINE MULTIPLAYER (2P)** → **🔑 JOIN ROOM** and enter the 6-digit code `482910`.
4. Both phones will display each other's Pilot Name and `ONLINE` connection status.
5. Tap **⚡ TAP TO MARK READY & START** on both phones. The server will broadcast `MATCH_START` with a shared obstacle seed, synchronize live scores and `ALIVE`/`ELIMINATED` statuses, and announce the validated winner on `MATCH_OVER`.
