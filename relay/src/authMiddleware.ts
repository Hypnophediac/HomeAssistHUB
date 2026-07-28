import { Request, Response, NextFunction } from "express";
import { HomeToken, isMongoConnected } from "./db";

/**
 * Express middleware that validates the Authorization: Bearer <syncToken>
 * header against the MongoDB HomeToken collection.
 *
 * On the very first ingest request for a homeId, the token is registered
 * (stored) and from then on must match on all subsequent requests.
 *
 * Attaches `homeId` and `syncToken` to req for downstream handlers.
 */
export async function syncTokenAuth(req: Request & { homeId?: string; syncToken?: string }, res: Response, next: NextFunction): Promise<void> {
  const authHeader = req.headers.authorization;
  if (!authHeader || !authHeader.startsWith("Bearer ")) {
    res.status(401).json({ error: "Missing or invalid Authorization header" });
    return;
  }

  const token = authHeader.slice(7).trim();
  if (!token) {
    res.status(401).json({ error: "Empty sync token" });
    return;
  }

  const homeId = req.params.homeId || req.body?.homeId || req.query?.homeId;
  if (!homeId || typeof homeId !== "string") {
    res.status(400).json({ error: "Missing homeId" });
    return;
  }

  if (!isMongoConnected()) {
    res.status(503).json({ error: "Database not connected" });
    return;
  }

  try {
    const existing = await HomeToken.findOne({ homeId }).lean();

    if (existing) {
      if (existing.syncToken !== token) {
        res.status(403).json({ error: "Invalid sync token for this homeId" });
        return;
      }
    } else {
      // First request for this homeId — register the token
      await HomeToken.create({ homeId, syncToken: token });
      console.log(`[auth] Registered new sync token for home=${homeId}`);
    }

    req.homeId = homeId;
    req.syncToken = token;
    next();
  } catch (err) {
    console.error("[auth] Token validation failed:", err);
    res.status(500).json({ error: "Internal server error" });
  }
}
