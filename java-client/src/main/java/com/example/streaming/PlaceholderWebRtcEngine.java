package com.example.streaming;

public final class PlaceholderWebRtcEngine implements WebRtcEngine {
  private LocalIceCandidateListener iceCandidateListener;

  @Override
  public void initialize() {
    System.out.println("PlaceholderWebRtcEngine initialized");
  }

  @Override
  public void setLocalIceCandidateListener(LocalIceCandidateListener listener) {
    this.iceCandidateListener = listener;
  }

  @Override
  public SdpAnswer handleRemoteOffer(String remoteSdp) {
    throw new UnsupportedOperationException(
        "Integrate a real WebRTC stack here (webrtc-java/GStreamer)."
            + " Use remote SDP to create peer connection and return local answer SDP.");
  }

  @Override
  public void addRemoteIceCandidate(String candidate, String sdpMid, Integer sdpMLineIndex) {
    System.out.printf("Remote ICE candidate received (ignored by placeholder): %s%n", candidate);
  }

  @Override
  public void close() {
    if (iceCandidateListener != null) {
      iceCandidateListener = null;
    }
  }
}
