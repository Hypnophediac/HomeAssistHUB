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
- **`CloudSyncManager`** — 2 percenként batch-eli a Room DB-ből a P1 raw + inverter olvasatokat és POST-olja a Render backendnek. **Két kurzor**: fő cursor (új adatok) és backfill cursor (7 nap gördülő ablak újra-szinkronizálása). A relay `bulkWrite` upsert-tel dolgozik (`{homeId, timestamp}` unique index), így a re-szinkron idempotens — MongoDB/Render adatvesztés esetén automatikusan újra feltöltődik. Offline eset a Room DB szolgál pufferként.
- **`CommandRouter`** — Socket.IO parancskezelő (get_p1_history, get_energy_daily/weekly/monthly/yearly, get_live_snapshot, stb.).
- **`HubApiServer`** — Ktor HTTP szerver (LAN-on), REST API a helyi hálózaton.
- **`HubLogBuffer`** — in-memory ring buffer (200 bejegyzés), ami a szolgáltatás komponenseinek logjait gyűjti a dashboard log viewer számára.
- **`DailyStatsCalculator`** — napi statisztikák számítása (összes fogyasztás, export, self-consumption ratio = (produced - exported) / produced).

### Room DB (v7)

Adatbázis fájl: `homeassist_hub.db` (SQLite, az app private data könyvtárában)

#### Táblák

**`p1_raw_data`** — perces P1 smart meter olvasatok (7 napig őrzi, utána `deleteOlderThan` törli)

| Oszlop | Típus | Leírás |
|---|---|---|
| `id` | Long (PK, autoGen) | Auto-increment primary key |
| `timestamp` | Long | Epoch millis (olvasás ideje) |
| `importT1Kwh` | Double | Kumulatív import T1 tarifa (kWh) |
| `importT2Kwh` | Double | Kumulatív import T2 tarifa (kWh) |
| `exportT1Kwh` | Double | Kumulatív export T1 tarifa (kWh) |
| `exportT2Kwh` | Double | Kumulatív export T2 tarifa (kWh) |
| `importTotalKwh` | Double | Teljes kumulált import (kWh) |
| `exportTotalKwh` | Double | Teljes kumulált export (kWh) |
| `currentPowerW` | Double | Jelenlegi teljesítmény (W) |
| `powerImportW` | Double | Import teljesítmény (W) |
| `powerExportW` | Double | Export teljesítmény (W) |
| `l1V/l2V/l3V` | Double | Fázisonkénti feszültség (V) |
| `l1A/l2A/l3A` | Double | Fázisonkénti áramerősség (A) |
| `powerImportL1W.../powerExportL1W...` | Double | Fázisonkénti import/export (W) |
| `powerFactor` | Double | Teljesítménytényező |
| `frequencyHz` | Double | Hálózati frekvencia (Hz) |
| `currentTariff` | Int | Aktuális tarifa (1 vagy 2) |

**`p1_daily_summary`** — véglegesített napi P1 statisztikák (éjféli rolloverkor írja a `DailyStatsCalculator`)

| Oszlop | Típus | Leírás |
|---|---|---|
| `date` | String (PK) | `yyyy-MM-dd` |
| `totalConsumedKwh` | Double | Napi összes fogyasztás (kWh) |
| `totalExportedKwh` | Double | Napi összes visszatáplálás (kWh) |
| `importT1Kwh/importT2Kwh` | Double | Napi import tarifa 1/2 (kWh) |
| `exportT1Kwh/exportT2Kwh` | Double | Napi export tarifa 1/2 (kWh) |
| `minPowerW/maxPowerW/avgPowerW` | Double | Napi min/max/átlag teljesítmény (W) |
| `maxImportW/maxExportW` | Double | Napi max import/export (W) |
| `peakConsumptionHour/peakExportHour` | Int | Csúcs fogyasztás/export órája (0-23) |
| `peakConsumptionKwh/peakExportKwh` | Double | Csúcs óra kWh értéke |
| `selfConsumptionRatio` | Double | Önfogyasztási arány (0-1) |
| `netEnergyKwh` | Double | Hálózati egyenleg (kWh) |
| `avgL1V.../avgL1A...` | Double | Fázisonkénti átlag feszültség/áram |
| `avgPowerFactor/avgFrequencyHz` | Double | Átlag PF és frekvencia |

**`inverter_history`** — inverter aktív teljesítmény görbe pontok (5 percenkénti scrape)

| Oszlop | Típus | Leírás |
|---|---|---|
| `id` | Long (PK, autoGen) | Auto-increment |
| `timestamp` | Long | Epoch millis |
| `activePowerW` | Double | Inverter aktív teljesítmény (W) |

**`inverter_daily_summary`** — napi napelem yield (éjféli snapshot a Kiosk `dailyEnergy` értékből)

| Oszlop | Típus | Leírás |
|---|---|---|
| `date` | String (PK) | `yyyy-MM-dd` |
| `producedKwh` | Double | Napi termelt energia (kWh) |

#### DAO-k

- **`P1RawDao`** — `insert`, `getRange(startMs, endMs)`, `getLatest`, `getOldest`, `getRangeSince(sinceMs, limit)`, `deleteOlderThan`
- **`P1DailySummaryDao`** — `upsert` (REPLACE), `getRange`, `getAll`, `getByDate`
- **`InverterHistoryDao`** — `insert/insertAll`, `getRange`, `getRecent`, `getRangeSince`, `deleteOlderThan`
- **`InverterDailySummaryDao`** — `upsert` (REPLACE), `getRange`, `getByDate`

#### Adatgyűjtés ütemezése

| Forrás | Gyakoriság | Mit csinál |
|---|---|---|
| `P1MeterController` | 60s | HTTP GET P1 meter → `P1RawData` insert + `P1HistoryBuffer` update |
| `HuaweiCloudScraper` | 300s (5p) | Kiosk API scrape → `InverterHistoryEntity` insert + `InverterLiveData` update |
| `DailyStatsCalculator` | Éjféli | `P1RawData` napi aggregáció → `P1DailySummary` upsert + `InverterDailySummary` upsert |
| `CloudSyncManager` | 120s (2p) | Fő cursor: új adatok batch POST → Render. Backfill cursor: 7 nap gördülő újra-szinkron |

#### Retention

- `p1_raw_data`: 7 nap (Hub oldalon, `deleteOlderThan` hívás)
- `inverter_history`: nincs explicit törlés (folyamatosan nő, de kis méret)
- `p1_daily_summary` / `inverter_daily_summary`: határozatlan ideig (éjféli upsert)

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

### Elszámolási nyitóértékek (MVM Baseline)

A P1 mérőóra kumulált állásokat ad vissza (beüzemelés óta), így az éves fogyasztás/visszatáplálás számításához ismerni kell az utolsó hivatalos MVM leolvasás napján mért óraállásokat.

**Képlet:**
```
Idei vételezés (Import) = current_import_total - baseline_import
Idei visszatáplálás (Export) = current_export_total - baseline_export
Éves egyenleg (kWh) = Idei vételezés - Idei visszatáplálás
```

**Tárolás:** `HubConfigStore` SharedPreferences — `baseline_import_kwh`, `baseline_export_kwh`, `baseline_date`

**Beállítás helye:**
- **Hub**: Dashboard → Beállítások → "Elszámolási nyitóértékek (MVM)" section
- **Kliens**: Beállítások fül → "Elszámolási nyitóértékek (MVM)" kártya (Socket.IO `save_baseline` / `get_baseline` parancsok)

**Megjelenítés:** Energia fül → Éves tab → BaselineCard (idei vételezés, visszatáplálás, éves egyenleg, jelenlegi óraállások)

**Adatforrás a `get_p1_history` válaszban:** `baseline` objektum, amely tartalmazza:
- `baselineImportKwh`, `baselineExportKwh`, `baselineDate` — a mentett nyitóértékek
- `currentImportTotalKwh`, `currentExportTotalKwh` — a P1 mérő jelenlegi kumulált állásai (`P1HistoryBuffer.latestSnapshot`)
- `yearlyImportKwh`, `yearlyExportKwh`, `yearlyBalanceKwh` — számolt éves delták

### Közvetlen elérés — böngészőből ellenőrzés

#### P1 Smart Meter (LAN-on)

A P1 meter egy egyszerű HTTP JSON API-t szolgál ki. Bármilyen böngészőből elérhető a helyi hálózaton:

```
http://192.168.0.148:8989/json
```

- **IP/port** a Hub `SecureCredentialStore`-ban van tárolva (`deviceId = "p1_meter"`)
- **Alapértelmezett**: `192.168.0.148:8989` (auto-provisioning ha nincs beállítva)
- **Válasz**: JSON — `powerW`, `powerImportW`, `powerExportW`, `l1V/l2V/l3V`, `l1A/l2A/l3A`, `powerImportL1W.../powerExportL1W...`, `powerFactor`, `frequencyHz`, `importT1Kwh/importT2Kwh`, `exportT1Kwh/exportT2Kwh`, `currentTariff`
- **Frissítés**: percenként (P1MeterController 60s poll)

#### Huawei FusionSolar Kiosk (publikus URL)

A Kiosk URL a Hub `HubConfigStore`-ban van tárolva (`kioskUrl` mező). Két formátumban lehet megadni:

**1. Kiosk portál oldal (böngészőben vizuálisan):**
```
https://uni002eu5.fusionsolar.huawei.com/pvmswebsite/nologin/assets/build/cloud.html#/kiosk?kk=n0uvBccyuPlyodtk9c46sHolzdwJDjrJ
```
- Ezt megnyitva böngészőben megjelenik a FusionSolar kiosk dashboard (real-time power, daily energy, power curve grafikon)

**2. REST API endpoint (JSON válasz, böngészőből vagy curl-lel):**
```
https://uni002eu5.fusionsolar.huawei.com/rest/pvms/web/kiosk/v1/station-kiosk-file?kk=n0uvBccyuPlyodtk9c46sHolzdwJDjrJ
```
- **Válasz**: HTML-escaped JSON — `realKpi.realTimePower` (kW), `realKpi.dailyEnergy` (kWh), `realKpi.cumulativeEnergy`, `powerCurve.activePower[]` (kW/időpont)
- **Frissítés**: ~5 percenként (HuaweiCloudScraper 300s poll)
- **Domain**: `uni002eu5.fusionsolar.huawei.com` (alapértelmezett, ha csak kk token van megadva)
- **kk token**: a kiosk URL-ből kivonva (`kk=...` paraméter)

> **Megjegyzés**: A fenti URL-ek és tokenek példák. A valós kk token a saját FusionSolar fiókból / kiosk linkből származik. A Hub Beállítások → Kiosk URL mezőben van konfigurálva, és a `get_kiosk_url` Socket.IO paranccsal is lekérdezhető.

## Client (`client/`)

Android app, ami két adatforrást használ:

- **Socket.IO (Hub)** — élő Dashboard: P1 adatok, inverter adatok, eszközvezérlés (konnektor be/ki), kamera
- **REST API (Render/MongoDB)** — Energia fül: historikus napi/heti/havi/éves/egyedi dátumtartomány statisztikák
- **Open-Meteo API** — időjárás-alapú napelem termelés előrejelzés (külső API, kulcs nélkül)

### Adatforrások részletesen

| Adatforrás | Protokoll | Mikor | Mit ad |
|---|---|---|---|
| Hub (Socket.IO) | `command_request`/`command_response` a relé-n keresztül | 2mp-es polling | Élő P1 olvasatok, inverter adatok, eszközvezérlés, kamera snapshot |
| Render REST API | HTTP GET `Bearer <syncToken>` | Kézi frissítés / tab váltás | Napi/heti/havi/éves/egyedi energiestatisztikák (MongoDB-ből) |
| Open-Meteo | HTTP GET (direkt) | Kézi frissítés | Időjárás + shortwave_radiation → PV termelés becslés |

### Képernyők és widgetek — adatforrás és számítás

#### 1. Dashboard fül (`DashboardScreen.kt`)

**Adatforrás: Socket.IO → Hub `get_p1_history` parancs (2mp polling, 30mp-ként full 24ó history)**

| Widget | Megjelenített adat | Forrás | Számítás |
|---|---|---|---|
| **P1PowerCard — StatChips** | Napelem (W) | `P1ReadingDto.inverterPowerW` | Hub `InverterLiveData.activePowerW` (Kiosk scrape) |
| | Ház Fogy. (W) | `P1ReadingDto.realConsumptionW` | Hub által számolt T-5 szinkronizált érték: `inverterPowerW + (P1importW - P1exportW)` |
| | Vételezés (W) | `P1ReadingDto.powerImportW` | P1 meter `instantaneous_power_import` (összes fázis) |
| | Betáplálás (W) | `P1ReadingDto.powerExportW` | P1 meter `instantaneous_power_export` (összes fázis) |
| **P1PowerCard — Feszültség** | L1/L2/L3 (V) | `P1ReadingDto.l1V/l2V/l3V` | P1 meter `voltage_phase_l1/l2/l3` |
| **P1PowerCard — Áramerősség** | L1/L2/L3 (A) | `P1ReadingDto.l1A/l2A/l3A` | P1 meter `current_phase_l1/l2/l3` |
| **PhasePowerChip** (L1/L2/L3) | Import/Export per fázis (W) | `P1ReadingDto.powerImportL1W.../powerExportL1W...` | Hub számolt: ha meter per-fázis érték >0, azt használja. Egyébként `V×Bl×PF` (balanced current × power factor). Irány az összesített import/export alapján: ha `importW=0` → minden fázis exportál; ha `exportW=0` → minden importál; ha mindkettő >0 → **brute-force 2^3=8 assignment**: minden fázis VAGY import VAGY export (nem mindkettő egyszerre), kiválasztva az az elrendezés, ahol a fázis teljesítmények összege legjobban illeszkedik az összesített import/export értékekhez, majd skálázva a pontos összegre |
| **HousePhaseChip** (L1/L2/L3) | Ház fogyasztás per fázis (W) | Számolt | `solarPerPhase + importLxW - exportLxW`, ahol `solarPerPhase = (exportW - importW + houseW) / 3`. `houseW` = Hub T-5 szinkronizált `realConsumptionW`. `solarRealtime = exportW - importW + houseW` (valós idejű P1 + szinkronizált házfogyasztás) |
| **P1HistoryChart** | Teljesítmény görbe (import/export/consumption) | `P1ReadingDto` lista (100-1440 pont) | Consumption = `realConsumptionW` (Hub T-5 szinkronizált). Inverter power = `inverterPowerW` (Hub `findInverterPower` 10 perces tolerance + valós idejű pont tárolása minden scrape-nél, mert a Kiosk powerCurve órákkal lemaradhat). **Catmull-Rom spline** (tension=0.5) sima görbékhez + **gradiens kitöltés** (25%→0% alpha) a vonalak alatt |
| **FreshnessBadge** | Adatfrissesség (zöld/sárga/piros) | `P1ReadingDto.timestamp` | `now - timestamp`: <90s=Élő, <6p=X perce, >6p=Elavult |
| **CloudSyncBadge** | Cloud sync státusz | `cloudSync.lastSyncTime` (Socket.IO válaszban) | `now - lastSyncTime`: <5p=syncél, <15p=Xp, >15p=Xp |
| **DailySummaryCard** | Napi inverter kWh, P1 import/export kWh, ház kWh | `dailySummary` (Socket.IO válaszban) | Hub `InverterLiveData.dailyEnergyKwh` + `P1HistoryBuffer.getDailyKwhDeltas()` |
| **PlugCards** | Smart plug lista + on/off állapot | `list_devices` parancs | Hub `SecureCredentialStore`-ból olvasott eszközök |

#### 2. Energia fül (`EnergyDashboardScreen.kt`)

**Élő adatok: Socket.IO → Hub `get_p1_history` (2mp polling)**
**Historikus adatok: Render REST API → MongoDB**

| Widget | Megjelenített adat | Forrás | Számítás |
|---|---|---|---|
| **LiveFlowCards — Napelem Termelés** | W | `LivePowerData.inverterPowerW` | Hub Kiosk scrape |
| **LiveFlowCards — Ház Fogyasztás** | W | `LivePowerData.houseW` | Hub T-5 szinkronizált `realConsumptionW` |
| **LiveFlowCards — Import** | W | `LivePowerData.importW` | P1 meter |
| **LiveFlowCards — Export** | W | `LivePowerData.exportW` | P1 meter |
| **FreshnessBadge** | Adatfrissesség | `LivePowerData.timestamp` | U.a. mint Dashboard |
| **CloudSyncBadge** | Cloud sync státusz | `cloudSyncLastTime` | U.a. mint Dashboard |
| **ForecastCard — Mára várható** | kWh | Open-Meteo `shortwave_radiation` | `pvCapacityKwp * (radiation / 1000) * performanceRatio` óránként, összegezve |
| **ForecastCard — Eddig termelt** | kWh | `dailySummary.inverterDailyKwh` | Hub Kiosk `dailyEnergy` |
| **ForecastCard — Jelenleg** | °C, felhőzet % | Open-Meteo `temperature_2m`, `cloudcover` | Aktuális óra indexe |
| **SummaryCards (Napi tab)** | Vételezés/visszatáplálás (kWh) | Render `GET /daily` → `EnergyDailyResponseDto` | MongoDB P1RawReading aggregáció |
| **SummaryCards — LiveStatCard** | Vételezés/visszatáplálás (W) | Render `latestPowerImportW/ExportW` | MongoDB legutolsó P1 olvasat |
| **SummaryCards — Feszültség/Áram** | L1/L2/L3 V és A | Render `latestL1V.../latestL1A...` | MongoDB legutolsó P1 olvasat |
| **SummaryCards — Power Factor/Frekvencia** | PF, Hz | Render `latestPowerFactor/latestFrequencyHz` | MongoDB legutolsó P1 olvasat |
| **SummaryCards — Napi statisztika** | Min/Max/Átlag teljesítmény (W) | Render `minPowerW/maxPowerW/avgPowerW` | MongoDB P1RawReading aggregáció |
| **SummaryCards — Max vételezés/visszatáplálás** | W | Render `maxImportW/maxExportW` | MongoDB P1RawReading max |
| **SummaryCards — Csúcs órák** | Óra + kWh | Render `peakConsumptionHour/peakExportHour` | MongoDB órás bontás max |
| **SummaryCards — Önfogyasztási arány** | % | Render `selfConsumptionRatio` | `(produced - exported) / produced` |
| **SummaryCards — Hálózati egyenleg** | kWh | Render `netEnergyKwh` | `totalConsumedKwh - totalExportedKwh` |
| **SummaryCards — Tariff 1/2** | kWh | Render `importT1Kwh/importT2Kwh` | P1 meter `active_import_energy_tariff_1/2` delta |
| **SummaryCards — Export T1/T2** | kWh | Render `exportT1Kwh/exportT2Kwh` | P1 meter `active_export_energy_tariff_1/2` delta |
| **SummaryCards — Fázisonkénti átlag** | V, A, PF, Hz átlag | Render `avgL1V.../avgL1A.../avgPowerFactor/avgFrequencyHz` | MongoDB P1RawReading napi átlag |
| **EnergyColumnChart (Napi)** | Óránkénti peak import/export (kW) | Render `hourly[].peakImportKw/peakExportKw` | MongoDB órás aggregáció, dinamikus Y-tengely (1/2/5/10/20/50... sorozat) |
| **PeriodSummaryCards (Heti/Havi/Éves/Egyedi)** | Vételezés/visszatáplálás/termelés (kWh) | Render `GET /weekly/monthly/yearly/range` | MongoDB napi bontású aggregáció |
| **PeriodSummaryCards — Önfogyasztási arány** | % | Számolt kliens oldalon | `((totalProducedKwh - totalExportedKwh) / totalProducedKwh) * 100` |
| **EnergyColumnChart (Heti)** | Napi fogyasztás/visszatáplálás (kWh) | Render `entries[].consumedKwh/exportedKwh` | Label: `Hé, Ke, Sze, Cs, Pé, Szo, Va` |
| **EnergyColumnChart (Havi/Éves/Egyedi)** | Napi/havi bontású fogyasztás/visszatáplálás | Render `entries[].consumedKwh/exportedKwh` | MongoDB aggregáció, dinamikus Y-tengely |
| **BaselineCard (Éves tab)** | Idei vételezés/visszatáplálás/egyenleg (kWh), jelenlegi óraállások | Socket.IO `get_p1_history` → `baseline` objektum | `currentTotal - baseline` (Hub oldalon számolva) |
| **EnergyDateRangePicker** | Dátumtartomány választó | Material3 `DateRangePicker` | UTC `yyyy-MM-dd` formátum |

#### 3. Kamera fül (`CameraScreen.kt`)

**Adatforrás: Socket.IO → Hub `list_devices` + `get_snapshot` parancsok**

| Widget | Megjelenített adat | Forrás |
|---|---|---|
| **Kamera lista** | `v380_ptz` és `rtsp_camera` típusú eszközök | Hub `list_devices` → `SecureCredentialStore` |
| **PTZ vezérlés** | Fel/le/bal/jobbra parancsok | Socket.IO `sendCommand(deviceId, action)` |
| **Snapshot** | Kamera kép (base64) | Hub `get_snapshot` → ExoPlayer RTSP frame grab |

#### 4. Beállítások fül (`SettingsScreen.kt`)

**Adatforrás: lokális SharedPreferences + Socket.IO → Hub parancsok**

| Widget | Adat | Forrás | Mentés |
|---|---|---|---|
| **ConnectionCard** | Relé URL, Home ID, Hub Local URL, Sync Token | `ClientConfigStore` (SharedPreferences) | Lokális mentés |
| **DiscoveryCard** | Hálózati eszköz felderítés | Hub `discover_devices` parancs | — |
| **AddCredentialCard** | Eszköz ID, típus, IP, port, user, jelszó | Hub `save_credential` parancs | Hub `SecureCredentialStore` |
| **SavedDevicesCard** | Mentett eszközök listája | Hub `list_devices` parancs | Hub `delete_credential` |
| **KioskUrlCard** | Huawei Kiosk URL | Hub `save_kiosk_url` / `get_kiosk_url` | Hub `HubConfigStore` |
| **BaselineSettingsCard** | Nyitó vételezés/visszatáplálás (kWh), leolvasás dátuma | Hub `save_baseline` / `get_baseline` | Hub `HubConfigStore` (SharedPreferences) |
| **PvForecastCard** | GPS lat/long, PV kapacitás (kWp), rendszert hatásfok (%) | `ClientConfigStore` (SharedPreferences) | Lokális mentés |

### Téma

App-wide sötét téma (OLED-barát):
- background = #121212
- surface = #1E293B
- surfaceContainerHighest = #334155
- Akcentusok: zöld #10B981 (termelés), narancs #F59E0B (import), kék #3B82F6 (export), lila #8B5CF6 (ház fogyasztás)

## Relay (`relay/`)

Node.js/TypeScript backend, Render-on fut. Részletes dokumentáció: [`relay/README.md`](relay/README.md).

### Funkciók

- **Socket.IO bróker** — szobák `homeId` alapján, WebRTC jelzés
- **FusionSolar API proxy** — Kiosk + OpenAPI mód
- **MongoDB Atlas** — energiadat tárolás (M0 free tier, 512MB)
- **REST API** — ingest (Hub→Render) + retrieval (Client→Render), Bearer token auth

### MongoDB Atlas

**Kluster:** `homeassisthub.tyd6meo.mongodb.net` (M0 free tier)
**Adatbázis:** `homeassisthub`
**User:** `hypnophediac_db_user`
**Kapcsolat:** `MONGODB_URI` env var (Render Environment-ben beállítva)

#### Kollekciók és sémák

**`P1RawReading`** — perces P1 smart meter olvasatok (14 nap gördülő ablak)

| Mező | Típus | Leírás |
|---|---|---|
| `homeId` | String (index) | Otthon azonosító (pl. `home1`) |
| `timestamp` | Number (index) | Epoch millis |
| `powerImportW` | Number | Import teljesítmény (W) |
| `powerExportW` | Number | Export teljesítmény (W) |
| `importT1Kwh` | Number | Kumulatív import T1 (kWh) |
| `importT2Kwh` | Number | Kumulatív import T2 (kWh) |
| `exportT1Kwh` | Number | Kumulatív export T1 (kWh) |
| `exportT2Kwh` | Number | Kumulatív export T2 (kWh) |
| `currentPowerW` | Number | Jelenlegi teljesítmény (W) |
| `l1V/l2V/l3V` | Number | Fázis feszültségek (V) |
| `l1A/l2A/l3A` | Number | Fázis áramok (A) |
| `powerImportL1W.../powerExportL3W` | Number | Fázisonkénti import/export (W) |
| `powerFactor` | Number | Teljesítménytényező |
| `frequencyHz` | Number | Hálózati frekvencia (Hz) |
| `currentTariff` | Number | Aktuális tarifa (1/2) |

- **Unique index:** `{ homeId: 1, timestamp: 1 }` — duplikáció ellen
- **Retention:** 14 nap (`startRawDataCleanup`: 6 óránként törli a 14 napnál régebbi dokumentumokat)

**`InverterReading`** — inverter teljesítmény olvasatok (14 nap gördülő ablak)

| Mező | Típus | Leírás |
|---|---|---|
| `homeId` | String (index) | Otthon azonosító |
| `timestamp` | Number (index) | Epoch millis |
| `activePowerW` | Number | Inverter aktív teljesítmény (W) |
| `dailyEnergyKwh` | Number | Napi termelt energia (kWh) a Kiosk `dailyEnergy`-ből |

- **Unique index:** `{ homeId: 1, timestamp: 1 }`
- **Retention:** 14 nap (ugyanaz a cleanup)

**`P1DailySummary`** — véglegesített napi P1 statisztikák (határozatlan ideig)

| Mező | Típus | Leírás |
|---|---|---|
| `homeId` | String (index) | Otthon azonosító |
| `date` | String | `yyyy-MM-dd` |
| `totalConsumedKwh/totalExportedKwh` | Number | Napi összes fogyasztás/visszatáplálás |
| `importT1Kwh/importT2Kwh` | Number | Napi import tarifa 1/2 |
| `exportT1Kwh/exportT2Kwh` | Number | Napi export tarifa 1/2 |
| `minPowerW/maxPowerW/avgPowerW` | Number | Napi min/max/átlag teljesítmény |
| `maxImportW/maxExportW` | Number | Napi max import/export |
| `peakConsumptionHour/peakExportHour` | Number | Csúcs órák (0-23) |
| `peakConsumptionKwh/peakExportKwh` | Number | Csúcs óra kWh |
| `selfConsumptionRatio` | Number | Önfogyasztási arány |
| `netEnergyKwh` | Number | Hálózati egyenleg |
| `avgL1V.../avgL1A...` | Number | Fázis átlagok |
| `avgPowerFactor/avgFrequencyHz` | Number | Átlag PF és frekvencia |

- **Unique index:** `{ homeId: 1, date: 1 }`
- **Retention:** határozatlan ideig (napi 1 dokumentum)

**`InverterDailySummary`** — napi napelem yield (határozatlan ideig)

| Mező | Típus | Leírás |
|---|---|---|
| `homeId` | String (index) | Otthon azonosító |
| `date` | String | `yyyy-MM-dd` |
| `producedKwh` | Number | Napi termelt energia (kWh) |

- **Unique index:** `{ homeId: 1, date: 1 }`
- **Retention:** határozatlan ideig

**`HomeToken`** — sync token registry

| Mező | Típus | Leírás |
|---|---|---|
| `homeId` | String (unique) | Otthon azonosító |
| `syncToken` | String | Bearer token |
| `createdAt` | Date | Létrehozás ideje |

### REST API endpointok

#### Ingest (Hub → Render)

**`POST /api/energy/:homeId/ingest`** — Batch P1 + inverter olvasatok feltöltése

- **Auth:** Bearer syncToken
- **Body:** `{ p1Readings: [...], inverterReadings: [...] }`
- **Működés:** `bulkWrite` upsert `{ homeId, timestamp }` filterrel — idempotens, ismételt feltöltés nem duplikál
- **Válasz:** `{ p1Inserted, invInserted }`

**`POST /api/energy/:homeId/daily-summary`** — Napi összegzők feltöltése

- **Auth:** Bearer syncToken
- **Body:** `{ p1Summary: {...}, inverterSummary: { date, producedKwh } }`
- **Működés:** `findOneAndUpdate` upsert `{ homeId, date }` filterrel

#### Retrieval (Client → Render)

**`GET /api/energy/:homeId/daily`** — Napi energiestatisztika

- **Query:** MongoDB `P1RawReading.find({ homeId, timestamp: { $gte: startMs, $lt: endMs } })` a mai napra (Budapest timezone-aware)
- **Számítások:**
  - `computeHourlyBuckets(rawReadings)` — óránként (0-23):
    - `peakImportKw` / `peakExportKw` = max `powerImportW` / `powerExportW` az órában (kW)
    - `consumedKwh` / `exportedKwh` = `(max(importT1+T2) - min(importT1+T2))` az órában (kWh)
    - **Nulla kumulált értékű olvasások szűrése** (debug/adathibák kiszűrése)
  - `computeDailyConsumedExported(rawReadings)` — napi összes:
    - `consumed` = `max(importT1+T2) - min(importT1+T2)` (nulla szűrés után)
    - `exported` = `max(exportT1+T2) - min(exportT1+T2)`
  - Csúcs órák: `peakImportKw` / `peakExportKw` alapján
  - `producedKwh` = `InverterDailySummary.producedKwh` vagy `InverterReading.dailyEnergyKwh` (legutolsó)
- **Időzóna-kezelés:**
  - `dateRangeMillis(dateStr)` — Budapest éjfél UTC millis (DST-aware, `Intl.DateTimeFormat` alapú)
  - `budapestHour(ts)` — Budapest óra (0-23), **éjfél = 0** (nem 24, `Intl.DateTimeFormat` bug fix)
  - Query range: `[startMs, endMs)` — half-open interval

**`GET /api/energy/:homeId/weekly`** — Heti statisztika (utolsó 7 nap)

- **Label:** Magyar napnevek (`Hé, Ke, Sze, Cs, Pé, Szo, Va`)
- **Működés:** Minden napra `P1RawReading` query + `computeDailyConsumedExported` + `InverterDailySummary`

**`GET /api/energy/:homeId/monthly`** — Havi statisztika (aktuális hónap napjai)

- **Label:** Nap sorszáma (`1, 2, 3, ...`)
- **Működés:** Ugyanaz, mint heti, de a hónap elejétől

**`GET /api/energy/:homeId/yearly`** — Éves statisztika (12 hónap)

- **Label:** Hónap neve (`Jan, Feb, Mar, ...`)
- **Működés:** Havi aggregáció `P1RawReading` + `InverterDailySummary` alapján

**`GET /api/energy/:homeId/range?from=&to=`** — Egyedi dátumtartomány

- **Label:** `MM-DD` formátum
- **Működés:** Ugyanaz, mint heti, de egyedi tartományra

### Energia számítási logika

#### Napi fogyasztás/visszatáplálás (kWh)

```
consumedKwh = max(importT1Kwh + importT2Kwh) - min(importT1Kwh + importT2Kwh)
exportedKwh = max(exportT1Kwh + exportT2Kwh) - min(exportT1Kwh + exportT2Kwh)
```

A P1 mérőóra **kumulált állásokat** ad (beüzemelés óta), így a napi fogyasztás a nap első és utolsó olvasásának különbsége. A `min/max` megközelítés (nem `first/last`) azért szükséges, mert a backfill során az olvasások időrendben nem feltétlenül sorrendben érkeznek.

**Nulla szűrés:** A `0` kumulált értékű olvasások (debug/test adatok) szűrése történik a számítás előtt, hogy elkerüljük a torzított eredményeket.

#### Óránkénti peak kW (grafikon tüskék)

```
peakImportKw = max(powerImportW) / 1000   (az óra összes olvasásából)
peakExportKw = max(powerExportW) / 1000
```

Ez a **napi grafikon** oszlopainak magassága — a bojler/napelem tüskéket mutatja, nem az átlagos teljesítményt.

#### Önfogyasztási arány

```
selfConsumptionRatio = (producedKwh - exportedKwh) / producedKwh
```

#### Ház fogyasztás (valós idejű, Hub oldalon)

```
realConsumptionW = inverterPowerW + (P1importW - P1exportW)
```

A Kiosk API ~5 perces késéssel dolgozik, ezért a P1 adatot T-5 perccel korábbi olvasattal kell használni.

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
- **Robust sync** — relay `bulkWrite` upsert + unique index, Hub 7-day backfill cursor (separate from main cursor), prevents permanent data loss on MongoDB/Render cold start. Energy daily chart shows peak kW per hour (max instantaneous power) instead of kWh — boiler spikes visible at correct scale.
- **Hub dashboard** — Scrollozható backend dashboard sötét témával: live P1/inverter/cloud sync státusz, log viewer, HubLogBuffer
- **Bugfixek** — selfConsumptionRatio formula javítás, T-5 szinkronizáció fallback, yearly endpoint Budapest-aware dátumkezelés, service státusz pontos megjelenítése

## Tervek (`plan.md`)

A `plan.md` fájl tartalmazza a részletes fejlesztési tervet (Fázis 0-13). A legtöbb fázis implementálva lett. Lásd a fájlt a teljes történetért.
