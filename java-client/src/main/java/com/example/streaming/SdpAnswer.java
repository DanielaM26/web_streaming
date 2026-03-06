package com.example.streaming;

public record SdpAnswer(String type, String sdp) {
  public SdpAnswer {
    if (type == null || type.isBlank()) {
      throw new IllegalArgumentException("type is required");
    }
    if (sdp == null || sdp.isBlank()) {
      throw new IllegalArgumentException("sdp is required");
    }
  }

  public static SdpAnswer answer(String sdp) {
    return new SdpAnswer("answer", sdp);
  }
}
