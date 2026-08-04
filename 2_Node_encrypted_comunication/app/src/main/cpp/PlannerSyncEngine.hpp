#pragma once
#include <thread>
#include <chrono>
#include <functional>
#include <atomic>
#include <mutex>
#include <condition_variable>
#include <vector>
#include <string>
#include <ctime>
#include <android/log.h>

#define SYNC_LOGI(...) __android_log_print(ANDROID_LOG_INFO, "PlannerSyncEngine", __VA_ARGS__)

struct MonetaryEntry {
    std::string id;
    double amount;
    std::string note;
    int64_t timestamp;
};

class PlannerSyncEngine {
private:
    std::atomic<bool> running_{false};
    std::thread syncThread_;
    std::condition_variable cv_;
    std::mutex mtx_;
    std::function<void()> onDailySync_;
    std::vector<MonetaryEntry> expenseQueue_;
    std::chrono::milliseconds TimeUntilNextUTCMidnight() {
        using namespace std::chrono;
        auto now = system_clock::now();
        time_t tnow = system_clock::to_time_t(now);
        tm* utc_tm = gmtime(&tnow); // Convert to UTC[cite: 1]

        utc_tm->tm_mday += 1;
        utc_tm->tm_hour = 0;
        utc_tm->tm_min = 0;
        utc_tm->tm_sec = 0;

        auto nextMidnight = system_clock::from_time_t(timegm(utc_tm)); // Calculate target time[cite: 1]
        return duration_cast<milliseconds>(nextMidnight - now);
    }

    void WorkerLoop() {
        while (running_) {
            auto sleepDuration = TimeUntilNextUTCMidnight(); // Sleep until midnight UTC[cite: 1]
            SYNC_LOGI("Next scheduled 00:00 AM UTC synchronization in %lld ms", sleepDuration.count());

            std::unique_lock<std::mutex> lock(mtx_);
            if (cv_.wait_for(lock, sleepDuration, [this]() { return !running_.load(); })) {
                break;
            }

            if (running_ && onDailySync_) {
                SYNC_LOGI("00:00 AM UTC reached. Executing atomic calendar & monetary ledger sync.");
                onDailySync_();
            }
        }
    }

public:
    void Start(std::function<void()> syncCallback) {
        if (running_) return;
        onDailySync_ = syncCallback;
        running_ = true;
        syncThread_ = std::thread(&PlannerSyncEngine::WorkerLoop, this); // Start worker thread[cite: 1]
    }

    void AddExpense(double amount, const std::string& note) {
        std::lock_guard<std::mutex> lock(mtx_);
        expenseQueue_.push_back({"tx_" + std::to_string(rand()), amount, note, std::time(nullptr)});
    }

    std::vector<MonetaryEntry> GetExpenses() {
        std::lock_guard<std::mutex> lock(mtx_);
        return expenseQueue_;
    }

    void Stop() {
        running_ = false;
        cv_.notify_all();
        if (syncThread_.joinable()) {
            syncThread_.join();
        }
    }

    ~PlannerSyncEngine() { Stop(); }
};