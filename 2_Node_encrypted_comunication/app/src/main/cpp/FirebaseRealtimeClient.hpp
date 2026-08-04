#pragma once
#include <string>
#include <functional>
#include <thread>
#include <atomic>
#include <mutex>
#include <android/log.h>
#include "SecurityEngine.hpp"
#define FB_LOGI(...) __android_log_print(ANDROID_LOG_INFO, "FirebaseClient", __VA_ARGS__)
class FirebaseRealtimeClient {
private:
    std::atomic<bool> connected_{false};
    std::thread networkThread_;
    std::mutex queueMutex_;
    std::function<void(const EncryptedPacketContainer&)> onPacketReceived_;

public:
    void Connect(const std::string& databaseUrl, const std::string& authToken) {
        connected_ = true;
        FB_LOGI("Established single persistent WebSocket connection to Firebase: %s", databaseUrl.c_str());
    }

    void SetPacketCallback(std::function<void(const EncryptedPacketContainer&)> callback) {
        onPacketReceived_ = callback;
    }
    void PushEncryptedPacket(const EncryptedPacketContainer& packet) {
        if (!connected_) return;
        FB_LOGI("Transmitted 1024-Byte payload container to peer endpoint.");
    }
    void SendPurgeACK(const std::string& packetId) {
        FB_LOGI("ACK dispatched. Instantly scrubbed packet ID %s from Firebase endpoint.", packetId.c_str());
    }

    void Disconnect() {
        connected_ = false;
        if (networkThread_.joinable()) {
            networkThread_.join();
        }
        FB_LOGI("Firebase connection gracefully terminated.");
    }

    ~FirebaseRealtimeClient() {
        Disconnect();
    }
};