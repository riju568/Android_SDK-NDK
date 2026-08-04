#pragma once
#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <android/native_window.h>
#include "imgui.h"
#include "backends/imgui_impl_android.h"
#include "backends/imgui_impl_opengl3.h"
#include "ChatEngine.hpp"

class ImGuiRenderer {
private:
    EGLDisplay display_ = EGL_NO_DISPLAY;
    EGLSurface surface_ = EGL_NO_SURFACE;
    EGLContext context_ = EGL_NO_CONTEXT;
    bool initialized_ = false;
    char messageInput_[256] = "";

    void RenderChatTab(ChatEngine* chatEngine) {
        auto messages = chatEngine->GetMessages();
        ImGui::BeginChild("ChatHistory", ImVec2(0, -ImGui::GetFrameHeightWithSpacing() - 20), true);
        for (const auto& msg : messages) {
            if (msg.isMine) {
                ImGui::PushStyleColor(ImGuiCol_ChildBg, ImVec4(0.0f, 0.4f, 0.1f, 1.0f)); // Green bubble
                ImGui::SetCursorPosX(ImGui::GetWindowWidth() - ImGui::CalcTextSize(msg.text.c_str()).x - 40);
            } else {
                ImGui::PushStyleColor(ImGuiCol_ChildBg, ImVec4(0.2f, 0.2f, 0.2f, 1.0f)); // Gray bubble
            }
            
            ImGui::BeginChild(msg.id.c_str(), ImVec2(ImGui::CalcTextSize(msg.text.c_str()).x + 20, 40), true);
            ImGui::TextWrapped("%s", msg.text.c_str());
            ImGui::EndChild();
            ImGui::PopStyleColor();
        }
        ImGui::EndChild();
        ImGui::InputText("##MessageInput", messageInput_, IM_ARRAYSIZE(messageInput_));
        ImGui::SameLine();
        if (ImGui::Button("Send", ImVec2(80, 0))) {
            if (strlen(messageInput_) > 0) {
                chatEngine->AddMessage({"local_id", messageInput_, true, 0, false});
                memset(messageInput_, 0, sizeof(messageInput_));
            }
        }
    }

    void RenderPlannerTab() {
        ImGui::Text("Shared Couples Calendar (Syncs 00:00 UTC)");
        ImGui::Separator();
        if (ImGui::BeginTable("CalendarGrid", 7, ImGuiTableFlags_Borders | ImGuiTableFlags_RowBg)) {
            const char* days[] = {"Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"};
            for (int i = 0; i < 7; i++) {
                ImGui::TableSetupColumn(days[i]);
            }
            ImGui::TableHeadersRow();
            for (int row = 0; row < 4; row++) {
                ImGui::TableNextRow(ImGuiTableRowFlags_None, 100.0f); // 100px height per week
                for (int col = 0; col < 7; col++) {
                    ImGui::TableSetColumnIndex(col);
                    int dayNum = (row * 7) + col + 1;
                    if (dayNum <= 31) {
                        ImGui::Text("%d", dayNum);
                        if (dayNum == 15) {
                            ImGui::PushStyleColor(ImGuiCol_Button, ImVec4(0.2f, 0.4f, 0.8f, 1.0f));
                            ImGui::Button("Dinner Date", ImVec2(-1, 30));
                            ImGui::PopStyleColor();
                        }
                    }
                }
            }
            ImGui::EndTable();
        }
    }

public:
    void Render(bool isSystemSecure, ChatEngine* chatEngine) {
        if (!initialized_) return;
        ImGui_ImplOpenGL3_NewFrame();
        ImGui_ImplAndroid_NewFrame();
        ImGui::NewFrame();
        ImGuiIO& io = ImGui::GetIO();
        ImGui::SetNextWindowPos(ImVec2(0, 0));
        ImGui::SetNextWindowSize(io.DisplaySize);
        ImGui::Begin("MainApp", nullptr, ImGuiWindowFlags_NoTitleBar | ImGuiWindowFlags_NoResize | ImGuiWindowFlags_NoMove);
        if (!isSystemSecure) {
            ImGui::TextColored(ImVec4(1, 0, 0, 1), "SECURITY TAMPER DETECTED");
        } else {
            if (ImGui::BeginTabBar("AppTabs")) {
                if (ImGui::BeginTabItem("Chat (WhatsApp)")) {
                    RenderChatTab(chatEngine);
                    ImGui::EndTabItem();
                }
                if (ImGui::BeginTabItem("Planner")) {
                    RenderPlannerTab();
                    ImGui::EndTabItem();
                }
                ImGui::EndTabBar();
            }
        }

        ImGui::End();
        ImGui::Render();

        glViewport(0, 0, static_cast<int>(io.DisplaySize.x), static_cast<int>(io.DisplaySize.y));
        glClearColor(0.08f, 0.08f, 0.08f, 1.0f);
        glClear(GL_COLOR_BUFFER_BIT);
        ImGui_ImplOpenGL3_RenderDrawData(ImGui::GetDrawData());

        eglSwapBuffers(display_, surface_);
    }
};