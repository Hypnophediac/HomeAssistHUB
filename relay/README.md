# Smart Home Relay (Render Cloud)

Node.js/TypeScript message broker + WebRTC signaling server that bridges the
Android Hub and Android Client apps. Also includes:
- **FusionSolar API proxy** for Huawei inverter data (Kiosk + OpenAPI modes)
- **MongoDB Atlas** integration for historical energy data storage
- **REST API** for energy data ingestion (Hub → Render) and retrieval (Client → Render)

## Stack
- Express.js (HTTP REST API + health checks)
- Socket.IO (rooms keyed by `homeId`)
- MongoDB Atlas (M0 free tier — historical energy data, 14-day rolling window for raw readings, indefinite for daily summaries)
- Mongoose 8.x (ODM)

## Socket.IO Events

| Event | Direction | Payload |
|---|---|---|
| `register` | Hub/Client -> Relay | `{ homeId, role: "hub" \| "client" }` |
| `registered` | Relay -> sender | `{ homeId, role }` |
| `peer_joined` / `peer_left` | Relay -> room | `{ homeId, role, socketId }` |
| `command_request` | Client -> Hub | `{ homeId, requestId, deviceId, action, params? }` |
| `command_response` | Hub -> Client | `{ homeId, requestId, success, data?, error? }` |
| `webrtc_offer` | Hub/Client -> peer | `{ homeId, sdp, from }` |
| `webrtc_answer` | Hub/Client -> peer | `{ homeId, sdp, from }` |
| `ice_candidate` | Hub/Client -> peer | `{ homeId, candidate, from }` |

## FusionSolar API Endpoints

| Endpoint | Method | Description |
|---|---|---|
| `/api/fusionsolar/status` | GET | Check if OpenAPI credentials are configured |
| `/api/fusionsolar/real` | POST | Fetch real-time inverter active power (OpenAPI) |
| `/api/fusionsolar/backfill` | POST | Fetch historical inverter data for a date range (OpenAPI) |
| `/api/fusionsolar/kiosk` | POST | Fetch real-time + today's power curve (Kiosk mode, no credentials) |

### OpenAPI Mode (credentials required)

Set these environment variables on the relay:

```
FUSIONSOLAR_USERNAME=your_username
FUSIONSOLAR_SYSTEM_CODE=your_system_code
FUSIONSOLAR_API_URL=https://eu5.fusionsolar.huawei.com   # optional, this is default
```

### Kiosk Mode (no credentials, just a kiosk ID)

1. Login to FusionSolar portal
2. Select your plant → Monitoring → Kiosk button
3. Copy the URL — the `kk=` parameter is the kiosk ID

Set this environment variable:

```
FUSIONSOLAR_KIOSK_KK=your_kiosk_id
FUSIONSOLAR_KIOSK_URL=https://region01eu5.fusionsolar.huawei.com/rest/pvms/web/kiosk/v1/station-kiosk-file?kk=   # optional
```

### Backfill Usage

```bash
curl -X POST https://relay-url/api/fusionsolar/backfill \
  -H "Content-Type: application/json" \
  -d '{"startDate":"2024-07-01","endDate":"2024-07-23"}'
```

Returns: `{ points: [{ timestamp, activePowerW }], daysFetched, stationCode, inverterCount }`

Send these points to the Hub via `inverter_backfill` command to store in the database.

## Energy Data API (MongoDB-backed)

All energy endpoints require `Authorization: Bearer <syncToken>` header.
The sync token is registered on first ingest request and must match on all subsequent requests.

### Ingest (Hub → Render)

| Endpoint | Method | Description |
|---|---|---|
| `/api/energy/:homeId/ingest` | POST | Batch upload P1 raw + inverter readings |
| `/api/energy/:homeId/daily-summary` | POST | Push finalized daily P1 + inverter summary |

### Retrieval (Client → Render)

| Endpoint | Method | Description |
|---|---|---|
| `/api/energy/:homeId/daily` | GET | Today's hourly breakdown + live stats |
| `/api/energy/:homeId/weekly` | GET | Last 7 days daily breakdown |
| `/api/energy/:homeId/monthly` | GET | Current month daily breakdown |
| `/api/energy/:homeId/yearly` | GET | Current year monthly breakdown |
| `/api/energy/:homeId/range` | GET | Custom date range (`?startDate=yyyy-MM-dd&endDate=yyyy-MM-dd`) |

### Data Flow

```
Hub (home, 2-min interval)
  CloudSyncManager → POST /api/energy/:homeId/ingest (batch raw readings)
  Midnight worker  → POST /api/energy/:homeId/daily-summary (finalized summaries)

Client (anywhere)
  EnergyViewModel → GET /api/energy/:homeId/{daily,weekly,monthly,yearly,range}
  Live data        → Socket.IO get_p1_history (direct to Hub, NOT via Render)
```

### MongoDB Schemas

- **P1RawReading** — per-minute P1 meter data (14-day rolling window, auto-cleaned)
- **InverterReading** — inverter power snapshots (14-day rolling window)
- **P1DailySummary** — finalized daily P1 statistics (kept indefinitely)
- **InverterDailySummary** — daily inverter yield (kept indefinitely)
- **HomeToken** — sync token registry per homeId

## Environment Variables

| Variable | Required | Description |
|---|---|---|
| `MONGODB_URI` | Yes | MongoDB Atlas connection string |
| `PORT` | No | Server port (default 3000, auto-injected by Render) |
| `RENDER_EXTERNAL_URL` | No | Used by keep-alive ping (auto-injected by Render) |
| `KEEP_ALIVE_DISABLED` | No | Set to `1` to disable keep-alive ping |
| `FUSIONSOLAR_KIOSK_KK` | No | Kiosk ID for Kiosk mode |
| `FUSIONSOLAR_USERNAME` | No | OpenAPI username |
| `FUSIONSOLAR_SYSTEM_CODE` | No | OpenAPI system code |

## Local Development
```bash
npm install
npm run dev      # ts-node-dev, auto-reload
```

## Build & Run
```bash
npm install
npm run build
npm start
```

## Deploy to Render
1. Push the `relay/` directory to GitHub (Render auto-deploys from `main`).
2. Create a Render Web Service using the `Dockerfile` in this directory.
3. Set `MONGODB_URI` in Render Environment.
4. Set FusionSolar env vars if using inverter integration.
5. Health check: `GET /health`.

Render auto-injects `PORT` and `RENDER_EXTERNAL_URL`. The built-in keep-alive
pinger hits `/health` every 10 minutes to prevent free-tier spin-down.

```bash
docker build -t smarthome-relay .
docker run -p 3000:3000 -e MONGODB_URI=mongodb://... smarthome-relay
```
