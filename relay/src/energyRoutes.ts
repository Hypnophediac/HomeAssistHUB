import { Router, Request, Response } from "express";
import { syncTokenAuth } from "./authMiddleware";
import {
  P1RawReading,
  InverterReading,
  P1DailySummary,
  InverterDailySummary,
  isMongoConnected,
} from "./db";

const router = Router();


// ── Helper: compute hourly buckets from raw P1 readings for a single day ──
function computeHourlyBuckets(readings: any[]): any[] {
  const buckets: Record<number, { first: any; last: any }> = {};
  for (const r of readings) {
    const date = new Date(r.timestamp);
    const hour = date.getHours();
    if (!buckets[hour]) {
      buckets[hour] = { first: r, last: r };
    } else {
      buckets[hour].last = r;
    }
  }
  return Array.from({ length: 24 }, (_, h) => {
    const b = buckets[h];
    if (b && b.first && b.last && b.first !== b.last) {
      const consumed = Math.max(0,
        (b.last.importT1Kwh + b.last.importT2Kwh) - (b.first.importT1Kwh + b.first.importT2Kwh)
      );
      const exported = Math.max(0,
        (b.last.exportT1Kwh + b.last.exportT2Kwh) - (b.first.exportT1Kwh + b.first.exportT2Kwh)
      );
      return { hour: h, consumedKwh: consumed, exportedKwh: exported };
    }
    return { hour: h, consumedKwh: 0, exportedKwh: 0 };
  });
}

// ── Helper: compute daily consumed/exported from first/last raw readings ──
function computeDailyConsumedExported(readings: any[]): { consumed: number; exported: number } {
  if (readings.length < 2) return { consumed: 0, exported: 0 };
  const first = readings[0];
  const last = readings[readings.length - 1];
  const consumed = Math.max(0,
    (last.importT1Kwh + last.importT2Kwh) - (first.importT1Kwh + first.importT2Kwh)
  );
  const exported = Math.max(0,
    (last.exportT1Kwh + last.exportT2Kwh) - (first.exportT1Kwh + first.exportT2Kwh)
  );
  return { consumed, exported };
}

function dateRangeMillis(dateStr: string): [number, number] {
  const date = new Date(dateStr + "T00:00:00");
  const startMs = date.getTime();
  const nextDay = new Date(date);
  nextDay.setDate(nextDay.getDate() + 1);
  return [startMs, nextDay.getTime()];
}

function todayDateString(): string {
  return new Date().toISOString().slice(0, 10);
}

function dateStringFromCal(d: Date): string {
  return d.toISOString().slice(0, 10);
}

// ── GET /api/energy/:homeId/daily ──
router.get("/:homeId/daily", syncTokenAuth, async (req: Request & { homeId?: string }, res: Response) => {
  if (!isMongoConnected()) { res.status(503).json({ error: "DB not connected" }); return; }
  const homeId = req.homeId!;
  try {
    const today = todayDateString();
    const [startMs, endMs] = dateRangeMillis(today);
    const rawReadings = await P1RawReading
      .find({ homeId, timestamp: { $gte: startMs, $lt: endMs } })
      .sort({ timestamp: 1 })
      .lean();

    const hourly = computeHourlyBuckets(rawReadings);
    const totalConsumed = hourly.reduce((s, h) => s + h.consumedKwh, 0);
    const totalExported = hourly.reduce((s, h) => s + h.exportedKwh, 0);
    const latest = rawReadings[rawReadings.length - 1];

    // Get today's inverter daily summary (live or finalized)
    const invDaily = await InverterDailySummary.findOne({ homeId, date: today }).lean();
    const latestInv = await InverterReading.findOne({ homeId }).sort({ timestamp: -1 }).lean();
    const producedKwh = invDaily?.producedKwh ?? latestInv?.dailyEnergyKwh ?? 0;

    res.json({
      hourly,
      totalConsumedKwh: totalConsumed,
      totalExportedKwh: totalExported,
      totalProducedKwh: producedKwh,
      latestPowerW: latest?.currentPowerW ?? 0,
      latestPowerImportW: latest?.powerImportW ?? 0,
      latestPowerExportW: latest?.powerExportW ?? 0,
      latestL1V: latest?.l1V ?? 0,
      latestL2V: latest?.l2V ?? 0,
      latestL3V: latest?.l3V ?? 0,
      latestL1A: latest?.l1A ?? 0,
      latestL2A: latest?.l2A ?? 0,
      latestL3A: latest?.l3A ?? 0,
      latestPowerImportL1W: latest?.powerImportL1W ?? 0,
      latestPowerImportL2W: latest?.powerImportL2W ?? 0,
      latestPowerImportL3W: latest?.powerImportL3W ?? 0,
      latestPowerExportL1W: latest?.powerExportL1W ?? 0,
      latestPowerExportL2W: latest?.powerExportL2W ?? 0,
      latestPowerExportL3W: latest?.powerExportL3W ?? 0,
      latestPowerFactor: latest?.powerFactor ?? 0,
      latestFrequencyHz: latest?.frequencyHz ?? 50,
      latestCurrentTariff: latest?.currentTariff ?? 1,
    });
  } catch (err) {
    console.error("[energy/daily] Error:", err);
    res.status(500).json({ error: "Internal server error" });
  }
});

// ── GET /api/energy/:homeId/weekly ──
router.get("/:homeId/weekly", syncTokenAuth, async (req: Request & { homeId?: string }, res: Response) => {
  if (!isMongoConnected()) { res.status(503).json({ error: "DB not connected" }); return; }
  const homeId = req.homeId!;
  try {
    const entries: any[] = [];
    let totalConsumed = 0, totalExported = 0, totalProduced = 0;

    for (let i = 6; i >= 0; i--) {
      const d = new Date();
      d.setDate(d.getDate() - i);
      const dateStr = dateStringFromCal(d);
      const [sMs, eMs] = dateRangeMillis(dateStr);
      const readings = await P1RawReading
        .find({ homeId, timestamp: { $gte: sMs, $lt: eMs } })
        .sort({ timestamp: 1 })
        .lean();
      const { consumed, exported } = computeDailyConsumedExported(readings);
      const invDaily = await InverterDailySummary.findOne({ homeId, date: dateStr }).lean();
      const produced = invDaily?.producedKwh ?? null;

      entries.push({ label: dateStr.slice(5), consumedKwh: consumed, exportedKwh: exported, producedKwh: produced });
      totalConsumed += consumed;
      totalExported += exported;
      totalProduced += produced ?? 0;
    }

    res.json({ entries, totalConsumedKwh: totalConsumed, totalExportedKwh: totalExported, totalProducedKwh: totalProduced });
  } catch (err) {
    console.error("[energy/weekly] Error:", err);
    res.status(500).json({ error: "Internal server error" });
  }
});

// ── GET /api/energy/:homeId/monthly ──
router.get("/:homeId/monthly", syncTokenAuth, async (req: Request & { homeId?: string }, res: Response) => {
  if (!isMongoConnected()) { res.status(503).json({ error: "DB not connected" }); return; }
  const homeId = req.homeId!;
  try {
    const now = new Date();
    const year = now.getFullYear();
    const month = now.getMonth();
    const daysInMonth = new Date(year, month + 1, 0).getDate();
    const entries: any[] = [];
    let totalConsumed = 0, totalExported = 0, totalProduced = 0;

    for (let day = 1; day <= daysInMonth; day++) {
      const dateStr = `${year}-${String(month + 1).padStart(2, "0")}-${String(day).padStart(2, "0")}`;
      const [sMs, eMs] = dateRangeMillis(dateStr);
      const readings = await P1RawReading
        .find({ homeId, timestamp: { $gte: sMs, $lt: eMs } })
        .sort({ timestamp: 1 })
        .lean();
      const { consumed, exported } = computeDailyConsumedExported(readings);
      const invDaily = await InverterDailySummary.findOne({ homeId, date: dateStr }).lean();
      const produced = invDaily?.producedKwh ?? null;

      entries.push({ label: String(day), consumedKwh: consumed, exportedKwh: exported, producedKwh: produced });
      totalConsumed += consumed;
      totalExported += exported;
      totalProduced += produced ?? 0;
    }

    res.json({ entries, totalConsumedKwh: totalConsumed, totalExportedKwh: totalExported, totalProducedKwh: totalProduced });
  } catch (err) {
    console.error("[energy/monthly] Error:", err);
    res.status(500).json({ error: "Internal server error" });
  }
});

// ── GET /api/energy/:homeId/yearly ──
router.get("/:homeId/yearly", syncTokenAuth, async (req: Request & { homeId?: string }, res: Response) => {
  if (!isMongoConnected()) { res.status(503).json({ error: "DB not connected" }); return; }
  const homeId = req.homeId!;
  try {
    const year = new Date().getFullYear();
    const entries: any[] = [];
    let totalConsumed = 0, totalExported = 0, totalProduced = 0;
    const monthNames = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

    for (let m = 0; m < 12; m++) {
      const startMs = new Date(year, m, 1).getTime();
      const endMs = new Date(year, m + 1, 1).getTime();
      const readings = await P1RawReading
        .find({ homeId, timestamp: { $gte: startMs, $lt: endMs } })
        .sort({ timestamp: 1 })
        .lean();
      const { consumed, exported } = computeDailyConsumedExported(readings);

      // Sum inverter daily summaries for this month
      const monthStart = `${year}-${String(m + 1).padStart(2, "0")}-01`;
      const daysInMonth = new Date(year, m + 1, 0).getDate();
      const monthEnd = `${year}-${String(m + 1).padStart(2, "0")}-${String(daysInMonth).padStart(2, "0")}`;
      const invSummaries = await InverterDailySummary
        .find({ homeId, date: { $gte: monthStart, $lte: monthEnd } })
        .lean();
      let produced: number | null = invSummaries.reduce((s: number, d: any) => s + d.producedKwh, 0);
      if (invSummaries.length === 0 && produced === 0) produced = null;

      entries.push({ label: monthNames[m], consumedKwh: consumed, exportedKwh: exported, producedKwh: produced });
      totalConsumed += consumed;
      totalExported += exported;
      totalProduced += produced ?? 0;
    }

    res.json({ entries, totalConsumedKwh: totalConsumed, totalExportedKwh: totalExported, totalProducedKwh: totalProduced });
  } catch (err) {
    console.error("[energy/yearly] Error:", err);
    res.status(500).json({ error: "Internal server error" });
  }
});

// ── GET /api/energy/:homeId/range?startDate=..&endDate=.. ──
router.get("/:homeId/range", syncTokenAuth, async (req: Request & { homeId?: string }, res: Response) => {
  if (!isMongoConnected()) { res.status(503).json({ error: "DB not connected" }); return; }
  const homeId = req.homeId!;
  const startDate = req.query.startDate as string;
  const endDate = req.query.endDate as string;
  if (!startDate || !endDate) {
    res.status(400).json({ error: "Missing startDate or endDate" });
    return;
  }
  try {
    const entries: any[] = [];
    let totalConsumed = 0, totalExported = 0, totalProduced = 0;

    const cursor = new Date(startDate + "T00:00:00");
    const end = new Date(endDate + "T00:00:00");
    while (cursor <= end) {
      const dateStr = dateStringFromCal(cursor);
      const [sMs, eMs] = dateRangeMillis(dateStr);
      const readings = await P1RawReading
        .find({ homeId, timestamp: { $gte: sMs, $lt: eMs } })
        .sort({ timestamp: 1 })
        .lean();
      const { consumed, exported } = computeDailyConsumedExported(readings);
      const invDaily = await InverterDailySummary.findOne({ homeId, date: dateStr }).lean();
      const produced = invDaily?.producedKwh ?? null;

      entries.push({ label: dateStr.slice(5), consumedKwh: consumed, exportedKwh: exported, producedKwh: produced });
      totalConsumed += consumed;
      totalExported += exported;
      totalProduced += produced ?? 0;
      cursor.setDate(cursor.getDate() + 1);
    }

    res.json({ entries, totalConsumedKwh: totalConsumed, totalExportedKwh: totalExported, totalProducedKwh: totalProduced });
  } catch (err) {
    console.error("[energy/range] Error:", err);
    res.status(500).json({ error: "Internal server error" });
  }
});

export default router;
