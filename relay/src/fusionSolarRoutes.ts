/**
 * Express routes for FusionSolar inverter data integration.
 *
 * Provides:
 *   GET  /api/fusionsolar/status   — check if FusionSolar credentials are configured
 *   POST /api/fusionsolar/real     — fetch real-time inverter active power (OpenAPI)
 *   POST /api/fusionsolar/backfill — fetch historical inverter data for a date range (OpenAPI)
 *   POST /api/fusionsolar/kiosk    — fetch real-time + today's power curve (Kiosk mode)
 *
 * OpenAPI credentials (env vars):
 *   FUSIONSOLAR_API_URL       (default: https://eu5.fusionsolar.huawei.com)
 *   FUSIONSOLAR_USERNAME
 *   FUSIONSOLAR_SYSTEM_CODE
 *
 * Kiosk mode (env vars):
 *   FUSIONSOLAR_KIOSK_KK      — kiosk ID from the FusionSolar kiosk URL
 *   FUSIONSOLAR_KIOSK_URL     (default: https://region01eu5.fusionsolar.huawei.com/rest/pvms/web/kiosk/v1/station-kiosk-file?kk=)
 */

import { Router, Request, Response } from "express";
import { FusionSolarApi, FusionSolarKiosk, InverterHistoryPoint } from "./fusionSolarApi";

const router = Router();

function getApi(): FusionSolarApi | null {
  const username = process.env.FUSIONSOLAR_USERNAME;
  const systemCode = process.env.FUSIONSOLAR_SYSTEM_CODE;
  if (!username || !systemCode) {
    return null;
  }
  return new FusionSolarApi({
    baseUrl: process.env.FUSIONSOLAR_API_URL || "https://eu5.fusionsolar.huawei.com",
    username,
    systemCode,
  });
}

/**
 * GET /api/fusionsolar/status
 * Returns whether FusionSolar API credentials are configured.
 */
router.get("/status", (_req: Request, res: Response) => {
  const configured = !!(process.env.FUSIONSOLAR_USERNAME && process.env.FUSIONSOLAR_SYSTEM_CODE);
  res.json({
    configured,
    apiUrl: process.env.FUSIONSOLAR_API_URL || "https://eu5.fusionsolar.huawei.com",
  });
});

/**
 * POST /api/fusionsolar/real
 * Fetches real-time active power from all inverters.
 * Body: { stationCode?: string }
 * Returns: { inverters: [{ devId, devName, activePowerW, dailyEnergyWh, totalEnergyWh }] }
 */
router.post("/real", async (req: Request, res: Response) => {
  const api = getApi();
  if (!api) {
    res.status(400).json({ error: "FusionSolar credentials not configured. Set FUSIONSOLAR_USERNAME and FUSIONSOLAR_SYSTEM_CODE env vars." });
    return;
  }

  try {
    const stationCode = req.body?.stationCode;

    // If no station code provided, discover all stations
    let stationCodes: string[] = [];
    if (stationCode) {
      stationCodes = [stationCode];
    } else {
      const stations = await api.getStationList();
      stationCodes = stations.map((s) => s.stationCode);
    }

    if (stationCodes.length === 0) {
      res.json({ inverters: [] });
      return;
    }

    // Get all devices, filter to inverters (devTypeId 17)
    const devices = await api.getDevList(stationCodes);
    const inverterDevIds = devices
      .filter((d) => d.devTypeId === 17)
      .map((d) => d.devId);

    if (inverterDevIds.length === 0) {
      res.json({ inverters: [], message: "No inverter devices found" });
      return;
    }

    const kpi = await api.getDevRealKpi(inverterDevIds, 17);

    res.json({
      inverters: kpi,
      devices: devices.filter((d) => d.devTypeId === 17),
    });
  } catch (err: any) {
    console.error(`[FusionSolar] /real error: ${err.message}`);
    res.status(500).json({ error: err.message });
  }
});

/**
 * POST /api/fusionsolar/backfill
 * Fetches historical inverter data for a date range and returns it
 * as an array of { timestamp, activePowerW } points.
 *
 * Body: {
 *   stationCode?: string,   — specific station (optional, defaults to all)
 *   startDate: string,      — ISO date string (e.g. "2024-01-01")
 *   endDate: string         — ISO date string (e.g. "2024-01-31")
 * }
 *
 * Returns: {
 *   points: [{ timestamp, activePowerW }],
 *   daysFetched: number,
 *   stationCode: string
 * }
 */
router.post("/backfill", async (req: Request, res: Response) => {
  const api = getApi();
  if (!api) {
    res.status(400).json({ error: "FusionSolar credentials not configured. Set FUSIONSOLAR_USERNAME and FUSIONSOLAR_SYSTEM_CODE env vars." });
    return;
  }

  const { startDate, endDate } = req.body || {};

  if (!startDate || !endDate) {
    res.status(400).json({ error: "startDate and endDate are required (ISO format: YYYY-MM-DD)" });
    return;
  }

  const start = new Date(startDate);
  const end = new Date(endDate);

  if (isNaN(start.getTime()) || isNaN(end.getTime())) {
    res.status(400).json({ error: "Invalid date format. Use ISO format: YYYY-MM-DD" });
    return;
  }

  if (start > end) {
    res.status(400).json({ error: "startDate must be before or equal to endDate" });
    return;
  }

  try {
    const stationCode = req.body?.stationCode;

    // Discover stations if not provided
    let stationCodes: string[] = [];
    if (stationCode) {
      stationCodes = [stationCode];
    } else {
      const stations = await api.getStationList();
      stationCodes = stations.map((s) => s.stationCode);
    }

    if (stationCodes.length === 0) {
      res.json({ points: [], daysFetched: 0, message: "No stations found" });
      return;
    }

    // Get inverter devices
    const devices = await api.getDevList(stationCodes);
    const inverterDevIds = devices
      .filter((d) => d.devTypeId === 17)
      .map((d) => d.devId);

    if (inverterDevIds.length === 0) {
      res.json({ points: [], daysFetched: 0, message: "No inverter devices found" });
      return;
    }

    // Iterate day by day, fetch history for each day
    const allPoints: InverterHistoryPoint[] = [];
    const days: Date[] = [];
    const cursor = new Date(start);
    cursor.setHours(0, 0, 0, 0);
    const endDay = new Date(end);
    endDay.setHours(0, 0, 0, 0);

    while (cursor <= endDay) {
      days.push(new Date(cursor));
      cursor.setDate(cursor.getDate() + 1);
    }

    console.log(`[FusionSolar] Backfill: fetching ${days.length} days of history for ${inverterDevIds.length} inverter(s)`);

    // FusionSolar API has rate limits (~1 call per second for history endpoints)
    // Process one day at a time with a small delay
    for (const day of days) {
      const collectTime = day.getTime(); // epoch millis at midnight
      try {
        const dayPoints = await api.getDevHistoryKpi(inverterDevIds, 17, collectTime);
        allPoints.push(...dayPoints);
        console.log(`[FusionSolar] Backfill ${day.toISOString().split("T")[0]}: ${dayPoints.length} points`);
      } catch (err: any) {
        console.error(`[FusionSolar] Backfill error for ${day.toISOString().split("T")[0]}: ${err.message}`);
      }

      // Rate limit: wait 1.5s between API calls
      if (days.indexOf(day) < days.length - 1) {
        await new Promise((resolve) => setTimeout(resolve, 1500));
      }
    }

    res.json({
      points: allPoints,
      daysFetched: days.length,
      stationCode: stationCodes[0],
      inverterCount: inverterDevIds.length,
    });
  } catch (err: any) {
    console.error(`[FusionSolar] /backfill error: ${err.message}`);
    res.status(500).json({ error: err.message });
  }
});

/**
 * POST /api/fusionsolar/kiosk
 * Fetches real-time inverter KPI + today's power curve using Kiosk mode.
 * No OpenAPI credentials needed — just the kiosk ID (kk).
 *
 * Body: { kk?: string }  — kiosk ID, or uses FUSIONSOLAR_KIOSK_KK env var
 *
 * Returns: {
 *   realKpi: { activePowerW, dailyEnergyWh, totalEnergyWh, monthEnergyWh, yearEnergyWh },
 *   powerCurve: [{ timestamp, activePowerW }]
 * }
 */
router.post("/kiosk", async (req: Request, res: Response) => {
  const kkId = req.body?.kk || process.env.FUSIONSOLAR_KIOSK_KK;
  if (!kkId) {
    res.status(400).json({
      error: "No kiosk ID provided. Set FUSIONSOLAR_KIOSK_KK env var or pass 'kk' in body.",
    });
    return;
  }

  const kioskUrl = process.env.FUSIONSOLAR_KIOSK_URL;
  const kiosk = new FusionSolarKiosk(kkId, kioskUrl);

  try {
    const [realKpi, powerCurve] = await Promise.all([
      kiosk.fetchRealKpi(),
      kiosk.fetchPowerCurve(),
    ]);

    res.json({
      realKpi,
      powerCurve,
      kkId,
    });
  } catch (err: any) {
    console.error(`[FusionSolar] /kiosk error: ${err.message}`);
    res.status(500).json({ error: err.message });
  }
});

export default router;
