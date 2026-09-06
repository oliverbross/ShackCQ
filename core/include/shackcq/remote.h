// SPDX-License-Identifier: GPL-3.0-only
#pragma once

#include <cstddef>
#include <cstdint>
#include <optional>
#include <string>
#include <string_view>
#include <unordered_map>
#include <unordered_set>
#include <vector>

namespace shackcq::remote {

inline constexpr std::uint16_t ProtocolVersion = 1;
inline constexpr std::size_t MaxControlFrame = 64 * 1024;
inline constexpr std::size_t MaxMediaPayload = 256 * 1024;
inline constexpr std::size_t MaxSessions = 8;

enum class Role : std::uint8_t { Observer, Operator, Admin };
enum class Channel : std::uint8_t {
  Control = 1, State, Spots, Health, AudioRx, AudioTx, Spectrum, Waterfall,
  IqOptional, Digi, Keyer, Voice, Rotator, LogEventOptional
};
enum class Lease : std::uint8_t { Writer, Transmit, Rotator };
enum class CommandClass : std::uint8_t {
  Read, SafeSet, Transmit, Tune, RotatorMove, Unsupported
};

struct PairingOffer {
  std::string stationId;
  std::string endpoint;
  std::string certificateSha256;
  std::string nonce;
  Role defaultRole{Role::Observer};
  std::uint64_t expiresAtMs{};
};

struct SessionSnapshot {
  std::string sessionId;
  std::string deviceId;
  Role role{Role::Observer};
  bool foreground{};
  std::uint64_t generation{};
  std::uint64_t lastHeartbeatMs{};
  bool writer{};
  bool transmit{};
  bool rotator{};
};

struct MediaFrame {
  Channel channel{Channel::Control};
  std::uint16_t flags{};
  std::uint32_t sequence{};
  std::uint64_t timestampMs{};
  std::uint64_t generation{};
  std::vector<std::uint8_t> payload;
};

struct RigState {
  std::uint64_t frequencyHz{};
  std::string mode{"USB"};
  std::uint32_t passbandHz{2400};
  std::string vfo{"VFOA"};
  bool split{};
  std::uint64_t splitFrequencyHz{};
  std::string splitMode{"USB"};
  std::int32_t ritHz{};
  std::int32_t xitHz{};
  bool ptt{};
};

struct ProtocolReply {
  bool accepted{};
  int errorCode{};
  CommandClass commandClass{CommandClass::Unsupported};
  std::string operation;
  std::string response;
  std::vector<std::string> arguments;
};

class SessionAuthority final {
public:
  bool registerPairingOffer(const PairingOffer &offer, std::uint64_t nowMs);
  bool consumePairingOffer(std::string_view nonce, std::string_view deviceId,
                           std::string_view publicKey, Role approvedRole,
                           std::uint64_t nowMs);
  bool restorePairedDevice(std::string_view deviceId, std::string_view publicKey,
                           Role role, bool revoked = false);
  std::optional<std::string> openSession(std::string_view deviceId,
                                         bool foreground,
                                         std::uint64_t generation,
                                         std::uint64_t nowMs);
  bool heartbeat(std::string_view sessionId, bool foreground,
                 std::uint64_t generation, std::uint64_t nowMs);
  bool acquire(std::string_view sessionId, Lease lease, std::uint64_t nowMs,
               std::uint64_t ttlMs, bool txPhysicallyAccepted = false,
               bool rotatorPhysicallyAccepted = false);
  void closeSession(std::string_view sessionId);
  void revoke(std::string_view deviceId);
  void localPreempt();
  void globalStop();
  void expire(std::uint64_t nowMs);
  std::vector<SessionSnapshot> sessions() const;
  bool paired(std::string_view deviceId) const;

private:
  struct Device { Role role; std::string publicKey; bool revoked{}; };
  struct Session { SessionSnapshot state; std::uint64_t writerExpiry{};
                   std::uint64_t txExpiry{}; std::uint64_t rotatorExpiry{}; };
  void clearLeases(Session &session);
  bool heldByOther(std::string_view sessionId, Lease lease) const;
  std::unordered_map<std::string, PairingOffer> m_offers;
  std::unordered_map<std::string, Device> m_devices;
  std::unordered_map<std::string, Session> m_sessions;
  std::unordered_set<std::string> m_revoked;
  std::uint64_t m_sessionCounter{};
};

std::vector<std::uint8_t> encodeMedia(const MediaFrame &frame);
std::optional<MediaFrame> decodeMedia(const std::uint8_t *bytes,
                                      std::size_t size);
ProtocolReply handleRigctld(std::string_view line, const RigState &state,
                            bool writerLease, bool transmitLease);
ProtocolReply handleTci(std::string_view command, const RigState &state,
                        bool writerLease, bool transmitLease);
std::string roleName(Role role);

} // namespace shackcq::remote
