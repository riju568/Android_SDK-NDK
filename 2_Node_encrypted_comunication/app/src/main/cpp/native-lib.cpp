#include <jni.h>
#include <memory>
#include <string>
#include <android/log.h>
#include "imgui.h"
#include "backends/imgui_impl_android.h"
#include "backends/imgui_impl_opengl3.h"
#include "ChatEngine.hpp"
#include "FirebaseRealtimeClient.hpp"
#include "ChatEngine.hpp"
#include "SecurityEngine.hpp"
#include "PlannerSyncEngine.hpp"
#include "WebRTCSignalingEngine.hpp"

namespace CoreEngine {
    static std::unique_ptr<SecurityEngine> security;
    static std::unique_ptr<FirebaseRealtimeClient> firebase;
    static std::unique_ptr<WebRTCSignalingEngine> rtcEngine;
}

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_pair_app_JNIBridge_nativeInitCore(JNIEnv* env, jobject thiz, jobject context) {
    CoreEngine::security = std::make_unique<SecurityEngine>();
    CoreEngine::firebase = std::make_unique<FirebaseRealtimeClient>();
    CoreEngine::rtcEngine = std::make_unique<WebRTCSignalingEngine>();
    CoreEngine::rtcEngine->Initialize(CoreEngine::firebase.get(), CoreEngine::security.get());
    CoreEngine::firebase->SetPacketCallback([](const EncryptedPacketContainer& packet) {
        CoreEngine::rtcEngine->HandleIncomingSignalingPacket(packet);
    });

    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_pair_app_MainActivity_nativeStartVideoCall(JNIEnv* env, jobject thiz) {
    if (CoreEngine::rtcEngine) {
        CoreEngine::rtcEngine->InitiateCall();
    }
}
} 