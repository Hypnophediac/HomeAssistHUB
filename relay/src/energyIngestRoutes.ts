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

// All ingest routes require syncToken auth
router.use(syncTokenAuth);

// ── POST /api/energy/:homeId/ingest ──
// Body: { p1Readings: [...], inverterReadings: [...] }
router.post("/:homeId/ingest", async (req: Request & { homeId?: string }, res: Response) => {
  if (!isMongoConnected()) { res.status(503).json({ error: "DB not connected" }); return; }
  const homeId = req.homeId!;
  try {
    const { p1Readings, inverterReadings } = req.body;

    let p1Inserted = 0;
    let invInserted = 0;

    if (Array.isArray(p1Readings) && p1Readings.length > 0) {
      const docs = p1Readings.map((r: any) => ({
        homeId,
        timestamp: r.timestamp,
        powerImportW: r.powerImportW ?? 0,
        powerExportW: r.powerExportW ?? 0,
        importT1Kwh: r.importT1Kwh ?? 0,
        importT2Kwh: r.importT2Kwh ?? 0,
        exportT1Kwh: r.exportT1Kwh ?? 0,
        exportT2Kwh: r.exportT2Kwh ?? 0,
        currentPowerW: r.currentPowerW ?? 0,
        l1V: r.l1V ?? 0,
        l2V: r.l2V ?? 0,
        l3V: r.l3V ?? 0,
        l1A: r.l1A ?? 0,
        l2A: r.l2A ?? 0,
        l3A: r.l3A ?? 0,
        powerImportL1W: r.powerImportL1W ?? 0,
        powerImportL2W: r.powerImportL2W ?? 0,
        powerImportL3W: r.powerImportL3W ?? 0,
        powerExportL1W: r.powerExportL1W ?? 0,
        powerExportL2W: r.powerExportL2W ?? 0,
        powerExportL3W: r.powerExportL3W ?? 0,
        powerFactor: r.powerFactor ?? 0,
        frequencyHz: r.frequencyHz ?? 50,
        currentTariff: r.currentTariff ?? 1,
      }));
      const result = await P1RawReading.insertMany(docs, { ordered: false });
      p1Inserted = result.length;
    }

    if (Array.isArray(inverterReadings) && inverterReadings.length > 0) {
      const docs = inverterReadings.map((r: any) => ({
        homeId,
        timestamp: r.timestamp,
        activePowerW: r.activePowerW ?? 0,
        dailyEnergyKwh: r.dailyEnergyKwh ?? 0,
      }));
      const result = await InverterReading.insertMany(docs, { ordered: false });
      invInserted = result.length;
    }

    res.json({ p1Inserted, invInserted });
  } catch (err: any) {
    // insertMany with ordered:false may have some duplicate key errors — that's OK
    if (err?.code === 11000 || err?.writeErrors) {
      const inserted = err.insertedDocs?.length ?? 0;
      res.json({ p1Inserted: inserted, invInserted: 0, partial: true });
    } else {
      console.error("[energy/ingest] Error:", err);
      res.status(500).json({ error: "Internal server error" });
    }
  }
});

// ── POST /api/energy/:homeId/daily-summary ──
// Body: { p1Summary: {...}, inverterSummary: { date, producedKwh } }
router.post("/:homeId/daily-summary", async (req: Request & { homeId?: string }, res: Response) => {
  if (!isMongoConnected()) { res.status(503).json({ error: "DB not connected" }); return; }
  const homeId = req.homeId!;
  try {
    const { p1Summary, inverterSummary } = req.body;

    if (p1Summary && p1Summary.date) {
      await P1DailySummary.findOneAndUpdate(
        { homeId, date: p1Summary.date },
        { $set: { homeId, ...p1Summary } },
        { upsert: true }
      );
    }

    if (inverterSummary && inverterSummary.date) {
      await InverterDailySummary.findOneAndUpdate(
        { homeId, date: inverterSummary.date },
        { $set: { homeId, date: inverterSummary.date, producedKwh: inverterSummary.producedKwh } },
        { upsert: true }
      );
    }

    res.json({ saved: true });
  } catch (err) {
    console.error("[energy/daily-summary] Error:", err);
    res.status(500).json({ error: "Internal server error" });
  }
});

export default router;
