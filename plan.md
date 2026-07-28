HMKE Energiakezelő Dashboard Redesign
Teljes körű "Energia" fül újratervezése app-wide sötét témával, élő T-5 szinkronizált flow-kártyákkal, egy egyedi Canvas-alapú pinch-to-zoom/pásztázható/fullscreen grafikonnal, Open-Meteo alapú időjárás-termelés előrejelzéssel, és egyedi dátumtartomány-választóval kiegészített statisztikai kártyákkal.

Döntések (a felhasználóval egyeztetve)
Időjárás/előrejelzés: valós Open-Meteo API integráció (ingyenes, kulcs nélkül), shortwave_radiation alapú fizikai becsléssel (nem csak cloudcover).
Sötét mód: app-wide (teljes kliens, minden fül).
Grafikon technológia: a meglévő egyedi Canvas-alapú EnergyColumnChart bővítése (detectTransformGestures a zoom/pan-hez), nem vezetünk be új chart könyvtárat.
Rendszer hatásfok (performance ratio): állítható a Beállításokban, alapérték 80%.
Adatfrissesség állapotjelző: mindkét fülön megjelenik (Dashboard P1 kártya + Energia élő flow kártyák).
Jelenlegi állapot (feltárva)
client/.../ui/energy/EnergyDashboardScreen.kt + EnergyViewModel.kt: már létezik Napi/Heti/Havi/Éves tab, SummaryCards/PeriodSummaryCards, egyedi Canvas oszlopdiagram.
client/.../MainActivity.kt: HomeAssistTheme jelenleg csak lightColorScheme-et definiál, hardkódolt.
client/.../data/ClientConfigStore.kt: csak relay/hub config, nincs GPS/PV kapacitás mező.
hub/.../bridge/CommandRouter.kt: get_energy_daily/weekly/monthly/yearly parancsok P1 raw adatokból (percenkénti P1RawDao) számolnak; nincs bennük inverter (napelem) termelés a heti/havi/éves nézetekhez, csak a napi nézetben van élő inverter adat máshol (Dashboard fülön).
hub/.../service/HubForegroundService.kt: már van éjféli rollover minta (P1DailySummary + computeAndStoreDailySummary), ezt tudjuk mintaként használni az inverter napi összegzéshez is.
Nincs InverterDailySummary tábla — a heti/havi/éves termelés-statisztikákhoz ez szükséges lenne.
AppDatabase.kt: jelenlegi version = 6, exportSchema = false — új tábla hozzáadása migrációval biztonságosan megoldható (nem érinti a meglévő táblák szerkezetét).
InverterLiveData és P1HistoryBuffer már tartalmaz timestamp-et minden mért értékhez — ez felhasználható az "Adatfrissesség" (mennyi ideje friss az adat) számításához.
Terv fázisokra bontva
Fázis 0 — App-wide Dark Theme (OLED-barát, réteghierarchiával)
MainActivity.kt: HomeAssistTheme átalakítása darkColorScheme-re, szigorúan 3 sötét tónusra korlátozva a felület-hierarchiát, hogy a kártyák keret nélkül is jól elváljanak:
background = #121212 (legmélyebb, OLED-barát alap).
surface/surfaceVariant (kártyák) = #1E293B.
surfaceContainerHighest/kiemelt elemek (pl. aktív tab, kiemelt stat) = #334155.
Szöveg: onBackground/onSurface = #F8FAFC, másodlagos = #94A3B8.
Akcentusok: zöld #10B981 (termelés), narancs/piros #F59E0B/#EF4444 (import), kék #3B82F6 (export), lila/türkiz #8B5CF6/#06B6D4 (ház fogyasztás/akku).
Minden képernyő (DashboardScreen, EnergyDashboardScreen, CameraScreen, SettingsScreen) automatikusan örökli az új témát — célzott ellenőrzés, hogy sehol nincs hardkódolt fehér/világos szín ami rontja a kontrasztot vagy OLED burn-in kockázatot okoz (pl. tartósan világító nagy fehér felületek elkerülése).
Fázis 1 — Helyszín, PV kapacitás & Rendszer hatásfok beállítások (előrejelzéshez szükséges)
ClientConfigStore.kt bővítése: latitude, longitude, pvCapacityKwp, performanceRatio (alapérték 0.80) mezőkkel (SharedPreferences).
SettingsScreen.kt + SettingsViewModel.kt: új szekció "Napelem & Helyszín" — szélesség/hosszúság (kézi beviteli mező, vagy FusedLocationProvider gomb "Jelenlegi helyzet" opcióval), telepített napelem kapacitás (kWp), és "Rendszer hatásfok (%)" csúszka/beviteli mező (alapérték 80%, tipikus tartomány 70-90%).
Fázis 2 — Backend: Egyedi dátumtartomány endpoint
CommandRouter.kt: új get_energy_range parancs, paraméterek startDate/endDate (yyyy-MM-dd), a meglévő heti/havi logika mintájára (P1RawDao.getFirstInRange/getLastInRange napi bontásban), tetszőleges hosszú intervallumra.
Fázis 3 — Backend: Inverter napi termelés historikus tárolása + megbízható rollover
Új InverterDailySummary Entity + DAO (minta: P1DailySummary), mezők: date, producedKwh.
Explicit Room Migration (MIGRATION_6_7) az AppDatabase.kt-ban: CREATE TABLE IF NOT EXISTS inverter_daily_summary (...) — nem fallbackToDestructiveMigration(), mivel a Hub folyamatosan futó szerver, a meglévő P1 historikus adatok nem veszhetnek el frissítéskor.
HubForegroundService.startDailySummaryWorker() bővítése: éjfélkor az InverterLiveData.dailyEnergyKwh utolsó ismert értékét elmentjük az előző napra.
Self-healing backfill (edge-case kezelés): a worker induláskor (és óránként ismétlődően, biztonsági hálóként) végigellenőrzi az elmúlt N napot (pl. 7 nap), és ha egy napra hiányzik az InverterDailySummary bejegyzés:
Megnézi, van-e cache-elt "utolsó ismert dailyEnergyKwh + timestamp" érték az adott napról (ehhez InverterLiveData kap egy kis perzisztens napi cache-t, ami minden sikeres Kiosk scrape-kor frissül, és túléli a service újraindítást — pl. egyszerű SharedPreferences kulcs date -> lastKnownDailyKwh).
Ha van ilyen érték, azt menti el végleges napi összegzésként (jobb egy közelítő érték, mint egy teljesen hiányzó nap).
Ha nincs semmilyen adat az adott napról (pl. a Hub egész nap offline volt), a nap producedKwh = null/hiányzóként jelölve marad, és a UI ezt "Nincs adat" jelöléssel mutatja a statisztikai kártyákon/grafikonon, nem nullaként (hogy ne torzítsa a heti/havi átlagot).
get_energy_daily/weekly/monthly/yearly/range válaszok kiegészítése producedKwh mezővel (napi bontásban + összesítve, null a hiányzó napokra), hogy a stat kártyák "Összes Termelés" és "Önfogyasztási arány" pontosan számolható legyen minden intervallumra.
Fázis 4 — Élő Flow Kártyák (T-5 szinkronizált) + Adatfrissesség állapotjelző
EnergyViewModel.kt: kiegészítés a Dashboard fülön már bevált get_p1_history hívással (vagy egy könnyebb get_live_snapshot paranccsal), hogy megkapja: napelem termelés (W), ház fogyasztás (szinkronizált, T-5), import (W), export (W), valamint a Kiosk scrape és a P1 olvasás timestamp-jeit.
EnergyDashboardScreen.kt: új "Élő Adatok" szekció a tetején, 2×2 rácsban 4 kártyával (Napelem Termelés zöld, Ház Fogyasztás lila/türkiz, Import narancs, Export kék), a blueprint szerinti ikonokkal és színekkel.
Új: "Adatfrissesség" állapotjelző — kis színes pötty + szöveg (pl. "Élő" zöld ha a P1 adat <90s, "Felhő: 3 perce" sárga ha a Kiosk adat 1-6 perc közötti, "Elavult: 12 perce" piros ha bármelyik forrás túl régi):
Mindkét fülön megjelenik: a fő DashboardScreen P1 kártyáján (kis badge a kártya sarkában) és az Energia fül élő flow kártyáin egyaránt.
A logika egy közös, újrafelhasználható composable-be kerül (pl. FreshnessBadge.kt egy közös ui/components csomagban), hogy mindkét képernyő ugyanazt a küszöbérték-logikát használja.
Fázis 5 — Időintervallum választó bővítése (Egyedi/Naptár)
EnergyDashboardScreen.kt: az 4 meglévő tab (Napi/Heti/Havi/Éves) mellé "Egyedi" tab hozzáadása.
Anyagi (Material3) DateRangePicker dialógus (experimental API, a meglévő compose-bom:2024.06.00 már tartalmazza) a tetszőleges intervallum kiválasztásához.
A kiválasztott tartomány elküldése a get_energy_range parancsnak.
Fázis 6 — Interaktív fő grafikon (zoom/pan/fullscreen, korlátokkal)
A meglévő EnergyColumnChart Canvas komponens bővítése:
detectTransformGestures a pinch-to-zoom-hoz, szigorúan korlátozva: scale.coerceIn(minScale = 1.0f, maxScale = 10.0f), hogy a tengelyskálázás ne csússzon el és ne lehessen a grafikont "elveszejteni" túlzoomolással.
Drag-alapú horizontális pásztázás (offset állapot + Modifier.pointerInput), az offset is korlátozva a tartalom szélességéhez (ne lehessen üres területre pásztázni).
Dupla koppintás (detectTapGestures(onDoubleTap = ...)) a zoom/pan azonnali visszaállítására (scale=1.0, offset=0) — mert szétcsippentés után gyalogosan visszahúzni nehézkes lenne.
Egy kis "Fullscreen" ikon-gomb minden grafikon sarkában, ami egy Dialog(usePlatformDefaultWidth = false)-t nyit meg, benne a grafikon nagyobb, szélesebb változatával, ugyanazokkal a gesztus-korlátokkal.
Napi nézet mély zoom → perces adatok (teljesítmény-optimalizált):
Amikor a zoom szint átlép egy küszöböt (pl. scale > 3.0f), az EnergyViewModel egyszer lekéri a nap teljes perces bontású P1 adatát (get_p1_history nagyobb limittel, pl. 1440), és cache-eli egy StateFlow-ban a ViewModel szinten (kulcs: dátum).
A Canvas ezután csak a helyi memóriából rajzol (nincs újabb hálózati kérés minden zoom/pan mozdulatnál) — a zoom/pan gesztus csak a már letöltött adatsoron belüli nézetet módosítja.
Ha a felhasználó másik napra vált, és arra még nincs cache, újra lekérdezzük; a cache pl. az utolsó 3 megnyitott napra korlátozva (egyszerű LRU map a ViewModelben), hogy ne nőjön korlátlanul a memóriahasználat.
Fázis 7 — Időjárás & Termelés Előrejelzés Widget (fizikai alapú, shortwave_radiation)
Új WeatherForecastService (client oldalon, Retrofit/OkHttp direkt hívás): GET https://api.open-meteo.com/v1/forecast?latitude=..&longitude=..&hourly=shortwave_radiation,temperature_2m,cloudcover&daily=sunrise,sunset&timezone=auto.
Fizikai alapú becslési formula (pontosabb, mint az egyszerű cloudcover-modell):
estimatedHourKwh = pvCapacityKwp * (shortwave_radiation / 1000) * performanceRatio, ahol shortwave_radiation a W/m² érték, 1000 W/m² a STC (Standard Test Conditions) referencia, performanceRatio a Beállításokból (alapérték 80%, ld. Fázis 1).
Az órás becsléseket a napkelte–napnyugta (daily.sunrise/sunset) közötti órákra összegezzük a napi várható termeléshez.
cloudcover továbbra is megjelenik a UI-on (időjárás ikonhoz: napos/felhős/esős vizuális jelzés), de a kWh-számítás a sugárzás-alapú fizikai képletet használja.
EnergyDashboardScreen.kt: új kártya a grafikon alatt — aktuális időjárás ikon + hőmérséklet, "Mára várható: X.X kWh" becsléssel a tényleges eddigi termelés mellett (InverterLiveData.dailyEnergyKwh a Hubtól).
Csak akkor jelenik meg, ha a Settings-ben be van állítva a helyszín + PV kapacitás + hatásfok (Fázis 1); egyébként egy diszkrét "Állítsd be a helyszínt a Beállításokban" felhívás látszik helyette.
Fázis 8 — Statisztikai összegző kártyák frissítése
SummaryCards/PeriodSummaryCards kiegészítése az új producedKwh mezővel minden intervallumra: Összes Termelés, Összes Fogyasztás (import), Betáplálás (export), Önfogyasztási arány (%) — konzisztens rács minden tabon (Napi/Heti/Havi/Éves/Egyedi).
Érintett fájlok
client/.../MainActivity.kt (dark theme, réteghierarchia)
client/.../data/ClientConfigStore.kt (GPS + kWp + performanceRatio mezők)
client/.../ui/settings/SettingsScreen.kt, SettingsViewModel.kt (új mezők UI-ja)
client/.../ui/components/FreshnessBadge.kt (új, közös állapotjelző komponens)
client/.../ui/dashboard/DashboardScreen.kt (FreshnessBadge integrálása a P1 kártyára)
client/.../ui/energy/EnergyDashboardScreen.kt, EnergyViewModel.kt (fő átalakítás, zoom-cache, FreshnessBadge)
client/.../network/WeatherForecastService.kt (új fájl, Open-Meteo hívás shortwave_radiation-nel)
client/.../network/model/ApiModels.kt (új DTO-k: range response, producedKwh mezők, nullable hiányzó napokhoz)
hub/.../bridge/CommandRouter.kt (get_energy_range, producedKwh mezők a meglévő parancsokban)
hub/.../data/db/InverterDailySummary.kt, InverterDailySummaryDao.kt (új fájlok)
hub/.../data/db/AppDatabase.kt (version = 7, explicit MIGRATION_6_7)
hub/.../service/HubForegroundService.kt (éjféli rollover + self-healing backfill worker)
hub/.../controller/InverterLiveData.kt (perzisztens napi cache: date -> lastKnownDailyKwh, a backfillhez)
Kockázatok / nyitott kérdések
DB migráció: explicit MIGRATION_6_7 szükséges (nem destruktív), mivel a Hub folyamatosan fut és a meglévő P1 historikus adatok nem veszhetnek el.
Location permission: ha "Jelenlegi helyzet" gombot építünk, Android futásidejű helyhozzáférés-engedély kell (ACCESS_COARSE_LOCATION); kézi lat/long beviteli mező mindenképp lesz alternatívaként.
Fullscreen forgatás: a terv nem forgatja el fizikailag a képernyőt (Activity orientation lock elkerülése végett), hanem egy nagyobb Dialog-ban jeleníti meg a grafikont — ha valódi landscape-re váltás kell, ez külön megbeszélést igényel.
Előrejelzés pontossága: a shortwave_radiation alapú fizikai modell nagyságrenddel pontosabb, mint a cloudcover-only becslés, de továbbra sem NASA/PVGIS szintű (nem veszi figyelembe pl. a panel dőlésszögét/tájolását) — ez tudatos kompromisszum.
Backfill pontossága: ha a Hub egy teljes napra offline volt, a napi termelés véglegesen elveszik (nincs honnan visszaszámolni) — ilyenkor a nap "Nincs adat"-ként jelenik meg, nem hamis nullaként.
Végrehajtási sorrend javaslata
Fázis 0 (dark theme) — gyors, azonnal látványos.
Fázis 4 (élő flow kártyák) — újrahasznosítja a meglévő szinkronizált T-5 logikát.
Fázis 6 (zoom/pan/fullscreen chart) — a legnagyobb UX érték.
Fázis 1 + 7 (helyszín beállítás + időjárás widget) — új külső integráció.
Fázis 2 + 3 + 5 + 8 (egyedi dátumtartomány + inverter historikus adat + stat kártyák) — backend-nehéz rész, de kiegészíti a teljes képet.
Fázis 9-13 (Felhő-Központú Híd / MongoDB sync) — a legnagyobb architekturális változás, önálló munkamenetben ajánlott.
Kiegészítés: Felhő-Központú Híd (Cloud Relay + MongoDB)
Az Energia fül historikus adatai (Napi/Heti/Havi/Éves/Egyedi) mostantól kizárólag a relay szolgáltatásból (MongoDB-vel bővítve) érkeznek, függetlenül attól, hogy a kliens otthoni Wi-Fin vagy mobilneten van — az élő eszközvezérlés és a valós idejű Dashboard adatok változatlanul a meglévő Socket.IO csatornán maradnak.

Fontos felfedezés
A README.md címe "Koyeb Cloud"-ot ír, de a server.ts már tartalmaz Render-specifikus "keep-alive ping" logikát (RENDER_EXTERNAL_URL) — a relay valószínűleg már Render-en fut, a README csak elavult. Nem kell platformot váltani, csak a meglévő relay szolgáltatást bővíteni MongoDB-vel és REST végpontokkal. A README-t frissítjük eközben.
Jelenleg a relay állapot nélküli üzenetbróker — nincs adatbázisa, csak Socket.IO szobákat (homeId alapján) és WebRTC jelzést kezel, plusz egy FusionSolar proxy-t.
Döntések (a felhasználóval egyeztetve)
Adatbázis: MongoDB Atlas (ingyenes M0 cluster, örökre ingyenes, nincs lejárat).
Élő vezérlés/adatok: változatlanul Socket.IO-n (Render/MongoDB csak historikus/aggregált adatokhoz).
Kliens lekérdezés: az Energia fül kizárólag a Render/MongoDB REST végpontjait hívja — a meglévő Socket.IO/LAN Retrofit fallback logika törlésre kerül az EnergyViewModel-ből.
Architektúra áttekintés

Hub (otthoni telefon)                Render (relay/ + MongoDB)              Kliens (bárhol)
─────────────────────                ──────────────────────────             ────────────────
P1MeterController  ──┐                                                      
HuaweiCloudScraper ──┼─► Room DB      POST /api/energy/ingest ◄── batch      
(helyi buffer/cache)  │  (unchanged)  POST /api/energy/daily-summary ◄── éjfél
                       │                       │                            
CloudSyncManager ──────┴──── HTTP POST ────────► MongoDB Atlas               GET /api/energy/daily
(1-5 percenként,                                 (raw + summaries)  ◄──────  GET /api/energy/weekly
retry ha offline)                                                            GET /api/energy/monthly
                                                                              GET /api/energy/yearly
Socket.IO command_request/response (élő vezérlés, VÁLTOZATLAN)               GET /api/energy/range
       Hub ◄──────────────────── relay ────────────────────► Kliens
Fázis 9 — Render backend: MongoDB integráció + új entitások
relay/src/db.ts (új): Mongoose kapcsolat (MONGODB_URI env var), séma-definíciók:
P1RawReading (homeId, timestamp, powerImportW, powerExportW, importTotalKwh, exportTotalKwh, l1V/l2V/l3V, l1A/l2A/l3A, powerFactor, frequencyHz, currentTariff).
InverterReading (homeId, timestamp, activePowerW, dailyEnergyKwh).
P1DailySummaryDoc / InverterDailySummaryDoc (homeId, date, aggregált mezők — tükrözi a Hub Room entitásait).
Adatmegőrzési korlát (Atlas M0 512MB limit miatt): a nyers (P1RawReading/InverterReading) dokumentumok csak egy gördülő ablakban (pl. 14 nap) maradnak — egy setInterval-alapú takarító job törli a régebbieket a relay-ben; a napi összegzések (*DailySummaryDoc) végtelen ideig megmaradnak (ezek kicsik).
relay/src/energyRoutes.ts (új): GET /api/energy/daily|weekly|monthly|yearly|range — a Hub CommandRouter-ben már meglévő számítási logika (óránkénti/napi bontás, statisztikák) portolása Node.js/Mongoose aggregáció formájában.
relay/src/energyIngestRoutes.ts (új): POST /api/energy/ingest (batch nyers adat), POST /api/energy/daily-summary (véglegesített napi összegzés).
Fázis 10 — Biztonság: per-home sync token
Jelenleg a homeId egy felhasználó által megadott, nem feltétlenül véletlenszerű azonosító — ha bárki kitalálja/ellesi, olvashatná más historikus energiaadatait a MongoDB-ből.
Új syncToken mező: a Hub Settings-ben generálunk egy hosszú, véletlenszerű tokent (pl. UUID.randomUUID()), amit a HubConfigStore és a ClientConfigStore is eltárol (a felhasználó átmásolja/beírja a kliens Settings-be, hasonlóan a homeId-hoz).
Minden POST /api/energy/ingest, POST /api/energy/daily-summary és GET /api/energy/* kérés az Authorization: Bearer <syncToken> fejlécet küldi; a relay ellenőrzi, hogy a token egyezik-e az adott homeId-hoz korábban regisztrálttal (első ingest kéréskor "regisztrálja" a token-homeId párost a MongoDB-ben, ezután fix marad).
Fázis 11 — Hub: CloudSyncManager (batch upload + offline-tűrő retry)
Új hub/.../sync/CloudSyncManager.kt: HubForegroundService-ből indított coroutine loop (pl. 2 percenként):
Lekéri a Room DB-ből a legutóbb szinkronizált kurzor (syncedUpToTimestamp, perzisztálva HubConfigStore-ban vagy egy új kis SharedPreferences kulcsban) óta keletkezett P1RawData és InverterHistoryEntity sorokat.
Batch JSON-t POST-ol a /api/energy/ingest végpontra.
Csak sikeres (2xx) válasz esetén lépteti előre a kurzort — ha nincs net/a Render nem válaszol, a kurzor változatlan marad, a helyi Room DB (ami már amúgy is 7 napig megőrzi a nyers adatokat) szolgál pufferként, a következő sikeres próbálkozáskor a teljes elmaradás egyben felmegy.
Az éjféli computeAndStoreDailySummary (P1 + Fázis 3 Inverter) után azonnal (és sikertelenség esetén a következő loop-ciklusokban ismételve, amíg nem sikerül) POST-olja a véglegesített napi összegzést a /api/energy/daily-summary végpontra — ez a legértékesebb, véglegesített adat, extra retry-t érdemel.
Fázis 12 — Kliens: Render-only EnergyViewModel
Új client/.../network/RenderApiService.kt (Retrofit interface): getEnergyDaily/Weekly/Monthly/Yearly/Range(homeId, syncToken, ...).
EnergyViewModel.kt átírása: a jelenlegi Socket.IO (get_energy_* command) + LAN Retrofit fallback logika teljes eltávolítása, helyette kizárólag a RenderApiService hívása — a UI viselkedése (betöltés, hibaüzenet) változatlan marad, csak az adatforrás egyszerűsödik egyetlen útvonalra.
ClientConfigStore.kt: syncToken mező hozzáadása (Fázis 10-hez kapcsolódik).
Fázis 13 — Dashboard fül: marad Socket.IO-n, de "Cloud Sync" állapot hozzáadása
A fő DashboardScreen/DashboardViewModel és az élő vezérlés (konnektor be/ki) nem változik — továbbra is Socket.IO-n megy közvetlenül a Hubhoz.
A Fázis 4-ben tervezett "Adatfrissesség" FreshnessBadge kiegészül egy apró jelzéssel is, hogy a Hub mikor szinkronizált utoljára sikeresen a felhővel (CloudSyncManager utolsó sikeres timestamp-je, amit a Hub a Socket.IO get_p1_history válaszában is visszaküldhet) — hasznos diagnosztikához, ha a felhasználó azon tűnődik, hogy az Energia fül miért nem friss (pl. "Felhő szinkron: 8 perce").
Érintett fájlok (kiegészítés)
relay/src/db.ts, relay/src/models/*.ts (új, Mongoose sémák)
relay/src/energyRoutes.ts, relay/src/energyIngestRoutes.ts (új)
server.ts (új route-ok regisztrálása, Mongo kapcsolat inicializálása induláskor)
package.json (új függőség: mongoose)
README.md (Koyeb → Render javítás, új végpontok dokumentálása)
hub/.../sync/CloudSyncManager.kt (új)
hub/.../data/HubConfigStore.kt (syncToken mező)
hub/.../service/HubForegroundService.kt (CloudSyncManager indítása, daily-summary push hook)
client/.../network/RenderApiService.kt (új Retrofit interface)
client/.../data/ClientConfigStore.kt (syncToken mező)
client/.../ui/energy/EnergyViewModel.kt (Socket.IO/LAN logika eltávolítása, Render-only)
client/.../ui/settings/SettingsScreen.kt (syncToken beviteli mező)
Kockázatok / nyitott kérdések (kiegészítés)
MongoDB Atlas M0 korlátok: 512MB tárhely, max ~500 kapcsolat — otthoni, egy-felhasználós projekt esetén bőven elég, de a nyers adatok gördülő ablakos törlése (14 nap) kötelező a hosszú távú stabilitáshoz.
Kettős karbantartás: az energia-számítási logika (óránkénti/napi bontás, statisztikák) ezután két helyen létezik — a Hub CommandRouter-jében (Kotlin, a Dashboard fül élő nézetéhez) és a relay energyRoutes.ts-ben (TypeScript, az Energia fülhöz). Ez a user explicit kérése (kizárólag Render az Energia fülnek), de karbantartási terhet jelent, ha a számítási logika változik — érdemes lehet egységes tesztadatokkal ellenőrizni, hogy a két implementáció ugyanazt az eredményt adja.
Szinkron késleltetés: a 2 perces batch-ciklus miatt az Energia fülön látott "ma" adatok néhány perccel el fognak maradni a Dashboard fülön látott élő (Socket.IO) adatoktól — ez elfogadható, mivel a blueprint is külön kezeli az élő flow kártyákat (Fázis 4, Socket.IO-n) és a historikus grafikonokat (Render-en).
syncToken elosztás: a felhasználónak kézzel át kell másolnia a Hub Settings-ben generált tokent a Kliens Settings-be (hasonlóan a jelenlegi homeId párosításhoz) — ha van QR-kód alapú párosítás a projektben, érdemes azt bővíteni a tokennel is, hogy ne kelljen kézzel gépelni.
