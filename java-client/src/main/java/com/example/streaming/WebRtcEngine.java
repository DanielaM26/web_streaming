package com.example.streaming;

public interface WebRtcEngine extends AutoCloseable {
  void initialize();

  void setLocalIceCandidateListener(LocalIceCandidateListener listener);

  SdpAnswer handleRemoteOffer(String remoteSdp) throws Exception;

  void addRemoteIceCandidate(String candidate, String sdpMid, Integer sdpMLineIndex);

  @Override
  void close();

  @FunctionalInterface
  interface LocalIceCandidateListener {
    void onIceCandidate(String candidate, String sdpMid, Integer sdpMLineIndex);
  }
}
