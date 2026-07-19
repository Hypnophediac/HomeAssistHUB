export type Role = "hub" | "client";

export interface RegisterPayload {
  homeId: string;
  role: Role;
  deviceLabel?: string;
}

export interface CommandRequestPayload {
  homeId: string;
  requestId: string;
  deviceId: string;
  action: string;
  params?: Record<string, unknown>;
}

export interface CommandResponsePayload {
  homeId: string;
  requestId: string;
  success: boolean;
  data?: Record<string, unknown>;
  error?: string;
}

export interface WebRtcOfferPayload {
  homeId: string;
  sdp: string;
  from: string;
}

export interface WebRtcAnswerPayload {
  homeId: string;
  sdp: string;
  from: string;
}

export interface IceCandidatePayload {
  homeId: string;
  candidate: unknown;
  from: string;
}

export interface SocketData {
  homeId?: string;
  role?: Role;
}
