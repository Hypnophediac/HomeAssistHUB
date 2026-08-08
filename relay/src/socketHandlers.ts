import { Server, Socket } from "socket.io";
import {
  CommandRequestPayload,
  CommandResponsePayload,
  IceCandidatePayload,
  RegisterPayload,
  SocketData,
  WebRtcAnswerPayload,
  WebRtcOfferPayload,
} from "./types";

/**
 * Registers all Socket.IO event handlers for a single connection.
 * Rooms are keyed by `homeId` so that a Hub and its Clients only ever
 * exchange messages with members of the same home.
 */
export function registerSocketHandlers(io: Server, socket: Socket<any, any, any, SocketData>): void {
  socket.on("register", (payload: RegisterPayload) => {
    if (!payload?.homeId || !payload?.role) {
      socket.emit("error_message", { message: "register requires homeId and role" });
      return;
    }

    socket.data.homeId = payload.homeId;
    socket.data.role = payload.role;
    socket.join(payload.homeId);

    console.log(`[register] socket=${socket.id} home=${payload.homeId} role=${payload.role}`);

    socket.to(payload.homeId).emit("peer_joined", {
      homeId: payload.homeId,
      role: payload.role,
      socketId: socket.id,
    });

    socket.emit("registered", { homeId: payload.homeId, role: payload.role });
  });

  // Client -> Hub
  socket.on("command_request", (payload: CommandRequestPayload) => {
    if (!requireRoom(socket, payload?.homeId)) return;
    console.log(
      `[command_request] home=${payload.homeId} requestId=${payload.requestId} deviceId=${payload.deviceId} action=${payload.action}`
    );
    socket.to(payload.homeId).emit("command_request", payload);
  });

  // Hub -> Client
  socket.on("command_response", (payload: CommandResponsePayload) => {
    if (!requireRoom(socket, payload?.homeId)) return;
    console.log(
      `[command_response] home=${payload.homeId} requestId=${payload.requestId} success=${payload.success} error=${payload.error ?? ""}`
    );
    socket.to(payload.homeId).emit("command_response", payload);
  });

  // Hub -> Client: camera frame (MJPEG proxy stream)
  socket.on("camera_frame", (payload: { homeId?: string; deviceId?: string; frame?: string; timestamp?: number }) => {
    if (!requireRoom(socket, payload?.homeId)) return;
    socket.to(payload.homeId!).emit("camera_frame", payload);
  });

  // WebRTC signaling (bidirectional, forwarded to the rest of the room)
  socket.on("webrtc_offer", (payload: WebRtcOfferPayload) => {
    if (!requireRoom(socket, payload?.homeId)) return;
    socket.to(payload.homeId).emit("webrtc_offer", payload);
  });

  socket.on("webrtc_answer", (payload: WebRtcAnswerPayload) => {
    if (!requireRoom(socket, payload?.homeId)) return;
    socket.to(payload.homeId).emit("webrtc_answer", payload);
  });

  socket.on("ice_candidate", (payload: IceCandidatePayload) => {
    if (!requireRoom(socket, payload?.homeId)) return;
    socket.to(payload.homeId).emit("ice_candidate", payload);
  });

  socket.on("disconnect", (reason) => {
    console.log(`[disconnect] socket=${socket.id} reason=${reason}`);
    if (socket.data.homeId) {
      socket.to(socket.data.homeId).emit("peer_left", {
        homeId: socket.data.homeId,
        role: socket.data.role,
        socketId: socket.id,
      });
    }
  });

  // Keepalive ping to prevent Render free tier spin-down
  socket.on("keepalive", (payload: { homeId?: string; timestamp?: number }) => {
    console.log(`[keepalive] socket=${socket.id} home=${payload?.homeId} ts=${payload?.timestamp}`);
  });
}

function requireRoom(socket: Socket<any, any, any, SocketData>, homeId?: string): boolean {
  if (!homeId || socket.data.homeId !== homeId) {
    socket.emit("error_message", { message: "Not registered to this homeId" });
    return false;
  }
  return true;
}
