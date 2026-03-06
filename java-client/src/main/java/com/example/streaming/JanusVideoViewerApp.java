package com.example.streaming;

import java.net.URI;

public final class JanusVideoViewerApp {
  private JanusVideoViewerApp() {}

  public static void main(String[] args) throws Exception {
    String janusWs = readEnv("JANUS_WS", "ws://localhost:8188");
    long roomId = Long.parseLong(readEnv("JANUS_ROOM", "1234"));
    String viewerName = readEnv("JANUS_DISPLAY", "java-viewer");
    long mappedFeedId = Long.parseLong(readEnvRequired("JANUS_FEED_ID"));

    System.out.printf("Janus WS: %s%n", janusWs);
    System.out.printf("Room: %d, display: %s, mapped feed: %d%n", roomId, viewerName, mappedFeedId);

    try (WebRtcEngine webRtc = new OnvoidWebRtcEngine();
         JanusVideoRoomViewer viewer = new JanusVideoRoomViewer(
             URI.create(janusWs), roomId, viewerName, mappedFeedId, webRtc)) {
      viewer.start();
      System.out.println("Viewer running. Press Ctrl+C to stop.");

      Thread.currentThread().join();
    }
  }

  private static String readEnv(String name, String defaultValue) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) {
      return defaultValue;
    }
    return value;
  }

  private static String readEnvRequired(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Required environment variable is missing: " + name);
    }
    return value;
  }
}
