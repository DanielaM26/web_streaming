package com.example.streaming;

import dev.onvoid.webrtc.CreateSessionDescriptionObserver;
import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.PeerConnectionObserver;
import dev.onvoid.webrtc.RTCAnswerOptions;
import dev.onvoid.webrtc.RTCConfiguration;
import dev.onvoid.webrtc.RTCIceCandidate;
import dev.onvoid.webrtc.RTCIceConnectionState;
import dev.onvoid.webrtc.RTCIceGatheringState;
import dev.onvoid.webrtc.RTCPeerConnection;
import dev.onvoid.webrtc.RTCPeerConnectionState;
import dev.onvoid.webrtc.RTCRtpReceiver;
import dev.onvoid.webrtc.RTCRtpTransceiver;
import dev.onvoid.webrtc.RTCSessionDescription;
import dev.onvoid.webrtc.RTCSignalingState;
import dev.onvoid.webrtc.RTCSdpType;
import dev.onvoid.webrtc.SetSessionDescriptionObserver;
import dev.onvoid.webrtc.media.MediaStreamTrack;
import dev.onvoid.webrtc.media.video.VideoTrack;
import dev.onvoid.webrtc.media.video.VideoTrackSink;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class OnvoidWebRtcEngine implements WebRtcEngine {
  private static final long SDP_TIMEOUT_MS = 10_000;

  private PeerConnectionFactory factory;
  private RTCPeerConnection peerConnection;
  private LocalIceCandidateListener iceCandidateListener;

  private final List<VideoTrack> remoteVideoTracks = new ArrayList<>();
  private final List<VideoTrackSink> remoteSinks = new ArrayList<>();

  @Override
  public synchronized void initialize() {
    if (peerConnection != null) {
      return;
    }

    factory = new PeerConnectionFactory();
    RTCConfiguration config = new RTCConfiguration();
    peerConnection = factory.createPeerConnection(config, new PeerConnectionObserver() {
      @Override
      public void onIceCandidate(RTCIceCandidate candidate) {
        LocalIceCandidateListener listener = iceCandidateListener;
        if (listener != null && candidate != null) {
          listener.onIceCandidate(candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex);
        }
      }

      @Override
      public void onIceConnectionChange(RTCIceConnectionState state) {
        System.out.printf("ICE connection state: %s%n", state);
      }

      @Override
      public void onIceGatheringChange(RTCIceGatheringState state) {
        System.out.printf("ICE gathering state: %s%n", state);
      }

      @Override
      public void onConnectionChange(RTCPeerConnectionState state) {
        System.out.printf("Peer connection state: %s%n", state);
      }

      @Override
      public void onSignalingChange(RTCSignalingState state) {
        System.out.printf("Signaling state: %s%n", state);
      }

      @Override
      public void onTrack(RTCRtpTransceiver transceiver) {
        RTCRtpReceiver receiver = transceiver.getReceiver();
        if (receiver == null) {
          return;
        }

        MediaStreamTrack track = receiver.getTrack();
        if (track == null) {
          return;
        }

        if (!Objects.equals(track.getKind(), MediaStreamTrack.VIDEO_TRACK_KIND)) {
          return;
        }

        if (!(track instanceof VideoTrack videoTrack)) {
          return;
        }

        // Keep a sink attached so frames are consumed and can be observed in logs.
        VideoTrackSink sink = frame -> System.out.printf(
            "Remote video frame: %dx%d ts=%d%n",
            frame.getBuffer().getWidth(),
            frame.getBuffer().getHeight(),
            frame.getTimestampNs());

        synchronized (OnvoidWebRtcEngine.this) {
          remoteVideoTracks.add(videoTrack);
          remoteSinks.add(sink);
        }
        videoTrack.addSink(sink);
        System.out.println("Remote video track attached.");
      }
    });
  }

  @Override
  public synchronized void setLocalIceCandidateListener(LocalIceCandidateListener listener) {
    this.iceCandidateListener = listener;
  }

  @Override
  public synchronized SdpAnswer handleRemoteOffer(String remoteSdp) throws Exception {
    if (peerConnection == null) {
      throw new IllegalStateException("initialize() must be called before handleRemoteOffer()");
    }
    if (remoteSdp == null || remoteSdp.isBlank()) {
      throw new IllegalArgumentException("remoteSdp is required");
    }

    RTCSessionDescription remoteOffer = new RTCSessionDescription(RTCSdpType.OFFER, remoteSdp);
    setRemoteDescription(remoteOffer);

    RTCSessionDescription answer = createAnswer();
    setLocalDescription(answer);

    return SdpAnswer.answer(answer.sdp);
  }

  @Override
  public synchronized void addRemoteIceCandidate(String candidate, String sdpMid, Integer sdpMLineIndex) {
    if (peerConnection == null) {
      throw new IllegalStateException("initialize() must be called before addRemoteIceCandidate()");
    }
    if (candidate == null || candidate.isBlank()) {
      return;
    }

    RTCIceCandidate rtcCandidate = new RTCIceCandidate(
        sdpMid == null ? "" : sdpMid,
        sdpMLineIndex == null ? 0 : sdpMLineIndex,
        candidate);
    peerConnection.addIceCandidate(rtcCandidate);
  }

  @Override
  public synchronized void close() {
    for (int i = 0; i < remoteVideoTracks.size(); i++) {
      try {
        remoteVideoTracks.get(i).removeSink(remoteSinks.get(i));
      } catch (Exception ignored) {
        // Ignore cleanup issues.
      }
    }
    remoteVideoTracks.clear();
    remoteSinks.clear();

    if (peerConnection != null) {
      try {
        peerConnection.close();
      } catch (Exception ignored) {
        // Ignore cleanup issues.
      }
      peerConnection = null;
    }

    if (factory != null) {
      try {
        factory.dispose();
      } catch (Exception ignored) {
        // Ignore cleanup issues.
      }
      factory = null;
    }
  }

  private void setRemoteDescription(RTCSessionDescription offer) throws Exception {
    CompletableFuture<Void> future = new CompletableFuture<>();
    peerConnection.setRemoteDescription(offer, new SetSessionDescriptionObserver() {
      @Override
      public void onSuccess() {
        future.complete(null);
      }

      @Override
      public void onFailure(String error) {
        future.completeExceptionally(new IllegalStateException("setRemoteDescription failed: " + error));
      }
    });
    future.get(SDP_TIMEOUT_MS, TimeUnit.MILLISECONDS);
  }

  private RTCSessionDescription createAnswer() throws Exception {
    CompletableFuture<RTCSessionDescription> future = new CompletableFuture<>();
    peerConnection.createAnswer(new RTCAnswerOptions(), new CreateSessionDescriptionObserver() {
      @Override
      public void onSuccess(RTCSessionDescription sdp) {
        future.complete(sdp);
      }

      @Override
      public void onFailure(String error) {
        future.completeExceptionally(new IllegalStateException("createAnswer failed: " + error));
      }
    });
    return future.get(SDP_TIMEOUT_MS, TimeUnit.MILLISECONDS);
  }

  private void setLocalDescription(RTCSessionDescription answer) throws Exception {
    CompletableFuture<Void> future = new CompletableFuture<>();
    peerConnection.setLocalDescription(answer, new SetSessionDescriptionObserver() {
      @Override
      public void onSuccess() {
        future.complete(null);
      }

      @Override
      public void onFailure(String error) {
        future.completeExceptionally(new IllegalStateException("setLocalDescription failed: " + error));
      }
    });
    future.get(SDP_TIMEOUT_MS, TimeUnit.MILLISECONDS);
  }
}
