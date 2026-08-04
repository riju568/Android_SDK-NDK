#pragma once

#include <string>
#include <vector>
#include <memory>
#include <mutex>
#include <sstream>
#include <android/log.h>
#include "api/peer_connection_interface.h"
#include "api/create_peerconnection_factory.h"
#include "api/media_stream_interface.h"
#include "api/scoped_refptr.h"
#include "SecurityEngine.hpp"
#include "FirebaseRealtimeClient.hpp"

#define RTC_LOGI(...) __android_log_print(ANDROID_LOG_INFO, "WebRTCSignalingEngine", __VA_ARGS__)
#define RTC_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "WebRTCSignalingEngine", __VA_ARGS__)
enum class SignalingType : uint8_t {
    OFFER = 0,
    ANSWER = 1,
    CANDIDATE = 2
};

struct WebRTCSignalingPayload {
    SignalingType type;
    std::string sdpOrCandidate;
    std::string sdpMid;
    int sdpMLineIndex{0};
    std::string Serialize() const {
        std::stringstream ss;
        ss << static_cast<int>(type) << "\n"
           << sdpMid << "\n"
           << sdpMLineIndex << "\n"
           << sdpOrCandidate;
        return ss.str();
    }
    static bool Deserialize(const std::string& raw, WebRTCSignalingPayload& outPayload) {
        std::stringstream ss(raw);
        std::string typeStr, midStr, mlineStr;

        if (!std::getline(ss, typeStr) ||
            !std::getline(ss, midStr) ||
            !std::getline(ss, mlineStr)) {
            return false;
        }
        outPayload.type = static_cast<SignalingType>(std::stoi(typeStr));
        outPayload.sdpMid = midStr;
        outPayload.sdpMLineIndex = std::stoi(mlineStr);

        std::string line;
        std::stringstream sdpSs;
        while (std::getline(ss, line)) {
            sdpSs << line << "\n";
        }
        outPayload.sdpOrCandidate = sdpSs.str();
        if (!outPayload.sdpOrCandidate.empty() && outPayload.sdpOrCandidate.back() == '\n') {
            outPayload.sdpOrCandidate.pop_back();
        }
        return true;
    }
};

class WebRTCSignalingEngine : public webrtc::PeerConnectionObserver,
                              public webrtc::CreateSessionDescriptionObserver,
                              public webrtc::SetLocalDescriptionObserver,
                              public webrtc::SetRemoteDescriptionObserver {
private:
    rtc::scoped_refptr<webrtc::PeerConnectionFactoryInterface> peerConnectionFactory_;
    rtc::scoped_refptr<webrtc::PeerConnectionInterface> peerConnection_;
    
    FirebaseRealtimeClient* firebaseClient_{nullptr};
    SecurityEngine* securityEngine_{nullptr};
    std::mutex rtcMutex_;

    // Step 4.1: Unified ICE Configuration (STUN + Coturn TURN)
    webrtc::PeerConnectionInterface::RTCConfiguration GetRTCConfig() {
        webrtc::PeerConnectionInterface::RTCConfiguration config;
        config.sdp_semantics = webrtc::SdpSemantics::kUnifiedPlan;
        config.continual_gathering_policy = webrtc::PeerConnectionInterface::GATHER_CONTINUALLY;

        // 1. Primary Public STUN Servers (Free)
        webrtc::PeerConnectionInterface::IceServer stunServer1;
        stunServer1.urls.push_back("stun:stun.l.google.com:19302");
        stunServer1.urls.push_back("stun:stun1.l.google.com:19302");
        config.servers.push_back(stunServer1);

        // 2. Fallback Encrypted TURN Server
        webrtc::PeerConnectionInterface::IceServer turnServer;
        turnServer.urls.push_back("turn:YOUR_PUBLIC_SERVER_IP:3478");
        turnServer.username = "coupleuser";
        turnServer.password = "StrongCryptographicPassword123!";
        config.servers.push_back(turnServer);

        return config;
    }

    // Step 4.2: Helper to encrypt signaling payload into 1024-byte packet and dispatch over WebSocket
    void DispatchSignalingPayload(const WebRTCSignalingPayload& payload) {
        if (!firebaseClient_ || !securityEngine_) {
            RTC_LOGE("Firebase or SecurityEngine bindings not set!");
            return;
        }

        std::string serialized = payload.Serialize();
        EncryptedPacketContainer packet;
        
        // Encrypt using fixed 1024-byte AES-256-GCM container
        if (securityEngine_->EncryptPacket(PacketType::TEXT_MESSAGE, serialized, packet)) {
            firebaseClient_->PushEncryptedPacket(packet);
            RTC_LOGI("Dispatched WebRTC signaling packet (Type: %d) over Firebase WebSocket.", static_cast<int>(payload.type));
        } else {
            RTC_LOGE("Failed to encrypt WebRTC signaling packet.");
        }
    }

public:
    WebRTCSignalingEngine() = default;
    virtual ~WebRTCSignalingEngine() { Shutdown(); }

    void Initialize(FirebaseRealtimeClient* fbClient, SecurityEngine* secEngine) {
        std::lock_guard<std::mutex> lock(rtcMutex_);
        firebaseClient_ = fbClient;
        securityEngine_ = secEngine;
        webrtc::PeerConnectionFactoryDependencies dependencies;
        peerConnectionFactory_ = webrtc::CreatePeerConnectionFactory(std::move(dependencies));

        if (!peerConnectionFactory_) {
            RTC_LOGE("Failed to create WebRTC PeerConnectionFactory.");
            return;
        }

        webrtc::PeerConnectionDependencies pcDependencies(this);
        webrtc::PeerConnectionInterface::RTCConfiguration config = GetRTCConfig();

        auto result = peerConnectionFactory_->CreatePeerConnectionOrError(config, std::move(pcDependencies));
        if (result.ok()) {
            peerConnection_ = result.MoveValue();
            RTC_LOGI("WebRTC PeerConnection successfully initialized with STUN/TURN fallback.");
        } else {
            RTC_LOGE("Failed to create PeerConnection: %s", result.error().message());
        }
    }

    void InitiateCall() {
        if (!peerConnection_) return;

        webrtc::PeerConnectionInterface::RTCOfferAnswerOptions options;
        options.offer_to_receive_audio = true;
        options.offer_to_receive_video = true;

        peerConnection_->CreateOffer(this, options);
    }
    void HandleIncomingSignalingPacket(const EncryptedPacketContainer& packet) {
        PacketType type;
        std::string plaintext;

        if (!securityEngine_->DecryptPacket(packet, type, plaintext)) {
            RTC_LOGE("Failed to decrypt incoming signaling packet.");
            return;
        }

        WebRTCSignalingPayload payload;
        if (!WebRTCSignalingPayload::Deserialize(plaintext, payload)) {
            RTC_LOGE("Failed to parse signaling payload.");
            return;
        }

        std::lock_guard<std::mutex> lock(rtcMutex_);
        if (!peerConnection_) return;

        if (payload.type == SignalingType::OFFER) {
            webrtc::SdpParseError error;
            std::unique_ptr<webrtc::SessionDescriptionInterface> sessionDesc =
                webrtc::CreateSessionDescription(webrtc::SdpType::kOffer, payload.sdpOrCandidate, &error);

            if (!sessionDesc) {
                RTC_LOGE("Failed to parse SDP offer: %s", error.description.c_str());
                return;
            }

            peerConnection_->SetRemoteDescription(
                rtc::scoped_refptr<webrtc::SetRemoteDescriptionObserverInterface>(this),
                sessionDesc.release()
            );
            webrtc::PeerConnectionInterface::RTCOfferAnswerOptions options;
            peerConnection_->CreateAnswer(this, options);

        } else if (payload.type == SignalingType::ANSWER) {
            webrtc::SdpParseError error;
            std::unique_ptr<webrtc::SessionDescriptionInterface> sessionDesc =
                webrtc::CreateSessionDescription(webrtc::SdpType::kAnswer, payload.sdpOrCandidate, &error);

            if (sessionDesc) {
                peerConnection_->SetRemoteDescription(
                    rtc::scoped_refptr<webrtc::SetRemoteDescriptionObserverInterface>(this),
                    sessionDesc.release()
                );
            }

        } else if (payload.type == SignalingType::CANDIDATE) {
            webrtc::SdpParseError error;
            std::unique_ptr<webrtc::IceCandidateInterface> candidate(
                webrtc::CreateIceCandidate(payload.sdpMid, payload.sdpMLineIndex, payload.sdpOrCandidate, &error)
            );

            if (candidate) {
                peerConnection_->AddIceCandidate(std::move(candidate), [](webrtc::RTCError error) {
                    if (!error.ok()) {
                        RTC_LOGE("Failed to add ICE candidate: %s", error.message());
                    }
                });
            }
        }
    }

    void OnIceCandidate(const webrtc::IceCandidateInterface* candidate) override {
        std::string sdpCandidate;
        if (candidate->ToString(&sdpCandidate)) {
            WebRTCSignalingPayload payload;
            payload.type = SignalingType::CANDIDATE;
            payload.sdpOrCandidate = sdpCandidate;
            payload.sdpMid = candidate->sdp_mid();
            payload.sdpMLineIndex = candidate->sdp_mline_index();

            DispatchSignalingPayload(payload);
        }
    }
    void OnSuccess(webrtc::SessionDescriptionInterface* desc) override {
        peerConnection_->SetLocalDescription(
            rtc::scoped_refptr<webrtc::SetLocalDescriptionObserverInterface>(this),
            desc
        );

        std::string sdp;
        desc->ToString(&sdp);

        WebRTCSignalingPayload payload;
        payload.type = (desc->GetType() == webrtc::SdpType::kOffer) ? SignalingType::OFFER : SignalingType::ANSWER;
        payload.sdpOrCandidate = sdp;

        DispatchSignalingPayload(payload);
    }

    void OnFailure(webrtc::RTCError error) override {
        RTC_LOGE("Session Description creation failed: %s", error.message());
    }
    void OnSignalingChange(webrtc::PeerConnectionInterface::SignalingState new_state) override {}
    void OnAddStream(rtc::scoped_refptr<webrtc::MediaStreamInterface> stream) override {}
    void OnRemoveStream(rtc::scoped_refptr<webrtc::MediaStreamInterface> stream) override {}
    void OnDataChannel(rtc::scoped_refptr<webrtc::DataChannelInterface> data_channel) override {}
    void OnRenegotiationNeeded() override {}
    void OnIceConnectionChange(webrtc::PeerConnectionInterface::IceConnectionState new_state) override {}
    void OnIceGatheringChange(webrtc::PeerConnectionInterface::IceGatheringState new_state) override {}
    void OnSetLocalDescriptionComplete(webrtc::RTCError error) override {}
    void OnSetRemoteDescriptionComplete(webrtc::RTCError error) override {}

    void Shutdown() {
        std::lock_guard<std::mutex> lock(rtcMutex_);
        if (peerConnection_) {
            peerConnection_->Close();
            peerConnection_ = nullptr;
        }
        peerConnectionFactory_ = nullptr;
        RTC_LOGI("WebRTC Signaling Engine safely shut down.");
    }
};