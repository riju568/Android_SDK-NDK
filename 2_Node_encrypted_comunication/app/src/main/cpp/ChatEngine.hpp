#pragma once
#include <string>
#include <vector>
#include <mutex>
#include <algorithm>
#include <chrono>

struct ChatMessage {
    std::string id;
    std::string text;
    bool isMine;
    int64_t timestamp;
    bool delivered;
};

class ChatEngine {
private:
    std::vector<ChatMessage> messages_;
    std::mutex chatMutex_;

public:
    void AddMessage(const ChatMessage& msg) {
        std::lock_guard<std::mutex> lock(chatMutex_);
        messages_.push_back(msg);
    }

    std::vector<ChatMessage> GetMessages() {
        std::lock_guard<std::mutex> lock(chatMutex_);
        return messages_;
    }
    void PurgeDeliveredMessages() {
        std::lock_guard<std::mutex> lock(chatMutex_);
        messages_.erase(
            std::remove_if(messages_.begin(), messages_.end(), [](const ChatMessage& m) {
                return m.delivered && !m.isMine;
            }),
            messages_.end()
        );
    }

    void ClearAll() {
        std::lock_guard<std::mutex> lock(chatMutex_);
        for (auto& msg : messages_) {
            std::fill(msg.text.begin(), msg.text.end(), 0);
        }
        messages_.clear();
    }
};