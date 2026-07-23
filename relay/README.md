# Smart Home Relay (Koyeb Cloud)

Node.js/TypeScript message broker + WebRTC signaling server that bridges the
Android Hub and Android Client apps described in the project blueprint.
Also includes a FusionSolar API proxy for Huawei inverter data.

## Stack
- Express.js (HTTP health checks + FusionSolar API routes)
- Socket.IO (rooms keyed by `homeId`)

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

## Deploy to Koyeb
1. Push this `relay/` directory to a Git repo (or use Koyeb's Docker deploy).
2. Create a new Koyeb service pointing at the `Dockerfile` in this directory.
3. Set the `PORT` env var if Koyeb requires a specific port (defaults to 3000;
   Koyeb auto-injects `PORT`, which the server already reads).
4. Set FusionSolar env vars (see above) if you want inverter data integration.
5. Deploy. Health check endpoint: `GET /health`.

```bash
docker build -t smarthome-relay .
docker run -p 3000:3000 smarthome-relay
```
