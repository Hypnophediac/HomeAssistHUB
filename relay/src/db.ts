import mongoose from "mongoose";

/**
 * MongoDB connection (Atlas M0 free tier).
 * The MONGODB_URI env var must be set; if missing, energy routes
 * will return 503 and ingest will fail gracefully — the relay
 * still functions as a stateless Socket.IO broker without Mongo.
 */
let connected = false;

export async function connectMongo(): Promise<void> {
  const uri = process.env.MONGODB_URI;
  if (!uri) {
    console.warn("[db] MONGODB_URI not set — energy API disabled");
    return;
  }
  try {
    await mongoose.connect(uri, {
      serverSelectionTimeoutMS: 10_000,
    });
    connected = true;
    console.log("[db] MongoDB connected");
    startRawDataCleanup();
  } catch (err) {
    console.error("[db] MongoDB connection failed:", err);
  }
}

export function isMongoConnected(): boolean {
  return connected;
}

// ── Schemas ──

const p1RawReadingSchema = new mongoose.Schema({
  homeId: { type: String, required: true, index: true },
  timestamp: { type: Number, required: true, index: true },
  powerImportW: { type: Number, default: 0 },
  powerExportW: { type: Number, default: 0 },
  importT1Kwh: { type: Number, default: 0 },
  importT2Kwh: { type: Number, default: 0 },
  exportT1Kwh: { type: Number, default: 0 },
  exportT2Kwh: { type: Number, default: 0 },
  currentPowerW: { type: Number, default: 0 },
  l1V: { type: Number, default: 0 },
  l2V: { type: Number, default: 0 },
  l3V: { type: Number, default: 0 },
  l1A: { type: Number, default: 0 },
  l2A: { type: Number, default: 0 },
  l3A: { type: Number, default: 0 },
  powerImportL1W: { type: Number, default: 0 },
  powerImportL2W: { type: Number, default: 0 },
  powerImportL3W: { type: Number, default: 0 },
  powerExportL1W: { type: Number, default: 0 },
  powerExportL2W: { type: Number, default: 0 },
  powerExportL3W: { type: Number, default: 0 },
  powerFactor: { type: Number, default: 0 },
  frequencyHz: { type: Number, default: 50 },
  currentTariff: { type: Number, default: 1 },
}, { _id: true, timestamps: false });

p1RawReadingSchema.index({ homeId: 1, timestamp: 1 }, { unique: true });

const inverterReadingSchema = new mongoose.Schema({
  homeId: { type: String, required: true, index: true },
  timestamp: { type: Number, required: true, index: true },
  activePowerW: { type: Number, default: 0 },
  dailyEnergyKwh: { type: Number, default: 0 },
}, { _id: true, timestamps: false });

inverterReadingSchema.index({ homeId: 1, timestamp: 1 }, { unique: true });

const p1DailySummarySchema = new mongoose.Schema({
  homeId: { type: String, required: true, index: true },
  date: { type: String, required: true }, // yyyy-MM-dd
  totalConsumedKwh: { type: Number, default: 0 },
  totalExportedKwh: { type: Number, default: 0 },
  importT1Kwh: { type: Number, default: 0 },
  importT2Kwh: { type: Number, default: 0 },
  exportT1Kwh: { type: Number, default: 0 },
  exportT2Kwh: { type: Number, default: 0 },
  minPowerW: { type: Number, default: 0 },
  maxPowerW: { type: Number, default: 0 },
  avgPowerW: { type: Number, default: 0 },
  maxImportW: { type: Number, default: 0 },
  maxExportW: { type: Number, default: 0 },
  peakConsumptionHour: { type: Number, default: -1 },
  peakExportHour: { type: Number, default: -1 },
  peakConsumptionKwh: { type: Number, default: 0 },
  peakExportKwh: { type: Number, default: 0 },
  selfConsumptionRatio: { type: Number, default: 0 },
  netEnergyKwh: { type: Number, default: 0 },
  avgL1V: { type: Number, default: 0 },
  avgL2V: { type: Number, default: 0 },
  avgL3V: { type: Number, default: 0 },
  avgL1A: { type: Number, default: 0 },
  avgL2A: { type: Number, default: 0 },
  avgL3A: { type: Number, default: 0 },
  avgPowerFactor: { type: Number, default: 0 },
  avgFrequencyHz: { type: Number, default: 50 },
}, { _id: true, timestamps: false });

p1DailySummarySchema.index({ homeId: 1, date: 1 }, { unique: true });

const inverterDailySummarySchema = new mongoose.Schema({
  homeId: { type: String, required: true, index: true },
  date: { type: String, required: true }, // yyyy-MM-dd
  producedKwh: { type: Number, required: true },
}, { _id: true, timestamps: false });

inverterDailySummarySchema.index({ homeId: 1, date: 1 }, { unique: true });

const homeTokenSchema = new mongoose.Schema({
  homeId: { type: String, required: true, unique: true, index: true },
  syncToken: { type: String, required: true },
  createdAt: { type: Date, default: Date.now },
}, { _id: true, timestamps: true });

// ── Models ──

export const P1RawReading = mongoose.model("P1RawReading", p1RawReadingSchema);
export const InverterReading = mongoose.model("InverterReading", inverterReadingSchema);
export const P1DailySummary = mongoose.model("P1DailySummary", p1DailySummarySchema);
export const InverterDailySummary = mongoose.model("InverterDailySummary", inverterDailySummarySchema);
export const HomeToken = mongoose.model("HomeToken", homeTokenSchema);

/**
 * Rolling-window cleanup: deletes raw readings (P1RawReading + InverterReading)
 * older than 14 days. Runs every 6 hours. Daily summaries are kept indefinitely
 * (they're tiny — one doc per day per home).
 */
function startRawDataCleanup(): void {
  const RETENTION_MS = 14 * 24 * 60 * 60 * 1000;
  const INTERVAL_MS = 6 * 60 * 60 * 1000;

  const cleanup = async () => {
    const cutoff = Date.now() - RETENTION_MS;
    try {
      const p1Deleted = await P1RawReading.deleteMany({ timestamp: { $lt: cutoff } });
      const invDeleted = await InverterReading.deleteMany({ timestamp: { $lt: cutoff } });
      if (p1Deleted.deletedCount > 0 || invDeleted.deletedCount > 0) {
        console.log(`[db] Cleanup: deleted ${p1Deleted.deletedCount} P1 raw + ${invDeleted.deletedCount} inverter readings older than 14 days`);
      }
    } catch (err) {
      console.error("[db] Cleanup failed:", err);
    }
  };

  cleanup();
  setInterval(cleanup, INTERVAL_MS);
  console.log("[db] Raw data cleanup scheduled (14-day rolling window, every 6h)");
}
