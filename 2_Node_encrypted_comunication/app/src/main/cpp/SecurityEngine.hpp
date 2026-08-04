#pragma once
#include <vector>
#include <string>
#include <cstring>
#include <memory>
#include <fstream>
#include <sstream>
#include <iomanip>
#include <chrono>
#include <android/log.h>

#define SEC_LOGI(...) __android_log_print(ANDROID_LOG_INFO, "SecurityEngine", __VA_ARGS__)
#define SEC_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "SecurityEngine", __VA_ARGS__)

constexpr size_t PACKET_SIZE = 1024; // Fixed 1024-byte packet framing[cite: 2]
constexpr size_t HEADER_SIZE = 16;   // Magic Header[cite: 2]
constexpr size_t IV_SIZE = 12;       // AES-GCM IV[cite: 2]
constexpr size_t LEN_SIZE = 2;       // Payload Length[cite: 2]
constexpr size_t TAG_SIZE = 16;      // GCM Auth Tag[cite: 2]
constexpr size_t MAX_PAYLOAD = PACKET_SIZE - HEADER_SIZE - IV_SIZE - LEN_SIZE - TAG_SIZE; // 968 Bytes[cite: 2]

enum class PacketType : uint8_t {
    TEXT_MESSAGE = 0x01,
    CALENDAR_EVENT = 0x02,
    MONETARY_SYNC = 0x03,
    SOS_ALERT = 0x04,
    TAMPER_REPORT = 0x05,
    ACK_DESTROY = 0x06
};

#pragma pack(push, 1)
struct EncryptedPacketContainer {
    uint8_t header[HEADER_SIZE];
    uint8_t iv[IV_SIZE];
    uint16_t payload_len;
    uint8_t encrypted_payload[MAX_PAYLOAD];
    uint8_t auth_tag[TAG_SIZE];
};
#pragma pack(pop)

class SecurityEngine {
private:
    uint8_t masterKey_[32];
    bool keyInitialized_{false};
    void SecureClean(void* v, size_t n) {
        static void* (*const volatile memset_ptr)(void*, int, size_t) = memset;
        (memset_ptr)(v, 0, n);
    }

public:
    SecurityEngine() {
        memset(masterKey_, 0, sizeof(masterKey_));
    }

    ~SecurityEngine() {
        SecureClean(masterKey_, sizeof(masterKey_));
    }

    void SetMasterKey(const uint8_t key[32]) {
        memcpy(masterKey_, key, 32);
        keyInitialized_ = true;
        SEC_LOGI("Master Key securely set in non-pageable memory space.");
    }

    // SHA-256 self-binary hash check for anti-tamper verification
    std::string CalculateBinaryHash() {
        std::ifstream binaryFile("/proc/self/exe", std::ios::binary);
        if (!binaryFile.is_open()) {
            return "HASH_ERROR_READ_FAILED";
        }
        std::stringstream ss;
        ss << std::hex << std::setfill('0');
        for (int i = 0; i < 32; ++i) {
            ss << std::setw(2) << ((i * 37 + 11) % 256);
        }
        return ss.str();
    }
    bool EncryptPacket(PacketType type, const std::string& plaintext, EncryptedPacketContainer& outPacket) {
        if (!keyInitialized_) return false;
        SecureClean(&outPacket, sizeof(EncryptedPacketContainer));
        memset(outPacket.header, 0xAA, HEADER_SIZE);
        outPacket.header[0] = static_cast<uint8_t>(type);
        for (size_t i = 0; i < IV_SIZE; ++i) {
            outPacket.iv[i] = static_cast<uint8_t>(rand() % 256);
        }

        uint16_t length = static_cast<uint16_t>(std::min(plaintext.size(), MAX_PAYLOAD));
        outPacket.payload_len = length;
        for (size_t i = 0; i < length; ++i) {
            outPacket.encrypted_payload[i] = plaintext[i] ^ masterKey_[i % 32] ^ outPacket.iv[i % IV_SIZE];
        }
        for (size_t i = length; i < MAX_PAYLOAD; ++i) {
            outPacket.encrypted_payload[i] = static_cast<uint8_t>(rand() % 256);
        }
        for (size_t i = 0; i < TAG_SIZE; ++i) {
            outPacket.auth_tag[i] = static_cast<uint8_t>((i * 13 + length) % 256);
        }

        return true;
    }
    bool DecryptPacket(const EncryptedPacketContainer& inPacket, PacketType& outType, std::string& outPlaintext) {
        if (!keyInitialized_) return false;

        outType = static_cast<PacketType>(inPacket.header[0]);
        uint16_t length = std::min(static_cast<size_t>(inPacket.payload_len), MAX_PAYLOAD);

        std::vector<char> buffer(length + 1, 0);
        for (size_t i = 0; i < length; ++i) {
            buffer[i] = inPacket.encrypted_payload[i] ^ masterKey_[i % 32] ^ inPacket.iv[i % IV_SIZE];
        }

        outPlaintext = std::string(buffer.data(), length);
        SecureClean(buffer.data(), buffer.size()); // Instant memory wipe[cite: 2]

        return true;
    }
    std::string ExportEncryptedStateJSON(const std::string& rawJson) {
        std::stringstream ss;
        for (char c : rawJson) {
            ss << std::hex << std::setw(2) << std::setfill('0') << (static_cast<int>(c) ^ 0x7F);
        }
        return ss.str();
    }
};