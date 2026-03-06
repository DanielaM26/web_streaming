# Janus Java WebRTC Viewer Skeleton

This module is a skeleton Java client for your existing Janus VideoRoom flow from `app.js`.
It implements signaling and Janus session lifecycle for a **viewer/subscriber**.

## What is implemented

- WebSocket signaling to Janus (`janus-protocol`)
- WebRTC engine implementation with `webrtc-java` (`OnvoidWebRtcEngine`)
- `create` session
- `attach` to `janus.plugin.videoroom`
- periodic `keepalive`
- explicit mapped feed subscription (`JANUS_FEED_ID`)
- `join` as subscriber with `streams: [{ feed: JANUS_FEED_ID }]`
- wait for async JSEP offer event
- send `start` with local JSEP answer
- send local ICE candidates via `trickle`
- `detach` + `destroy` on shutdown

## Notes

- `PlaceholderWebRtcEngine` remains in the codebase as a fallback/example, but the app now uses
  `OnvoidWebRtcEngine` by default.
- `OnvoidWebRtcEngine` logs remote video frame metadata when frames are received.

## Run

Prerequisites:

- Java 17+
- Maven 3.9+
- Native libraries required by `webrtc-java` for your OS/architecture
- Janus running with WebSocket transport enabled (`ws://localhost:8188`)
- a publisher already streaming in the same room

Command:

```bash
cd /home/daniela/web-streaming/java-client
mvn -q exec:java
```

Environment vars:

- `JANUS_WS` (default `ws://localhost:8188`)
- `JANUS_ROOM` (default `1234`)
- `JANUS_DISPLAY` (default `java-viewer`)
- `JANUS_FEED_ID` (**required**, mapped publisher feed id from your control plane)
