/**
 * FusionSolar Northbound OpenAPI + Kiosk client.
 *
 * OpenAPI implements:
 *   1. Login and obtain an XSRF-TOKEN
 *   2. List stations (getStationList)
 *   3. List devices per station (getDevList)
 *   4. Fetch real-time inverter KPI (getDevRealKpi) — active power
 *   5. Fetch historical 5-minute KPI (getDevHistoryKpi) — for backfill
 *
 * Kiosk mode:
 *   Simple GET request with a kiosk ID (kk) — no credentials needed.
 *   Returns realKpi + powerCurve (time-series) for a station.
 *
 * API reference: https://support.huawei.com/enterprise/en/doc/EDOC1100261860
 * Field names based on PyFusionSolarDataRelay source code analysis.
 */

const DEFAULT_BASE_URL = "https://eu5.fusionsolar.huawei.com";
const DEFAULT_KIOSK_URL = "https://region01eu5.fusionsolar.huawei.com/rest/pvms/web/kiosk/v1/station-kiosk-file?kk=";

export interface FusionSolarConfig {
  baseUrl: string;
  username: string;
  systemCode: string;
}

export interface StationInfo {
  stationCode: string;
  stationName: string;
  capacity: number;
}

export interface DeviceInfo {
  devId: string;
  devName: string;
  stationCode: string;
  devTypeId: number;
  inverterType: string;
}

export interface InverterRealKpi {
  devId: string;
  activePowerW: number;
  dailyEnergyWh: number;
  totalEnergyWh: number;
}

export interface InverterHistoryPoint {
  timestamp: number; // epoch millis
  activePowerW: number;
}

export class FusionSolarApi {
  private token: string | null = null;
  private readonly baseUrl: string;
  private readonly username: string;
  private readonly systemCode: string;

  constructor(config: FusionSolarConfig) {
    this.baseUrl = config.baseUrl || DEFAULT_BASE_URL;
    this.username = config.username;
    this.systemCode = config.systemCode;
  }

  /**
   * Login to the FusionSolar OpenAPI and store the XSRF-TOKEN.
   * Token is valid for ~30 minutes.
   */
  async login(): Promise<void> {
    const url = `${this.baseUrl}/thirdData/login`;
    const body = {
      userName: this.username,
      systemCode: this.systemCode,
    };

    const res = await fetch(url, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
    });

    if (!res.ok) {
      throw new Error(`FusionSolar login HTTP ${res.status}: ${await res.text()}`);
    }

    // The XSRF-TOKEN is returned as a response header (lowercase in fetch)
    const token = res.headers.get("xsrf-token") || res.headers.get("XSRF-TOKEN");
    if (!token) {
      // Some endpoints return the token in a cookie; try extracting from set-cookie
      const setCookie = res.headers.get("set-cookie") || "";
      const match = setCookie.match(/XSRF-TOKEN=([^;]+)/);
      if (match) {
        this.token = match[1];
        return;
      }
      throw new Error("FusionSolar login: no XSRF-TOKEN in response headers or cookies");
    }
    this.token = token;
  }

  /**
   * Ensure we have a valid token before making API calls.
   */
  private async ensureLoggedIn(): Promise<void> {
    if (!this.token) {
      await this.login();
    }
  }

  /**
   * Make an authenticated POST to a FusionSolar API endpoint.
   * Auto-retries once on token expiry (failCode 305).
   */
  private async doCall(endpoint: string, body: Record<string, unknown> = {}): Promise<any> {
    await this.ensureLoggedIn();

    const url = `${this.baseUrl}/thirdData/${endpoint}`;
    const res = await fetch(url, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "xsrf-token": this.token!,
      },
      body: JSON.stringify(body),
    });

    if (!res.ok) {
      throw new Error(`FusionSolar ${endpoint} HTTP ${res.status}: ${await res.text()}`);
    }

    const json = await res.json();

    // failCode 305 = token expired, retry once
    if (json.failCode === 305) {
      console.log(`[FusionSolar] Token expired (305), re-logging in...`);
      this.token = null;
      return this.doCall(endpoint, body);
    }

    // failCode 407 = rate limited
    if (json.failCode === 407) {
      throw new Error(`FusionSolar ${endpoint}: rate limited (407). Slow down.`);
    }

    if (json.failCode && json.failCode !== 0) {
      throw new Error(`FusionSolar ${endpoint} failCode ${json.failCode}: ${json.message || json.data}`);
    }

    if (!json.success && json.success !== undefined) {
      throw new Error(`FusionSolar ${endpoint} not successful: ${json.message || JSON.stringify(json)}`);
    }

    return json;
  }

  /**
   * Get list of stations associated with this account.
   */
  async getStationList(): Promise<StationInfo[]> {
    const json = await this.doCall("getStationList");
    const data = json.data || [];
    return data.map((s: any) => ({
      stationCode: s.stationCode,
      stationName: s.stationName,
      capacity: s.capacity || 0,
    }));
  }

  /**
   * Get list of devices for given station codes.
   * devTypeId 17 = Inverter (SUN2000)
   */
  async getDevList(stationCodes: string[]): Promise<DeviceInfo[]> {
    const json = await this.doCall("getDevList", {
      stationCodes: stationCodes.join(","),
    });
    const data = json.data || [];
    return data.map((d: any) => ({
      devId: String(d.devId),
      devName: d.devName || "",
      stationCode: d.stationCode,
      devTypeId: d.devTypeId,
      inverterType: d.inverterType || "",
    }));
  }

  /**
   * Get real-time KPI for inverter devices.
   * Returns active power (W), daily energy (Wh), total cumulative energy (Wh).
   */
  async getDevRealKpi(devIds: string[], devTypeId: number = 17): Promise<InverterRealKpi[]> {
    const json = await this.doCall("getDevRealKpi", {
      devIds: devIds.join(","),
      devTypeId,
    });
    const data = json.data || [];
    return data.map((d: any) => ({
      devId: String(d.devId),
      // Per PyFusionSolarDataRelay: data is in dataItemMap with snake_case keys
      // active_power is in kW, multiply by 1000 for watts
      activePowerW: this.extractNumber(d, ["dataItemMap.active_power", "activePower", "Active_Power"]) * 1000,
      dailyEnergyWh: this.extractNumber(d, ["dataItemMap.daily_energy", "dailyEnergy", "Daily_Energy"]) * 1000,
      totalEnergyWh: this.extractNumber(d, ["dataItemMap.total_energy", "totalEnergy", "Total_Energy"]) * 1000,
    }));
  }

  /**
   * Get historical 5-minute interval KPI for an inverter device.
   * collectTime should be the day you want data for (epoch millis at midnight).
   * Returns array of { timestamp, activePowerW } for each 5-minute interval.
   */
  async getDevHistoryKpi(
    devIds: string[],
    devTypeId: number = 17,
    collectTime: number
  ): Promise<InverterHistoryPoint[]> {
    const json = await this.doCall("getDevHistoryKpi", {
      devIds: devIds.join(","),
      devTypeId,
      collectTime,
    });

    // The response contains a "data" array with time-series points
    const data = json.data || [];
    const points: InverterHistoryPoint[] = [];

    for (const dev of data) {
      // Each device has a "dataList" or "kpi" array with timestamps and values
      const dataList = dev.dataList || dev.kpi || dev.data || [];
      for (const point of dataList) {
        const ts = point.collectTime || point.timestamp || point.time;
        const powerKw = this.extractNumber(point, ["dataItemMap.active_power", "activePower", "Active_Power", "active_power"]);
        if (ts && powerKw !== undefined) {
          points.push({
            timestamp: typeof ts === "number" ? ts : Date.parse(ts),
            activePowerW: powerKw * 1000, // kW → W
          });
        }
      }
    }

    return points;
  }

  /**
   * Get historical hourly KPI for a station (entire day).
   * Returns array of { timestamp, activePowerW }.
   */
  async getKpiStationHour(
    stationCodes: string[],
    collectTime: number
  ): Promise<InverterHistoryPoint[]> {
    const json = await this.doCall("getKpiStationHour", {
      stationCodes: stationCodes.join(","),
      collectTime,
    });

    const data = json.data || [];
    const points: InverterHistoryPoint[] = [];

    for (const station of data) {
      const dataList = station.dataList || station.kpi || station.data || [];
      for (const point of dataList) {
        const ts = point.collectTime || point.timestamp || point.time;
        const powerKw = this.extractNumber(point, ["activePower", "Active_Power", "currentPower"]);
        if (ts && powerKw !== undefined) {
          points.push({
            timestamp: typeof ts === "number" ? ts : Date.parse(ts),
            activePowerW: powerKw * 1000,
          });
        }
      }
    }

    return points;
  }

  /**
   * Try multiple possible field names in a FusionSolar response object.
   * Some API versions use camelCase, others use snake_case or nested maps.
   */
  private extractNumber(obj: any, keys: string[]): number {
    for (const key of keys) {
      if (key.includes(".")) {
        const parts = key.split(".");
        let val = obj;
        for (const part of parts) {
          val = val?.[part];
          if (val === undefined) break;
        }
        if (typeof val === "number") return val;
        if (typeof val === "string" && !isNaN(parseFloat(val))) return parseFloat(val);
      } else {
        if (typeof obj[key] === "number") return obj[key];
        if (typeof obj[key] === "string" && !isNaN(parseFloat(obj[key]))) return parseFloat(obj[key]);
      }
    }
    return 0;
  }
}

/**
 * FusionSolar Kiosk mode client.
 *
 * Much simpler than OpenAPI — no credentials needed, just a kiosk ID (kk).
 * The kiosk URL can be found by:
 *   1. Login to FusionSolar portal
 *   2. Select your plant → Monitoring → Kiosk button
 *   3. Copy the URL — the kk= parameter is the kiosk ID
 *
 * Response structure (HTML-escaped JSON in "data" field):
 *   {
 *     realKpi: { realTimePower, cumulativeEnergy, dailyEnergy, monthEnergy, yearEnergy },  // all in kW
 *     powerCurve: { currentPower, xAxis: [...timestamps], series: [{ name, data: [...] }] }
 *   }
 */
export interface KioskRealKpi {
  activePowerW: number;
  dailyEnergyWh: number;
  totalEnergyWh: number;
  monthEnergyWh: number;
  yearEnergyWh: number;
}

export interface KioskHistoryPoint {
  timestamp: number;
  activePowerW: number;
}

export class FusionSolarKiosk {
  private readonly kioskUrl: string;
  private readonly kkId: string;

  constructor(kkId: string, kioskBaseUrl?: string) {
    this.kkId = kkId;
    this.kioskUrl = kioskBaseUrl || DEFAULT_KIOSK_URL;
  }

  /**
   * Fetch real-time KPI from the kiosk endpoint.
   */
  async fetchRealKpi(): Promise<KioskRealKpi> {
    const url = `${this.kioskUrl}${this.kkId}`;
    const res = await fetch(url, { method: "GET" });

    if (!res.ok) {
      throw new Error(`FusionSolar Kiosk HTTP ${res.status}: ${await res.text()}`);
    }

    const json = await res.json();
    if (!json.data) {
      throw new Error("FusionSolar Kiosk: no 'data' field in response");
    }

    // The data field is HTML-escaped JSON string — unescape and parse
    const unescaped = json.data
      .replace(/&quot;/g, '"')
      .replace(/&#39;/g, "'")
      .replace(/&lt;/g, "<")
      .replace(/&gt;/g, ">")
      .replace(/&amp;/g, "&");
    const data = JSON.parse(unescaped);

    if (!data.realKpi) {
      throw new Error("FusionSolar Kiosk: no 'realKpi' in data");
    }

    const r = data.realKpi;
    return {
      // Values are in kW, convert to W
      activePowerW: parseFloat(r.realTimePower || 0) * 1000,
      dailyEnergyWh: parseFloat(r.dailyEnergy || 0) * 1000,
      totalEnergyWh: parseFloat(r.cumulativeEnergy || 0) * 1000,
      monthEnergyWh: parseFloat(r.monthEnergy || 0) * 1000,
      yearEnergyWh: parseFloat(r.yearEnergy || 0) * 1000,
    };
  }

  /**
   * Fetch the power curve (today's time-series) from the kiosk endpoint.
   * Returns array of { timestamp, activePowerW } for each interval.
   */
  async fetchPowerCurve(): Promise<KioskHistoryPoint[]> {
    const url = `${this.kioskUrl}${this.kkId}`;
    const res = await fetch(url, { method: "GET" });

    if (!res.ok) {
      throw new Error(`FusionSolar Kiosk HTTP ${res.status}: ${await res.text()}`);
    }

    const json = await res.json();
    if (!json.data) {
      throw new Error("FusionSolar Kiosk: no 'data' field in response");
    }

    const unescaped = json.data
      .replace(/&quot;/g, '"')
      .replace(/&#39;/g, "'")
      .replace(/&lt;/g, "<")
      .replace(/&gt;/g, ">")
      .replace(/&amp;/g, "&");
    const data = JSON.parse(unescaped);

    if (!data.powerCurve) {
      throw new Error("FusionSolar Kiosk: no 'powerCurve' in data");
    }

    const pc = data.powerCurve;
    const xAxis: string[] = pc.xAxis || [];
    // powerCurve has series array with name and data (values in kW)
    const series = pc.series || [];
    const powerSeries = series.find((s: any) => s.name === "power" || s.name === "Power" || s.data) || series[0];
    const powerValues: number[] = powerSeries?.data || [];

    const points: KioskHistoryPoint[] = [];
    for (let i = 0; i < xAxis.length && i < powerValues.length; i++) {
      const timeStr = xAxis[i];
      // xAxis entries are typically "HH:MM" or full timestamps
      const today = new Date();
      const [h, m] = timeStr.split(":").map(Number);
      today.setHours(h || 0, m || 0, 0, 0);
      points.push({
        timestamp: today.getTime(),
        activePowerW: Number(powerValues[i] || 0) * 1000, // kW → W
      });
    }

    return points;
  }
}
