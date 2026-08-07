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

const BUDAPEST_TZ = "Europe/Budapest";

function todayDateString(): string {
  return new Intl.DateTimeFormat("en-CA", {
    timeZone: BUDAPEST_TZ,
    year: "numeric", month: "2-digit", day: "2-digit",
  }).format(new Date());
}

function dateStringFromCal(d: Date): string {
  return new Intl.DateTimeFormat("en-CA", {
    timeZone: BUDAPEST_TZ,
    year: "numeric", month: "2-digit", day: "2-digit",
  }).format(d);
}

function dateRangeMillis(dateStr: string): [number, number] {
  // Determine Budapest offset (DST-aware) by checking what hour
  // Budapest shows at noon UTC for the given date.
  const noonUtc = new Date(dateStr + "T12:00:00Z");
  const parts = new Intl.DateTimeFormat("en-US", {
    timeZone: BUDAPEST_TZ,
    hour: "2-digit", minute: "2-digit", hour12: false,
  }).formatToParts(noonUtc);
  const bHour = parseInt(parts.find(p => p.type === "hour")?.value || "14");
  const bMin = parseInt(parts.find(p => p.type === "minute")?.value || "0");
  const offsetMin = (bHour * 60 + bMin) - 12 * 60;

  const midnightUtc = new Date(dateStr + "T00:00:00Z").getTime();
  const startMs = midnightUtc - offsetMin * 60 * 1000;
  const endMs = startMs + 24 * 60 * 60 * 1000;
  return [startMs, endMs];
}

function budapestHour(ts: number): number {
  return parseInt(new Intl.DateTimeFormat("en-US", {
    timeZone: BUDAPEST_TZ,
    hour: "2-digit", hour12: false,
  }).format(new Date(ts)));
}

function computeDailyStatsFromRaw(readings: any[], totalConsumed: number, totalExported: number, totalProduced: number) {
  if (readings.length === 0) return null;
  const powers = readings.map(r => r.currentPowerW || 0);
  const imports = readings.map(r => r.powerImportW || 0);
  const exportPowers = readings.map(r => r.powerExportW || 0);
  const minPowerW = Math.min(...powers);
  const maxPowerW = Math.max(...powers);
  const avgPowerW = powers.reduce((s, v) => s + v, 0) / powers.length;
  const maxImportW = Math.max(...imports);
  const maxExportW = Math.max(...exportPowers);

  // Tariff deltas from first to last reading
  const first = readings[0];
  const last = readings[readings.length - 1];
  const importT1Kwh = Math.max(0, (last.importT1Kwh || 0) - (first.importT1Kwh || 0));
  const importT2Kwh = Math.max(0, (last.importT2Kwh || 0) - (first.importT2Kwh || 0));
  const exportT1Kwh = Math.max(0, (last.exportT1Kwh || 0) - (first.exportT1Kwh || 0));
  const exportT2Kwh = Math.max(0, (last.exportT2Kwh || 0) - (first.exportT2Kwh || 0));

  const netEnergyKwh = totalConsumed - totalExported;

  const selfConsumptionRatio = totalProduced > 0
    ? Math.max(0, Math.min(1, (totalProduced - totalExported) / totalProduced))
    : 0;

  // Averages for voltage, current, power factor, frequency
  const avg = (arr: number[]) => arr.length ? arr.reduce((s, v) => s + v, 0) / arr.length : 0;
  const avgL1V = avg(readings.map(r => r.l1V || 0));
  const avgL2V = avg(readings.map(r => r.l2V || 0));
  const avgL3V = avg(readings.map(r => r.l3V || 0));
  const avgL1A = avg(readings.map(r => r.l1A || 0));
  const avgL2A = avg(readings.map(r => r.l2A || 0));
  const avgL3A = avg(readings.map(r => r.l3A || 0));
  const avgPowerFactor = avg(readings.map(r => r.powerFactor || 0));
  const avgFrequencyHz = avg(readings.map(r => r.frequencyHz || 50));

  return {
    minPowerW, maxPowerW, avgPowerW, maxImportW, maxExportW,
    importT1Kwh, importT2Kwh, exportT1Kwh, exportT2Kwh,
    netEnergyKwh, selfConsumptionRatio,
    avgL1V, avgL2V, avgL3V, avgL1A, avgL2A, avgL3A,
    avgPowerFactor, avgFrequencyHz,
  };
}


// ── Helper: compute hourly buckets from raw P1 readings for a single day ──
// Returns average power (kW) per hour, not kWh. This makes the chart
// visually comparable to the real-time dashboard (which shows W).
function computeHourlyBuckets(readings: any[]): any[] {
  const buckets: Record<number, { first: any; last: any }> = {};
  for (const r of readings) {
    const hour = budapestHour(r.timestamp);
    if (!buckets[hour]) {
      buckets[hour] = { first: r, last: r };
    } else {
      buckets[hour].last = r;
    }
  }
  return Array.from({ length: 24 }, (_, h) => {
    const b = buckets[h];
    if (b && b.first && b.last && b.first !== b.last) {
      const consumedKwh = Math.max(0,
        (b.last.importT1Kwh + b.last.importT2Kwh) - (b.first.importT1Kwh + b.first.importT2Kwh)
      );
      const exportedKwh = Math.max(0,
        (b.last.exportT1Kwh + b.last.exportT2Kwh) - (b.first.exportT1Kwh + b.first.exportT2Kwh)
      );
      // Convert kWh to average kW: divide by actual time span in hours
      const durationMs = (b.last.timestamp - b.first.timestamp);
      const durationH = durationMs > 0 ? durationMs / 3_600_000 : 1.0;
      const consumedKw = consumedKwh / durationH;
      const exportedKw = exportedKwh / durationH;
      return { hour: h, consumedKwh: consumedKw, exportedKwh: exportedKw };
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
    // hourly now returns average kW (not kWh), so compute real kWh totals separately
    const { consumed: totalConsumed, exported: totalExported } = computeDailyConsumedExported(rawReadings);
    const latest = rawReadings[rawReadings.length - 1];

    // Get today's inverter daily summary (live or finalized)
    const invDaily = await InverterDailySummary.findOne({ homeId, date: today }).lean();
    const latestInv = await InverterReading.findOne({ homeId }).sort({ timestamp: -1 }).lean();
    const producedKwh = invDaily?.producedKwh ?? latestInv?.dailyEnergyKwh ?? 0;

    // Get today's P1 daily summary (if the Hub has pushed one)
    const p1Daily = await P1DailySummary.findOne({ homeId, date: today }).lean();

    // Compute peak hours from hourly buckets
    let peakConsumptionHour = -1, peakExportHour = -1;
    let peakConsumptionKwh = 0, peakExportKwh = 0;
    for (const h of hourly) {
      if (h.consumedKwh > peakConsumptionKwh) {
        peakConsumptionKwh = h.consumedKwh;
        peakConsumptionHour = h.hour;
      }
      if (h.exportedKwh > peakExportKwh) {
        peakExportKwh = h.exportedKwh;
        peakExportHour = h.hour;
      }
    }

    // Use P1DailySummary if available, otherwise compute from raw readings
    const rawStats = computeDailyStatsFromRaw(rawReadings, totalConsumed, totalExported, producedKwh);

    const dailyStats: any = p1Daily || rawStats || {};

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
      // ── Daily statistics (from P1DailySummary or computed from raw) ──
      minPowerW: dailyStats.minPowerW ?? 0,
      maxPowerW: dailyStats.maxPowerW ?? 0,
      avgPowerW: dailyStats.avgPowerW ?? 0,
      maxImportW: dailyStats.maxImportW ?? 0,
      maxExportW: dailyStats.maxExportW ?? 0,
      peakConsumptionHour: p1Daily?.peakConsumptionHour ?? peakConsumptionHour,
      peakExportHour: p1Daily?.peakExportHour ?? peakExportHour,
      peakConsumptionKwh: p1Daily?.peakConsumptionKwh ?? peakConsumptionKwh,
      peakExportKwh: p1Daily?.peakExportKwh ?? peakExportKwh,
      selfConsumptionRatio: dailyStats.selfConsumptionRatio ?? 0,
      netEnergyKwh: dailyStats.netEnergyKwh ?? 0,
      importT1Kwh: dailyStats.importT1Kwh ?? 0,
      importT2Kwh: dailyStats.importT2Kwh ?? 0,
      exportT1Kwh: dailyStats.exportT1Kwh ?? 0,
      exportT2Kwh: dailyStats.exportT2Kwh ?? 0,
      avgL1V: dailyStats.avgL1V ?? 0,
      avgL2V: dailyStats.avgL2V ?? 0,
      avgL3V: dailyStats.avgL3V ?? 0,
      avgL1A: dailyStats.avgL1A ?? 0,
      avgL2A: dailyStats.avgL2A ?? 0,
      avgL3A: dailyStats.avgL3A ?? 0,
      avgPowerFactor: dailyStats.avgPowerFactor ?? 0,
      avgFrequencyHz: dailyStats.avgFrequencyHz ?? 50,
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
      const daysInMonth = new Date(year, m + 1, 0).getDate();
      const monthStart = `${year}-${String(m + 1).padStart(2, "0")}-01`;
      const monthEnd = `${year}-${String(m + 1).padStart(2, "0")}-${String(daysInMonth).padStart(2, "0")}`;
      const [startMs] = dateRangeMillis(monthStart);
      const [, endMs] = dateRangeMillis(monthEnd);
      const readings = await P1RawReading
        .find({ homeId, timestamp: { $gte: startMs, $lt: endMs } })
        .sort({ timestamp: 1 })
        .lean();
      const { consumed, exported } = computeDailyConsumedExported(readings);

      // Sum inverter daily summaries for this month
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
