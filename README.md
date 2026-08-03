# HomeAssistHUB

Saját fejlesztésű okosotthon-energiakezelő rendszer. Három komponensből áll:

- **Hub** — Android foreground service (otthoni telefon), ami P1 smart meterből és Huawei napelem inverterből gyűjti az adatokat, helyi Room DB-ben tárolja, és szinkronizál a felhővel.
- **Client** — Android app (bármilyen hálózaton), ami Socket.IO-n keresztül élő adatokat kér a Hubtól, és REST API-n keresztül historikus adatokat a Render/MongoDB backendből.
- **Relay** — Node.js/TypeScript backend (Render-on fut), ami Socket.IO brókerként, FusionSolar API proxyként és MongoDB-alapú energiadat-tárolóként működik.

## Architektúra

```
Hub (otthoni telefon)              Render (relay + MongoDB)           Kliens (bárhol)
─────────────────────              ──────────────────────────         ────────────────
P1MeterController ──┐                                                  
HuaweiCloudScraper──┼─► Room DB     POST /api/energy/ingest ◄── batch  
(kiosk scrape, T-5)  │  (7 nap raw)  POST /api/energy/daily-summary    
                     │                      │                          
CloudSyncManager ────┴── HTTP POST ────────► MongoDB Atlas             GET /api/energy/daily
(2 perc, retry)                              (raw 14 nap, summary ∞)   GET /api/energy/weekly
                                                                        GET /api/energy/monthly
Socket.IO command_request/response (élő vezérlés, Dashboard)           GET /api/energy/yearly
   Hub ◄──────────── relay ────────────────────────► Kliens             GET /api/energy/range
```

## Hub (`hub/`)

Android foreground service, ami folyamatosan fut az otthoni telefonon.

### Fő komponensek

- **`HubForegroundService`** — foreground service, ami elindítja a P1 pollert, Kiosk scrapert, Socket.IO klienst, CloudSyncManagert és az éjféli napi összegző workert.
- **`P1MeterController`** — percenként lekérdezi a P1 smart metert (HTTP JSON), feldolgozza a 3-fázisú adatokat (import/export per fázis, feszültség, áram, teljesítménytényező).
- **`HuaweiCloudScraper`** — ~5 percenként scrape-eli a Huawei FusionSolar Kiosk API-t (napelem termelés, napi yield kWh). A P1 adatot T-5 perccel korábbi olvasattal szinkronizálja a ház fogyasztás pontos számításához.
- **`P1HistoryBuffer`** — in-memory ring buffer (10 perc), ami a P1 olvasatokat tárolja a T-5 perces szinkronizációhoz. Éjféli kWh baseline-t is követ a napi delta számításhoz.
- **`InverterLiveData`** — singleton, ami a legfrissebb inverter adatokat tartja (activePowerW, realConsumptionW, dailyEnergyKwh).
- **`CloudSyncManager`** — 2 percenként batch-eli a Room DB-ből a P1 raw + inverter olvasatokat és POST-olja a Render backendnek. Sync kurzort használ (csak sikeres válasz esetén léptet előre). Offline eset a Room DB szolgál pufferként.
- **`CommandRouter`** — Socket.IO parancskezelő (get_p1_history, get_energy_daily/weekly/monthly/yearly, get_live_snapshot, stb.).
- **`HubApiServer`** — Ktor HTTP szerver (LAN-on), REST API a helyi hálózaton.
- **`HubLogBuffer`** — in-memory ring buffer (200 bejegyzés), ami a szolgáltatás komponenseinek logjait gyűjti a dashboard log viewer számára.
- **`DailyStatsCalculator`** — napi statisztikák számítása (összes fogyasztás, export, self-consumption ratio = (produced - exported) / produced).

### Room DB (v7)

- **P1RawData** — perces P1 olvasatok (7 napig)
- **P1DailySummary** — véglegesített napi P1 statisztikák (éjféli rollover)
- **InverterHistoryEntity** — inverter teljesítmény görbe pontok
- **InverterDailySummary** — napi napelem yield (producedKwh)
- Explicit migrációk (MIGRATION_6_7), nem destruktív

### Hub UI — Dashboard

A Hub egy scrollozható dashboard-ot mutat (sötét témával, Material3 `darkColorScheme`):

- **Header** — óra + szolgáltatás státusz badge (zöld=Fut / piros=Leállítva)
- **Szolgáltatás** — Start/Stop/Restart gombok
- **P1 Smart Meter** — élő import/export (W), napi import/export (kWh)
- **Huawei Inverter** — élő termelés (W), napi yield (kWh), ház fogyasztás (W)
- **Cloud Sync** — utolsó sync ideje, cursor, OK/Késés indicator
- **Konfiguráció** — összecsukható: relé URL, home ID, kiosk URL, P1 IP/port, sync token
- **Log viewer** — monospace, színkódolt (INFO/WARN/ERROR/DEBUG), utolsó 50 bejegyzés

### Ház fogyasztás számítás

```
realConsumptionW = inverterPowerW + (P1importW - P1exportW)
```

A Kiosk API ~5 perces késéssel dolgozik, ezért a P1 adatot T-5 perccel korábbi olvasattal kell használni. Ha a T-5min adat nem elérhető (pl. szolgáltatás újraindítás után), fallback a legfrissebb P1 olvasatra.

## Client (`client/`)

Android app, ami két adatforrást használ:

- **Socket.IO (Hub)** — élő Dashboard: P1 adatok, inverter adatok, eszközvezérlés (konnektor be/ki), kamera
- **REST API (Render)** — Energia fül: historikus napi/heti/havi/éves/egyedi dátumtartomány statisztikák

### Fő képernyők

- **Dashboard** — élő flow kártyák (napelem termelés, ház fogyasztás, import, export), 3-fázisú P1 adatok, interaktív grafikon (pinch-to-zoom, pan), per-fázis chippek
- **Energia** — Napi/Heti/Havi/Éves/Egyedi tabok, statisztikai kártyák (termelés, fogyasztás, export, önfogyasztási arány), oszlopdiagram
- **Kamera** — ONVIF RTSP kamera snapshot
- **Beállítások** — relé URL, home ID, sync token, GPS/PV kapacitás/rendszert hatásfok, sötét téma

### Téma

App-wide sötét téma (OLED-barát):
- background = #121212
- surface = #1E293B
- surfaceContainerHighest = #334155
- Akcentusok: zöld (termelés), narancs (import), kék (export), türkiz (ház fogyasztás)

## Relay (`relay/`)

Node.js/TypeScript backend, Render-on fut. Részletes dokumentáció: [`relay/README.md`](relay/README.md).

### Funkciók

- **Socket.IO bróker** — szobák `homeId` alapján, WebRTC jelzés
- **FusionSolar API proxy** — Kiosk + OpenAPI mód
- **MongoDB Atlas** — energiadat tárolás (M0 free tier, 512MB)
  - P1RawReading — 14 nap gördülő ablak
  - InverterReading — 14 nap gördülő ablak
  - P1DailySummary / InverterDailySummary — határozatlan ideig
  - HomeToken — sync token registry
- **REST API** — ingest (Hub→Render) + retrieval (Client→Render), Bearer token auth

## Eszközök

- Hub: `f49a03807d74` (otthoni telefon, Android)
- Client: `QW4HJ7EA6LGMAU65` (bármilyen eszköz)

## Build & Deploy

### Hub
```bash
cd hub && gradle assembleDebug
adb -s f49a03807d74 install -r -t app/build/outputs/apk/debug/app-debug.apk
```

### Client
```bash
cd client && gradle assembleDebug
adb install -r -t app/build/outputs/apk/debug/app-debug.apk
```

### Relay
```bash
cd relay && npm install && npm run build
# Render auto-deploys from GitHub main branch
```

## Fejlesztési történet

- **Fázis 0-8** — Kliens UI redesign: sötét téma, élő flow kártyák, pinch-to-zoom grafikon, Open-Meteo időjárás előrejelzés, egyedi dátumtartomány, InverterDailySummary tábla, statisztikai kártyák
- **Fázis 9-13** — Felhő híd: MongoDB integráció, CloudSyncManager, sync token auth, Render-only EnergyViewModel
- **Hub dashboard** — Scrollozható backend dashboard sötét témával: live P1/inverter/cloud sync státusz, log viewer, HubLogBuffer
- **Bugfixek** — selfConsumptionRatio formula javítás, T-5 szinkronizáció fallback, yearly endpoint Budapest-aware dátumkezelés, service státusz pontos megjelenítése

## Tervek (`plan.md`)

A `plan.md` fájl tartalmazza a részletes fejlesztési tervet (Fázis 0-13). A legtöbb fázis implementálva lett. Lásd a fájlt a teljes történetért.
