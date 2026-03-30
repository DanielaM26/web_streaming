package com.example.streaming;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class JanusVideoRoomViewer implements AutoCloseable {
  private static final Duration TX_TIMEOUT = Duration.ofSeconds(8);
  private static final Duration WAIT_OFFER_TIMEOUT = Duration.ofSeconds(30);

  private final JanusWsClient janus;
  private final long roomId;
  private final String displayName;
  private final long mappedFeedId;
  private final WebRtcEngine webRtc;
  private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

  private Long sessionId;
  private Long handleId;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "The viewer intentionally holds the caller-provided WebRtcEngine for its lifecycle.")
  public JanusVideoRoomViewer(
      URI janusWsUri, long roomId, String displayName, long mappedFeedId, WebRtcEngine webRtc) {
    this.janus = new JanusWsClient(janusWsUri);
    this.roomId = roomId;
    this.displayName = Objects.requireNonNull(displayName);
    if (mappedFeedId <= 0) {
      throw new IllegalArgumentException("mappedFeedId must be > 0");
    }
    this.mappedFeedId = mappedFeedId;
    this.webRtc = Objects.requireNonNull(webRtc);
  }

  public void start() throws Exception {
    janus.connect();

    sessionId = createSession();
    handleId = attachVideoRoomPlugin();

    startKeepalive();
    webRtc.initialize();
    webRtc.setLocalIceCandidateListener(this::sendLocalIceCandidate);

    joinAsSubscriber(mappedFeedId);

    JsonNode offerEvent = waitForPluginEventWithJsep();
    JsonNode remoteJsep = offerEvent.path("jsep");
    String remoteSdp = remoteJsep.path("sdp").asText();
    SdpAnswer answer = webRtc.handleRemoteOffer(remoteSdp);
    sendStart(answer);
    startAsyncEventLoop();

    System.out.printf("Viewer attached to mapped feed %d. WebRTC answer sent.%n", mappedFeedId);
  }

  @Override
  public void close() {
    scheduler.shutdownNow();

    try {
      if (sessionId != null && handleId != null) {
        ObjectNode detach = baseJanus("detach");
        janus.sendWithTransaction(detach, TX_TIMEOUT);
      }
    } catch (Exception e) {
      System.err.println("Detach during shutdown failed: " + e.getMessage());
    }

    try {
      if (sessionId != null) {
        ObjectNode destroy = baseJanus("destroy");
        janus.sendWithTransaction(destroy, TX_TIMEOUT);
      }
    } catch (Exception e) {
      System.err.println("Destroy during shutdown failed: " + e.getMessage());
    }

    webRtc.close();
    janus.close();
  }

  private long createSession() throws Exception {
    ObjectNode req = janus.object();
    req.put("janus", "create");

    JsonNode res = janus.sendWithTransaction(req, TX_TIMEOUT);
    checkNotError(res, "create session");
    long id = res.path("data").path("id").asLong();
    if (id <= 0) {
      throw new IllegalStateException("Janus create response missing session id: " + res);
    }
    System.out.printf("Janus session created: %d%n", id);
    return id;
  }

  private long attachVideoRoomPlugin() throws Exception {
    ObjectNode req = baseJanus("attach");
    req.put("plugin", "janus.plugin.videoroom");

    JsonNode res = janus.sendWithTransaction(req, TX_TIMEOUT);
    checkNotError(res, "attach plugin");
    long id = res.path("data").path("id").asLong();
    if (id <= 0) {
      throw new IllegalStateException("Janus attach response missing handle id: " + res);
    }
    System.out.printf("VideoRoom handle attached: %d%n", id);
    return id;
  }

  private void startKeepalive() {
    scheduler.scheduleAtFixedRate(() -> {
      if (sessionId == null) {
        return;
      }
      try {
        ObjectNode keepalive = janus.object();
        keepalive.put("janus", "keepalive");
        keepalive.put("session_id", sessionId);
        janus.sendWithTransaction(keepalive, TX_TIMEOUT);
      } catch (Exception e) {
        System.err.println("Keepalive failed: " + e.getMessage());
      }
    }, 25, 25, TimeUnit.SECONDS);
  }

  private void startAsyncEventLoop() {
    scheduler.scheduleWithFixedDelay(() -> {
      try {
        JsonNode event = janus.pollEvent(Duration.ofMillis(500));
        if (event == null) {
          return;
        }
        handleAsyncEvent(event);
      } catch (Exception e) {
        System.err.println("Async event loop error: " + e.getMessage());
      }
    }, 0, 200, TimeUnit.MILLISECONDS);
  }

  private void joinAsSubscriber(long feedId) throws Exception {
    ObjectNode body = janus.object();
    body.put("request", "join");
    body.put("room", roomId);
    body.put("ptype", "subscriber");
    body.put("display", displayName);
    body.put("feed", feedId);

    ArrayNode streams = janus.object().arrayNode();
    ObjectNode stream = janus.object();
    stream.put("feed", feedId);
    streams.add(stream);
    body.set("streams", streams);

    pluginRequest(body);
    System.out.printf("Join subscriber request sent for mapped feed %d.%n", feedId);
  }

  private JsonNode waitForPluginEventWithJsep() throws Exception {
    return janus.waitForEvent(event -> {
      if (!"event".equals(event.path("janus").asText())) {
        return false;
      }
      if (event.path("sender").asLong() != handleId) {
        return false;
      }
      if (!event.path("jsep").isObject()) {
        return false;
      }
      String type = event.path("jsep").path("type").asText();
      return "offer".equalsIgnoreCase(type);
    }, WAIT_OFFER_TIMEOUT);
  }

  private void sendStart(SdpAnswer answer) throws Exception {
    ObjectNode body = janus.object();
    body.put("request", "start");
    body.put("room", roomId);

    ObjectNode jsep = janus.object();
    jsep.put("type", answer.type());
    jsep.put("sdp", answer.sdp());

    pluginRequest(body, jsep);
  }

  private void sendLocalIceCandidate(String candidate, String sdpMid, Integer sdpMLineIndex) {
    try {
      ObjectNode trickle = baseJanus("trickle");
      ObjectNode c = janus.object();
      c.put("candidate", candidate);
      if (sdpMid != null) {
        c.put("sdpMid", sdpMid);
      }
      if (sdpMLineIndex != null) {
        c.put("sdpMLineIndex", sdpMLineIndex);
      }
      trickle.set("candidate", c);

      janus.sendWithTransaction(trickle, TX_TIMEOUT);
    } catch (Exception e) {
      System.err.println("Failed to send local ICE candidate: " + e.getMessage());
    }
  }

  private void handleAsyncEvent(JsonNode event) {
    String janusType = event.path("janus").asText("");

    if ("trickle".equals(janusType)) {
      JsonNode candidate = event.path("candidate");
      String c = candidate.path("candidate").asText(null);
      if (c != null && !c.isBlank()) {
        String mid = candidate.has("sdpMid") ? candidate.path("sdpMid").asText() : null;
        Integer mline = candidate.has("sdpMLineIndex") ? candidate.path("sdpMLineIndex").asInt() : null;
        webRtc.addRemoteIceCandidate(c, mid, mline);
      }
      return;
    }

    if ("event".equals(janusType)) {
      String vrEvent = event.path("plugindata").path("data").path("videoroom").asText("");
      if (!vrEvent.isBlank()) {
        System.out.printf("VideoRoom async event: %s%n", vrEvent);
      }
      return;
    }

    if ("webrtcup".equals(janusType)) {
      System.out.println("Janus reports WebRTC up.");
      return;
    }

    if ("hangup".equals(janusType)) {
      System.err.println("Janus hangup event: " + event);
      return;
    }

    if (!"ack".equals(janusType)) {
      System.out.println("Unhandled async Janus event: " + event);
    }
  }

  private JsonNode pluginRequest(ObjectNode body) throws Exception {
    return pluginRequest(body, null);
  }

  private JsonNode pluginRequest(ObjectNode body, ObjectNode jsep) throws Exception {
    ObjectNode req = baseJanus("message");
    req.put("handle_id", handleId);
    req.set("body", body);
    if (jsep != null) {
      req.set("jsep", jsep);
    }

    JsonNode res = janus.sendWithTransaction(req, TX_TIMEOUT);
    checkNotError(res, "plugin request");
    return res;
  }

  private ObjectNode baseJanus(String janusAction) {
    if (sessionId == null && !"create".equals(janusAction)) {
      throw new IllegalStateException("sessionId not initialized");
    }
    ObjectNode req = janus.object();
    req.put("janus", janusAction);
    if (sessionId != null) {
      req.put("session_id", sessionId);
    }
    return req;
  }

  private static void checkNotError(JsonNode res, String operation) {
    if ("error".equals(res.path("janus").asText())) {
      throw new IllegalStateException("Janus " + operation + " failed: " + res);
    }
  }
}
