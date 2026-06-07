// Minimal Janus VideoRoom streaming without janus.js (raw WebSocket API)

let ws;
let sessionId = null;
let handleId = null;

let pc = null;
let localStream = null;
let publisherFeedId = null;

let role = null; // "publisher" | "viewer"
let room = 1234;
let displayName = "alice";
let janusWsUrl = "ws://localhost:8188";

let keepaliveTimer = null;
let viewerPollTimer = null;
let viewerJoinSent = false;
let viewerMonitorTimer = null;
let currentViewerFeedId = null;
let appStatus = "idle";
let reconnectBadgeTimer = null;
let stopRequested = false;
let startInProgress = false;
const BG_PRESET_STORAGE_KEY = "janus.bgPreset";
const DEFAULT_BG_PRESET = "default";
const ALLOWED_BG_PRESETS = new Set(["default", "blur", "aurora", "warm"]);

const logEl = document.getElementById("log");
const statusBadgeEl = document.getElementById("statusBadge");
const userMessageEl = document.getElementById("userMessage");
const nameEl = document.getElementById("name");
const roomEl = document.getElementById("room");
const janusWsEl = document.getElementById("janusWs");
const btnPublisherEl = document.getElementById("btnPublisher");
const btnViewerEl = document.getElementById("btnViewer");
const btnStopEl = document.getElementById("btnStop");
const STATUS_LABELS = {
  idle: "Idle",
  connecting: "Connecting",
  connected: "Connected",
  publishing: "Publishing",
  viewer_waiting: "Viewer waiting",
  reconnecting: "Reconnecting",
  error: "Error",
};
const bgPresetEl = document.getElementById("bgPreset");
function log(...args) {
  const line = args.map(a => (typeof a === "string" ? a : JSON.stringify(a))).join(" ");
  console.log(line);
  logEl.textContent += line + "\n";
  logEl.scrollTop = logEl.scrollHeight;
}

function setStatus(status, labelOverride) {
  appStatus = status;
  statusBadgeEl.dataset.status = status;
  statusBadgeEl.textContent = labelOverride || STATUS_LABELS[status] || status;
  updateControls();
}

function showUserMessage(message) {
  userMessageEl.textContent = message;
  userMessageEl.classList.add("show");
}

function clearUserMessage() {
  userMessageEl.textContent = "";
  userMessageEl.classList.remove("show");
}

function updateControls() {
  const active = role !== null || startInProgress || appStatus === "connecting" || appStatus === "reconnecting";
  btnPublisherEl.disabled = active;
  btnViewerEl.disabled = active;
  btnStopEl.disabled = !active;
  nameEl.disabled = active;
  roomEl.disabled = active;
  janusWsEl.disabled = active;
}

function errorText(err) {
  if (err?.error?.reason) return String(err.error.reason);
  if (err?.message) return String(err.message);
  return String(err || "");
}

function toFriendlyError(err, fallback) {
  const text = errorText(err);
  if (text.includes("NotAllowedError") || text.includes("Permission denied")) {
    return "Camera/microphone access is blocked. Please allow permissions and try again.";
  }
  if (text.includes("NotFoundError") || text.includes("Requested device not found")) {
    return "No camera or microphone was found on this device.";
  }
  if (text.includes("WS open timeout") || text.includes("WS not open")) {
    return "Could not connect to Janus. Check server address and if Janus is running.";
  }
  if (text.includes("Invalid room")) {
    return "Room must be a positive number.";
  }
  if (text.includes("No such room") || text.includes("does not exist")) {
    return "The room is not available right now. Verify the room ID and try again.";
  }
  if (text.includes("No such feed") || text.includes("No live publisher") || text.includes("feed")) {
    return "The live stream is unavailable right now. Please wait for a publisher.";
  }
  if (text.includes("A publisher is already live")) {
    return "Another publisher is already streaming in this room.";
  }
  return fallback || "Something went wrong. Please try again.";
}

function clearReconnectBadgeTimer() {
  if (reconnectBadgeTimer) {
    clearTimeout(reconnectBadgeTimer);
    reconnectBadgeTimer = null;
  }
}

function handleUnexpectedDisconnect() {
  stopKeepalive();
  stopViewerPoll();
  stopViewerMonitor();
  viewerJoinSent = false;
  currentViewerFeedId = null;

  try { if (pc) pc.close(); } catch {}
  pc = null;
  if (localStream) {
    localStream.getTracks().forEach((t) => t.stop());
    localStream = null;
  }
  document.getElementById("local").srcObject = null;
  document.getElementById("remote").srcObject = null;

  role = null;
  sessionId = null;
  handleId = null;
  publisherFeedId = null;

  setStatus("reconnecting");
  showUserMessage("Connection was interrupted. Reconnect and press Start again.");
  clearReconnectBadgeTimer();
  reconnectBadgeTimer = setTimeout(() => {
    if (appStatus === "reconnecting") {
      setStatus("idle");
    }
  }, 1800);
}

function txid() {
  return Math.random().toString(36).slice(2) + Date.now().toString(36);
}

function normalizeBackgroundPreset(preset) {
  if (ALLOWED_BG_PRESETS.has(preset)) {
    return preset;
  }
  return DEFAULT_BG_PRESET;
}

function persistBackgroundPreset(preset) {
  try {
    localStorage.setItem(BG_PRESET_STORAGE_KEY, preset);
  } catch {
    // Ignore storage errors in private browsing or restricted contexts.
  }
}

function applyBackgroundPreset(preset) {
  const normalized = normalizeBackgroundPreset(preset);
  document.body.setAttribute("data-bg-preset", normalized);
  bgPresetEl.value = normalized;
  persistBackgroundPreset(normalized);
  log(`Background style: ${normalized}`);
}

function loadSavedBackgroundPreset() {
  let saved = DEFAULT_BG_PRESET;
  try {
    saved = localStorage.getItem(BG_PRESET_STORAGE_KEY) || DEFAULT_BG_PRESET;
  } catch {
    saved = DEFAULT_BG_PRESET;
  }
  applyBackgroundPreset(saved);
}

function sendJanus(msg) {
  if (!ws || ws.readyState !== WebSocket.OPEN) throw new Error("WS not open");
  ws.send(JSON.stringify(msg));
}

function startKeepalive() {
  stopKeepalive();
  keepaliveTimer = setInterval(() => {
    if (!sessionId) return;
    sendJanus({ janus: "keepalive", session_id: sessionId, transaction: txid() });
  }, 25000);
}

function stopKeepalive() {
  if (keepaliveTimer) clearInterval(keepaliveTimer);
  keepaliveTimer = null;
}

function stopViewerPoll() {
  if (viewerPollTimer) clearInterval(viewerPollTimer);
  viewerPollTimer = null;
}

function stopViewerMonitor() {
  if (viewerMonitorTimer) clearInterval(viewerMonitorTimer);
  viewerMonitorTimer = null;
}

async function createSession() {
  return new Promise((resolve, reject) => {
    const t = txid();
    const onMsg = (evt) => {
      const msg = JSON.parse(evt.data);
      if (msg.transaction !== t) return;
      ws.removeEventListener("message", onMsg);
      if (msg.janus !== "success") return reject(msg);
      sessionId = msg.data.id;
      log("Janus session:", sessionId);
      resolve();
    };
    ws.addEventListener("message", onMsg);
    sendJanus({ janus: "create", transaction: t });
  });
}

async function attachVideoRoom() {
  return new Promise((resolve, reject) => {
    const t = txid();
    const onMsg = (evt) => {
      const msg = JSON.parse(evt.data);
      if (msg.transaction !== t) return;
      ws.removeEventListener("message", onMsg);
      if (msg.janus !== "success") return reject(msg);
      handleId = msg.data.id;
      log("Attached handle:", handleId);
      resolve();
    };
    ws.addEventListener("message", onMsg);
    sendJanus({
      janus: "attach",
      plugin: "janus.plugin.videoroom",
      session_id: sessionId,
      transaction: t,
    });
  });
}

async function reattachVideoRoomHandle() {
  const oldHandleId = handleId;
  if (oldHandleId && sessionId) {
    try {
      sendJanus({
        janus: "detach",
        session_id: sessionId,
        handle_id: oldHandleId,
        transaction: txid(),
      });
    } catch {}
  }
  await attachVideoRoom();
}

async function pluginRequest(body, jsep = null) {
  return new Promise((resolve, reject) => {
    const t = txid();
    const onMsg = (evt) => {
      const msg = JSON.parse(evt.data);

      // async events come here too; we resolve only by transaction match
      if (msg.transaction !== t) return;

      ws.removeEventListener("message", onMsg);

      if (msg.janus === "ack") {
        // Janus may ack first; we need to wait for the event without tx match.
        // To keep minimal: we don't rely on ack flows here.
        return reject({ error: "Received ack; enable full event handling if needed", msg });
      }

      if (msg.janus === "error") return reject(msg);

      resolve(msg);
    };

    ws.addEventListener("message", onMsg);

    const payload = {
      janus: "message",
      session_id: sessionId,
      handle_id: handleId,
      transaction: t,
      body,
    };
    if (jsep) payload.jsep = jsep;

    sendJanus(payload);
  });
}

function setupGlobalMessageHandler() {
  ws.addEventListener("message", async (evt) => {
    const msg = JSON.parse(evt.data);

    if (msg.janus === "error") {
      log("Janus error:", msg.error?.code, msg.error?.reason || msg.error);
      setStatus("error");
      showUserMessage(toFriendlyError(msg, "Janus returned an error."));
      return;
    }

    // important async event channel:
    if (msg.janus === "event" && msg.sender === handleId) {
      // handle plugin event + possible jsep
      if (msg.plugindata?.plugin === "janus.plugin.videoroom") {
        const data = msg.plugindata.data;
        if (data?.error || data?.error_code) {
          log("VideoRoom error:", data.error_code, data.error || data);
          if (role === "viewer" && (data.error_code === 428 || data.error_code === 425)) {
            // Feed disappeared/changed or stale subscriber state; recover and retry.
            setStatus("reconnecting");
            showUserMessage("Stream ended. Waiting for a new live stream...");
            viewerJoinSent = false;
            currentViewerFeedId = null;
            resetViewerPlaybackState();
            await reattachVideoRoomHandle();
            startViewerAutoJoin();
          } else {
            setStatus("error");
            showUserMessage(toFriendlyError(data.error || data, "Room request failed."));
          }
          return;
        }

        if (role === "publisher") {
          if (data.videoroom === "joined") {
            publisherFeedId = data.id || null;
            log("Publisher joined room", data.room, "id:", data.id);
            await publishLocalStream();
          } else if (data.videoroom === "event") {
            // nothing special for MVP
          }
          if (msg.jsep && msg.jsep.type === "answer") {
            await handlePublisherAnswer(msg.jsep);
          }
        }

        if (role === "viewer") {
          if (data?.videoroom) {
            log("Viewer event:", data.videoroom);
          }
          if (data?.unpublished || data?.leaving) {
            log("Live stream ended. Waiting for next publisher...");
            setStatus("viewer_waiting");
            showUserMessage("Feed ended. Waiting for the publisher to come back.");
            resetViewerPlaybackState();
            await reattachVideoRoomHandle();
            startViewerAutoJoin();
            return;
          }
          // When we join as subscriber, Janus sends an offer (jsep) to us.
          if (msg.jsep && (data.videoroom === "attached" || data.videoroom === "event")) {
            log("Viewer got offer from Janus");
            await handleSubscriberOffer(msg.jsep);
          }
        }
      }
    }

    if (msg.janus === "hangup" && role === "viewer") {
      log("Viewer hangup:", msg.reason || "stream ended");
      setStatus("viewer_waiting");
      showUserMessage("Feed ended. Waiting for a live publisher.");
      resetViewerPlaybackState();
      await reattachVideoRoomHandle();
      startViewerAutoJoin();
      return;
    }

    // trickle ack
    if (msg.janus === "ack") return;
  });
}

function createPeerConnection() {
  // TURN is optional in dev; keep config empty if it causes issues.
  const iceServers = [
    // STUN/TURN local (if you run coturn)
    // { urls: "stun:localhost:3478" },
    // { urls: "turn:localhost:3478?transport=udp", username: "demo", credential: "demo" },
  ];

  pc = new RTCPeerConnection({ iceServers });

  pc.onicecandidate = (e) => {
    if (!sessionId || !handleId) return;
    if (e.candidate) {
      sendJanus({
        janus: "trickle",
        session_id: sessionId,
        handle_id: handleId,
        transaction: txid(),
        candidate: e.candidate,
      });
    } else {
      // end-of-candidates
      sendJanus({
        janus: "trickle",
        session_id: sessionId,
        handle_id: handleId,
        transaction: txid(),
        candidate: { completed: true },
      });
    }
  };

  pc.ontrack = (e) => {
    const remote = document.getElementById("remote");
    if (remote.srcObject !== e.streams[0]) {
      remote.srcObject = e.streams[0];
      log("Remote stream attached");
      clearUserMessage();
      setStatus("connected");
    }
  };
}

async function ensureRoomExists() {
  // We try "exists". If false, create.
  const t = txid();
  const existsReq = {
    janus: "message",
    session_id: sessionId,
    handle_id: handleId,
    transaction: t,
    body: { request: "exists", room },
  };

  const res = await new Promise((resolve, reject) => {
    const onMsg = (evt) => {
      const msg = JSON.parse(evt.data);
      if (msg.transaction !== t) return;
      ws.removeEventListener("message", onMsg);
      if (msg.janus !== "success" && msg.janus !== "event") return reject(msg);
      resolve(msg);
    };
    ws.addEventListener("message", onMsg);
    sendJanus(existsReq);
  });

  const exists = !!res.plugindata?.data?.exists;
  log("Room exists:", exists);

  if (exists) return;

  // create room
  const t2 = txid();
  const createReq = {
    janus: "message",
    session_id: sessionId,
    handle_id: handleId,
    transaction: t2,
    body: {
      request: "create",
      room,
      publishers: 1,
      record: false,
      bitrate: 1024 * 1024, // 1 Mbps (optional)
      fir_freq: 10,
      notify_joining: true,
    },
  };

  await new Promise((resolve, reject) => {
    const onMsg = (evt) => {
      const msg = JSON.parse(evt.data);
      if (msg.transaction !== t2) return;
      ws.removeEventListener("message", onMsg);
      if (msg.janus === "error") return reject(msg);
      log("Room created:", room);
      resolve();
    };
    ws.addEventListener("message", onMsg);
    sendJanus(createReq);
  });
}

async function listParticipants() {
  const res = await pluginRequest({ request: "listparticipants", room });
  return res.plugindata?.data?.participants || [];
}

function findLivePublisherFeed(participants) {
  // Keep only entries that look like real publishers.
  const pubs = participants.filter((p) =>
    p.publisher === true ||
    !!p.videocodec ||
    !!p.audiocodec ||
    (Array.isArray(p.streams) && p.streams.length > 0)
  );
  return pubs[0]?.id || null;
}

async function ensureNoExistingPublisher() {
  const participants = await listParticipants();
  const feedId = findLivePublisherFeed(participants);
  if (feedId) {
    throw new Error(`A publisher is already live in room ${room} (feed ${feedId})`);
  }
}

async function joinAsPublisher() {
  await ensureRoomExists();
  await ensureNoExistingPublisher();

  sendJanus({
    janus: "message",
    session_id: sessionId,
    handle_id: handleId,
    transaction: txid(),
    body: { request: "join", room, ptype: "publisher", display: displayName },
  });
}

async function publishLocalStream() {
  if (!pc) createPeerConnection();

  localStream = await navigator.mediaDevices.getUserMedia({ video: true, audio: true });
  document.getElementById("local").srcObject = localStream;
  localStream.getTracks().forEach((t) => pc.addTrack(t, localStream));

  const offer = await pc.createOffer();
  await pc.setLocalDescription(offer);

  sendJanus({
    janus: "message",
    session_id: sessionId,
    handle_id: handleId,
    transaction: txid(),
    body: { request: "configure", audio: true, video: true },
    jsep: offer,
  });

  log("Publishing...");
  clearUserMessage();
  setStatus("publishing");
}

async function handlePublisherAnswer(jsepAnswer) {
  if (!pc) return;
  await pc.setRemoteDescription(jsepAnswer);
  log("Publisher got SDP answer from Janus");
}

async function joinAsViewer() {
  await ensureRoomExists();
  if (!pc) createPeerConnection();
  startViewerAutoJoin();
}

async function tryJoinLiveFeed() {
  if (role !== "viewer" || viewerJoinSent) return;
  const participants = await listParticipants();
  const feedId = findLivePublisherFeed(participants);
  if (!feedId) return;

  viewerJoinSent = true;
  currentViewerFeedId = feedId;
  stopViewerPoll();
  log("Viewer joining live feed:", feedId);
  sendJanus({
    janus: "message",
    session_id: sessionId,
    handle_id: handleId,
    transaction: txid(),
    body: {
      request: "join",
      room,
      ptype: "subscriber",
      // Legacy field for older Janus versions.
      feed: feedId,
      // Multistream field for newer Janus versions.
      streams: [{ feed: feedId }],
    },
  });
}

function startViewerAutoJoin() {
  viewerJoinSent = false;
  currentViewerFeedId = null;
  stopViewerPoll();
  startViewerMonitor();
  log("Viewer waiting for live publisher...");
  setStatus("viewer_waiting");
  const run = async () => {
    try {
      await tryJoinLiveFeed();
    } catch (e) {
      const reason = e?.error?.reason || e?.message || e;
      log("Viewer auto-join retry:", reason);
      if (String(reason).includes("Already in as a subscriber on this handle")) {
        resetViewerPlaybackState();
        await reattachVideoRoomHandle();
      }
    }
  };
  run();
  viewerPollTimer = setInterval(run, 1500);
}

function startViewerMonitor() {
  if (viewerMonitorTimer) return;
  viewerMonitorTimer = setInterval(async () => {
    if (role !== "viewer" || !sessionId || !handleId) return;
    try {
      const participants = await listParticipants();
      const liveFeedId = findLivePublisherFeed(participants);

      if (!viewerJoinSent) return;

      if (!liveFeedId) {
        log("No live publisher. Waiting...");
        resetViewerPlaybackState();
        startViewerAutoJoin();
        return;
      }

      if (currentViewerFeedId && liveFeedId !== currentViewerFeedId) {
        log("Publisher changed. Rejoining live stream...");
        resetViewerPlaybackState();
        startViewerAutoJoin();
      }
    } catch (e) {
      log("Viewer monitor retry:", e?.error?.reason || e?.message || e);
    }
  }, 2000);
}

function resetViewerPlaybackState() {
  const remote = document.getElementById("remote");
  remote.srcObject = null;
  try { if (pc) pc.close(); } catch {}
  pc = null;
  createPeerConnection();
}

async function handleSubscriberOffer(jsepOffer) {
  await pc.setRemoteDescription(jsepOffer);
  const answer = await pc.createAnswer();
  await pc.setLocalDescription(answer);

  sendJanus({
    janus: "message",
    session_id: sessionId,
    handle_id: handleId,
    transaction: txid(),
    body: { request: "start", room },
    jsep: answer,
  });

  log("Viewer started");
  clearUserMessage();
  setStatus("connected");
}

async function start(selectedRole) {
  if (startInProgress || role !== null) {
    return;
  }

  startInProgress = true;
  stopRequested = false;
  clearReconnectBadgeTimer();
  clearUserMessage();
  setStatus("connecting", selectedRole === "publisher" ? "Connecting publisher" : "Connecting viewer");
  logEl.textContent = "";
  log("Starting role:", selectedRole);

  try {
    role = selectedRole;
    displayName = nameEl.value.trim() || "user";
    room = parseInt(roomEl.value.trim() || "1234", 10);
    if (!Number.isFinite(room) || room <= 0) {
      throw new Error("Invalid room");
    }
    janusWsUrl = janusWsEl.value.trim() || "ws://localhost:8188";

    ws = new WebSocket(janusWsUrl, "janus-protocol");

    ws.onerror = (e) => {
      log("WS error", e);
      if (!stopRequested) {
        showUserMessage("Connection issue detected. Trying to recover...");
      }
    };
    ws.onclose = () => {
      log("WS closed");
      if (!stopRequested && role !== null) {
        handleUnexpectedDisconnect();
      }
    };

    await new Promise((resolve, reject) => {
      ws.onopen = resolve;
      setTimeout(() => reject(new Error("WS open timeout")), 8000);
    });

    log("WS connected:", janusWsUrl);
    setStatus("connected");

    setupGlobalMessageHandler();
    await createSession();
    startKeepalive();
    await attachVideoRoom();

    if (role === "publisher") await joinAsPublisher();
    if (role === "viewer") await joinAsViewer();
  } catch (e) {
    log("Start failed:", e?.error?.reason || e?.message || e);
    showUserMessage(toFriendlyError(e, "Could not start streaming. Please try again."));
    setStatus("error");
    await stopAll({ preserveMessage: true, preserveStatus: true });
  } finally {
    startInProgress = false;
    updateControls();
  }
}

async function stopAll(options = {}) {
  const preserveMessage = !!options.preserveMessage;
  const preserveStatus = !!options.preserveStatus;
  stopRequested = true;
  clearReconnectBadgeTimer();
  log("Stopping...");
  const currentRole = role;
  stopKeepalive();
  stopViewerPoll();
  stopViewerMonitor();
  viewerJoinSent = false;
  currentViewerFeedId = null;

  try { if (pc) pc.close(); } catch {}
  pc = null;

  if (localStream) {
    localStream.getTracks().forEach((t) => t.stop());
    localStream = null;
  }

  document.getElementById("local").srcObject = null;
  document.getElementById("remote").srcObject = null;

  if (ws && ws.readyState === WebSocket.OPEN) {
    try {
      if (currentRole === "publisher" && publisherFeedId) {
        sendJanus({
          janus: "message",
          session_id: sessionId,
          handle_id: handleId,
          transaction: txid(),
          body: { request: "unpublish" },
        });
      }
      if (handleId) sendJanus({ janus: "detach", session_id: sessionId, handle_id: handleId, transaction: txid() });
      if (sessionId) sendJanus({ janus: "destroy", session_id: sessionId, transaction: txid() });
    } catch {}
  }

  try { ws?.close(); } catch {}
  ws = null;
  sessionId = null;
  handleId = null;
  publisherFeedId = null;
  role = null;
  if (!preserveMessage) {
    clearUserMessage();
  }
  if (!preserveStatus) {
    setStatus("idle");
  } else {
    updateControls();
  }
}

btnPublisherEl.onclick = () => start("publisher");
btnViewerEl.onclick = () => start("viewer");
btnStopEl.onclick = () => stopAll();
setStatus("idle");
bgPresetEl.addEventListener("change", () => applyBackgroundPreset(bgPresetEl.value));
loadSavedBackgroundPreset();
