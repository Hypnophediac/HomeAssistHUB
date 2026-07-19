# Smart Home Relay (Koyeb Cloud)

Node.js/TypeScript message broker + WebRTC signaling server that bridges the
Android Hub and Android Client apps described in the project blueprint.

## Stack
- Express.js (HTTP health checks)
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
4. Deploy. Health check endpoint: `GET /health`.

```bash
docker build -t smarthome-relay .
docker run -p 3000:3000 smarthome-relay
```
