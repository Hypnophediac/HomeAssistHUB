import express from "express";
import cors from "cors";
import { createServer } from "http";
import { Server } from "socket.io";
import { registerSocketHandlers } from "./socketHandlers";
import fusionSolarRoutes from "./fusionSolarRoutes";
import energyRoutes from "./energyRoutes";
import energyIngestRoutes from "./energyIngestRoutes";
import { connectMongo } from "./db";

const PORT = Number(process.env.PORT) || 3000;

const app = express();
app.use(cors());
app.use(express.json({ limit: "10mb" }));

app.get("/", (_req, res) => {
  res.json({ status: "ok", service: "smarthome-relay" });
});

app.get("/health", (_req, res) => {
  res.json({ status: "healthy", uptime: process.uptime() });
});

// FusionSolar inverter API routes
app.use("/api/fusionsolar", fusionSolarRoutes);

// Energy data routes (historical, from MongoDB)
app.use("/api/energy", energyRoutes);

// Energy data ingestion (Hub -> Render/MongoDB)
app.use("/api/energy", energyIngestRoutes);

const httpServer = createServer(app);

const io = new Server(httpServer, {
  cors: {
    origin: "*",
    methods: ["GET", "POST"],
  },
});

io.on("connection", (socket) => {
  console.log(`[connection] socket=${socket.id}`);
  registerSocketHandlers(io, socket);
});

httpServer.listen(PORT, () => {
  console.log(`smarthome-relay listening on port ${PORT}`);
  connectMongo().catch((err) => console.error("[db] Failed to connect on startup:", err));
  startKeepAlivePing();
});

/**
 * Render's free tier spins down web services after ~15 minutes without
 * an inbound HTTP request. Pinging our own public /health endpoint every
 * 10 minutes counts as genuine inbound traffic and keeps the instance awake.
 * Set KEEP_ALIVE_URL explicitly if RENDER_EXTERNAL_URL isn't available
 * (e.g. when deploying elsewhere); set KEEP_ALIVE_DISABLED=1 to turn this off.
 */
function startKeepAlivePing(): void {
  if (process.env.KEEP_ALIVE_DISABLED === "1") return;

  const baseUrl = process.env.KEEP_ALIVE_URL || process.env.RENDER_EXTERNAL_URL || `http://localhost:${PORT}`;
  const healthUrl = `${baseUrl.replace(/\/$/, "")}/health`;
  const intervalMs = 10 * 60 * 1000; // 10 minutes

  setInterval(() => {
    fetch(healthUrl)
      .then((res) => console.log(`[keep-alive] ping ${healthUrl} -> ${res.status}`))
      .catch((err) => console.error(`[keep-alive] ping failed: ${err.message}`));
  }, intervalMs);

  console.log(`[keep-alive] pinging ${healthUrl} every ${intervalMs / 60000} minutes`);
}
