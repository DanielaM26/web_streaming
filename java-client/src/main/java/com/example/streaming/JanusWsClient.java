package com.example.streaming;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

public final class JanusWsClient implements AutoCloseable {
  private final ObjectMapper mapper = new ObjectMapper();
  private final HttpClient httpClient = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(8))
      .build();
  private final ConcurrentHashMap<String, CompletableFuture<JsonNode>> txResponses = new ConcurrentHashMap<>();
  private final BlockingQueue<JsonNode> asyncEvents = new LinkedBlockingQueue<>();
  private final URI janusWsUri;

  private volatile WebSocket ws;
  private volatile boolean open;

  public JanusWsClient(URI janusWsUri) {
    this.janusWsUri = Objects.requireNonNull(janusWsUri);
  }

  public void connect() {
    this.ws = httpClient.newWebSocketBuilder()
        .subprotocols("janus-protocol")
        .buildAsync(janusWsUri, new Listener())
        .join();
    this.open = true;
  }

  public JsonNode sendWithTransaction(ObjectNode payload, Duration timeout) throws Exception {
    if (!open || ws == null) {
      throw new IllegalStateException("WebSocket is not connected");
    }
    String tx = transactionId();
    payload.put("transaction", tx);

    CompletableFuture<JsonNode> responseFuture = new CompletableFuture<>();
    txResponses.put(tx, responseFuture);

    String text = mapper.writeValueAsString(payload);
    ws.sendText(text, true).join();

    try {
      return responseFuture.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } finally {
      txResponses.remove(tx);
    }
  }

  public JsonNode waitForEvent(Predicate<JsonNode> filter, Duration timeout) throws Exception {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (true) {
      long remainingNanos = deadline - System.nanoTime();
      if (remainingNanos <= 0) {
        throw new IllegalStateException("Timed out waiting for Janus async event");
      }
      JsonNode event = asyncEvents.poll(remainingNanos, TimeUnit.NANOSECONDS);
      if (event == null) {
        continue;
      }
      if (filter.test(event)) {
        return event;
      }
    }
  }

  public JsonNode pollEvent(Duration timeout) throws InterruptedException {
    return asyncEvents.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
  }

  public ObjectNode object() {
    return mapper.createObjectNode();
  }

  public ObjectMapper mapper() {
    return mapper;
  }

  @Override
  public void close() {
    open = false;
    WebSocket socket = this.ws;
    this.ws = null;
    if (socket != null) {
      socket.sendClose(WebSocket.NORMAL_CLOSURE, "bye").join();
    }
    txResponses.values().forEach(f -> f.completeExceptionally(new IllegalStateException("Janus client closed")));
    txResponses.clear();
    asyncEvents.clear();
  }

  private static String transactionId() {
    return UUID.randomUUID().toString().replace("-", "");
  }

  private final class Listener implements WebSocket.Listener {
    private final StringBuilder partial = new StringBuilder();

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
      partial.append(data);
      if (!last) {
        webSocket.request(1);
        return CompletableFuture.completedFuture(null);
      }

      String messageText = partial.toString();
      partial.setLength(0);

      try {
        JsonNode msg = mapper.readTree(messageText);
        String tx = msg.path("transaction").asText(null);
        if (tx != null) {
          CompletableFuture<JsonNode> txFuture = txResponses.get(tx);
          if (txFuture != null) {
            txFuture.complete(msg);
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
          }
        }
        asyncEvents.offer(msg);
      } catch (Exception e) {
        asyncEvents.offer(mapper.createObjectNode()
            .put("janus", "error")
            .put("error", "JSON parsing failed: " + e.getMessage()));
      }

      webSocket.request(1);
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public void onOpen(WebSocket webSocket) {
      WebSocket.Listener.super.onOpen(webSocket);
      webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
      open = false;
      txResponses.values()
          .forEach(f -> f.completeExceptionally(new IllegalStateException("WebSocket closed: " + reason)));
      return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
      open = false;
      txResponses.values().forEach(f -> f.completeExceptionally(error));
    }
  }
}
