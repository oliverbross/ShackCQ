// SPDX-License-Identifier: GPL-3.0-only
#include "shackcq/remote.h"

#include <algorithm>
#include <array>
#include <charconv>
#include <cctype>
#include <limits>
#include <sstream>

namespace shackcq::remote {
namespace {
constexpr std::array<std::uint8_t, 4> Magic{'R', 'W', 'R', '1'};
constexpr std::size_t HeaderSize = 36;

void put16(std::vector<std::uint8_t> &out, std::uint16_t value) {
  out.push_back(static_cast<std::uint8_t>(value >> 8));
  out.push_back(static_cast<std::uint8_t>(value));
}
void put32(std::vector<std::uint8_t> &out, std::uint32_t value) {
  for (int shift = 24; shift >= 0; shift -= 8)
    out.push_back(static_cast<std::uint8_t>(value >> shift));
}
void put64(std::vector<std::uint8_t> &out, std::uint64_t value) {
  for (int shift = 56; shift >= 0; shift -= 8)
    out.push_back(static_cast<std::uint8_t>(value >> shift));
}
std::uint16_t get16(const std::uint8_t *p) {
  return static_cast<std::uint16_t>((p[0] << 8) | p[1]);
}
std::uint32_t get32(const std::uint8_t *p) {
  std::uint32_t value{};
  for (int i = 0; i < 4; ++i) value = (value << 8) | p[i];
  return value;
}
std::uint64_t get64(const std::uint8_t *p) {
  std::uint64_t value{};
  for (int i = 0; i < 8; ++i) value = (value << 8) | p[i];
  return value;
}
std::string trim(std::string_view input) {
  while (!input.empty() && std::isspace(static_cast<unsigned char>(input.front()))) input.remove_prefix(1);
  while (!input.empty() && std::isspace(static_cast<unsigned char>(input.back()))) input.remove_suffix(1);
  return std::string(input);
}
std::vector<std::string> tokens(std::string_view input) {
  std::istringstream stream(trim(input));
  std::vector<std::string> values;
  for (std::string value; stream >> value;) values.push_back(std::move(value));
  return values;
}
std::vector<std::string> commaTokens(std::string_view input) {
  std::vector<std::string> values;
  while (!input.empty()) {
    const auto comma = input.find(',');
    values.push_back(trim(input.substr(0, comma)));
    if (comma == std::string_view::npos) break;
    input.remove_prefix(comma + 1);
  }
  return values;
}
template <typename T> std::optional<T> number(std::string_view value) {
  T result{};
  const auto parsed = std::from_chars(value.data(), value.data() + value.size(), result);
  if (parsed.ec != std::errc{} || parsed.ptr != value.data() + value.size()) return std::nullopt;
  return result;
}
std::string upper(std::string value) {
  std::transform(value.begin(), value.end(), value.begin(), [](unsigned char c) { return static_cast<char>(std::toupper(c)); });
  return value;
}
bool validMode(std::string_view mode) {
  static constexpr std::array<std::string_view, 16> modes{
      "USB", "LSB", "CW", "CWR", "RTTY", "RTTYR", "AM", "FM",
      "WFM", "PKTLSB", "PKTUSB", "PKTFM", "SAM", "DSB", "DIGU", "DIGL"};
  return std::find(modes.begin(), modes.end(), mode) != modes.end();
}
ProtocolReply denied(CommandClass kind, std::string operation, int code = -8) {
  return {false, code, kind, std::move(operation), "RPRT " + std::to_string(code) + "\n", {}};
}
ProtocolReply readReply(std::string operation, std::string value) {
  return {true, 0, CommandClass::Read, std::move(operation), std::move(value), {}};
}
} // namespace

bool SessionAuthority::registerPairingOffer(const PairingOffer &offer,
                                            std::uint64_t nowMs) {
  if (offer.stationId.empty() || offer.endpoint.empty() || offer.nonce.size() < 16 ||
      offer.certificateSha256.size() != 64 || offer.expiresAtMs <= nowMs ||
      offer.expiresAtMs - nowMs > 10 * 60 * 1000) return false;
  m_offers[offer.nonce] = offer;
  return true;
}

bool SessionAuthority::consumePairingOffer(std::string_view nonce,
                                           std::string_view deviceId,
                                           std::string_view publicKey,
                                           Role approvedRole,
                                           std::uint64_t nowMs) {
  const auto found = m_offers.find(std::string(nonce));
  if (found == m_offers.end() || found->second.expiresAtMs < nowMs ||
      deviceId.empty() || deviceId.size() > 128 || publicKey.size() < 32 ||
      publicKey.size() > 4096 || m_revoked.count(std::string(deviceId)) != 0) return false;
  m_devices[std::string(deviceId)] = {approvedRole, std::string(publicKey), false};
  m_offers.erase(found);
  return true;
}

bool SessionAuthority::restorePairedDevice(std::string_view deviceId,
                                           std::string_view publicKey,
                                           Role role, bool revoked) {
  if (deviceId.empty() || deviceId.size() > 128 || publicKey.empty() || publicKey.size() > 4096)
    return false;
  m_devices[std::string(deviceId)] = {role, std::string(publicKey), revoked};
  if (revoked) m_revoked.insert(std::string(deviceId));
  else m_revoked.erase(std::string(deviceId));
  return true;
}

std::optional<std::string> SessionAuthority::openSession(
    std::string_view deviceId, bool foreground, std::uint64_t generation,
    std::uint64_t nowMs) {
  const auto device = m_devices.find(std::string(deviceId));
  if (device == m_devices.end() || device->second.revoked ||
      m_revoked.count(std::string(deviceId)) != 0 || m_sessions.size() >= MaxSessions) return std::nullopt;
  const std::string id = "remote-" + std::to_string(++m_sessionCounter);
  Session session;
  session.state = {id, std::string(deviceId), device->second.role, foreground,
                   generation, nowMs, false, false, false};
  m_sessions.emplace(id, std::move(session));
  return id;
}

bool SessionAuthority::heartbeat(std::string_view sessionId, bool foreground,
                                 std::uint64_t generation,
                                 std::uint64_t nowMs) {
  const auto found = m_sessions.find(std::string(sessionId));
  if (found == m_sessions.end() || found->second.state.generation != generation) return false;
  found->second.state.foreground = foreground;
  found->second.state.lastHeartbeatMs = nowMs;
  if (!foreground) {
    found->second.state.transmit = false;
    found->second.txExpiry = 0;
  }
  return true;
}

bool SessionAuthority::heldByOther(std::string_view sessionId, Lease lease) const {
  return std::any_of(m_sessions.begin(), m_sessions.end(), [&](const auto &row) {
    if (row.first == sessionId) return false;
    if (lease == Lease::Writer) return row.second.state.writer;
    if (lease == Lease::Transmit) return row.second.state.transmit;
    return row.second.state.rotator;
  });
}

bool SessionAuthority::acquire(std::string_view sessionId, Lease lease,
                               std::uint64_t nowMs, std::uint64_t ttlMs,
                               bool txPhysicallyAccepted,
                               bool rotatorPhysicallyAccepted) {
  auto found = m_sessions.find(std::string(sessionId));
  if (found == m_sessions.end() || ttlMs == 0 || ttlMs > 30'000 || heldByOther(sessionId, lease)) return false;
  Session &session = found->second;
  if (session.state.role == Role::Observer) return false;
  if (lease != Lease::Writer && !session.state.writer) return false;
  if (lease == Lease::Transmit && (!session.state.foreground || !txPhysicallyAccepted)) return false;
  if (lease == Lease::Rotator && !rotatorPhysicallyAccepted) return false;
  if (lease == Lease::Writer) { session.state.writer = true; session.writerExpiry = nowMs + ttlMs; }
  if (lease == Lease::Transmit) { session.state.transmit = true; session.txExpiry = nowMs + ttlMs; }
  if (lease == Lease::Rotator) { session.state.rotator = true; session.rotatorExpiry = nowMs + ttlMs; }
  return true;
}

void SessionAuthority::clearLeases(Session &session) {
  session.state.writer = session.state.transmit = session.state.rotator = false;
  session.writerExpiry = session.txExpiry = session.rotatorExpiry = 0;
}
void SessionAuthority::closeSession(std::string_view sessionId) { m_sessions.erase(std::string(sessionId)); }
void SessionAuthority::revoke(std::string_view deviceId) {
  m_revoked.insert(std::string(deviceId));
  if (auto device = m_devices.find(std::string(deviceId)); device != m_devices.end()) device->second.revoked = true;
  for (auto it = m_sessions.begin(); it != m_sessions.end();) {
    if (it->second.state.deviceId == deviceId) it = m_sessions.erase(it); else ++it;
  }
}
void SessionAuthority::localPreempt() { for (auto &[id, session] : m_sessions) { (void)id; clearLeases(session); } }
void SessionAuthority::globalStop() { localPreempt(); }
void SessionAuthority::expire(std::uint64_t nowMs) {
  for (auto it = m_offers.begin(); it != m_offers.end();) {
    if (it->second.expiresAtMs <= nowMs) it = m_offers.erase(it); else ++it;
  }
  for (auto it = m_sessions.begin(); it != m_sessions.end();) {
    auto &session = it->second;
    if (session.state.lastHeartbeatMs + 15'000 <= nowMs) { it = m_sessions.erase(it); continue; }
    if (session.writerExpiry <= nowMs) { session.state.writer = false; session.writerExpiry = 0; }
    if (session.txExpiry <= nowMs) { session.state.transmit = false; session.txExpiry = 0; }
    if (session.rotatorExpiry <= nowMs) { session.state.rotator = false; session.rotatorExpiry = 0; }
    ++it;
  }
}
std::vector<SessionSnapshot> SessionAuthority::sessions() const {
  std::vector<SessionSnapshot> result;
  result.reserve(m_sessions.size());
  for (const auto &[id, session] : m_sessions) { (void)id; result.push_back(session.state); }
  std::sort(result.begin(), result.end(), [](const auto &a, const auto &b) { return a.sessionId < b.sessionId; });
  return result;
}
bool SessionAuthority::paired(std::string_view deviceId) const {
  const auto found = m_devices.find(std::string(deviceId));
  return found != m_devices.end() && !found->second.revoked;
}

std::vector<std::uint8_t> encodeMedia(const MediaFrame &frame) {
  if (frame.channel == Channel::Control || frame.payload.size() > MaxMediaPayload) return {};
  std::vector<std::uint8_t> out;
  out.reserve(HeaderSize + frame.payload.size());
  out.insert(out.end(), Magic.begin(), Magic.end());
  put16(out, ProtocolVersion); out.push_back(static_cast<std::uint8_t>(frame.channel)); out.push_back(0);
  put16(out, frame.flags); put16(out, 0); put32(out, frame.sequence);
  put64(out, frame.timestampMs); put64(out, frame.generation);
  put32(out, static_cast<std::uint32_t>(frame.payload.size()));
  out.insert(out.end(), frame.payload.begin(), frame.payload.end());
  return out;
}

std::optional<MediaFrame> decodeMedia(const std::uint8_t *bytes, std::size_t size) {
  if (bytes == nullptr || size < HeaderSize || !std::equal(Magic.begin(), Magic.end(), bytes) ||
      get16(bytes + 4) != ProtocolVersion || bytes[7] != 0 || get16(bytes + 10) != 0) return std::nullopt;
  const auto channel = static_cast<Channel>(bytes[6]);
  if (channel < Channel::State || channel > Channel::LogEventOptional) return std::nullopt;
  const std::uint32_t payloadSize = get32(bytes + 32);
  if (payloadSize > MaxMediaPayload || size != HeaderSize + payloadSize) return std::nullopt;
  MediaFrame frame;
  frame.channel = channel; frame.flags = get16(bytes + 8); frame.sequence = get32(bytes + 12);
  frame.timestampMs = get64(bytes + 16); frame.generation = get64(bytes + 24);
  frame.payload.assign(bytes + HeaderSize, bytes + size);
  return frame;
}

ProtocolReply handleRigctld(std::string_view raw, const RigState &state,
                            bool writerLease, bool transmitLease) {
  if (raw.size() > 4096 || raw.find('\0') != std::string_view::npos) return denied(CommandClass::Unsupported, "malformed", -1);
  std::string line = trim(raw);
  while (!line.empty() && (line.back() == '\r' || line.back() == '\n')) line.pop_back();
  bool extended = !line.empty() && std::ispunct(static_cast<unsigned char>(line.front())) && line.front() != '\\' && line.front() != '_';
  const char separator = extended && line.front() != '+' ? line.front() : '\n';
  if (extended) line.erase(line.begin());
  auto args = tokens(line);
  if (args.empty()) return denied(CommandClass::Unsupported, "empty", -1);
  std::string op = args.front();
  if (!op.empty() && op.front() == '\\') op.erase(op.begin());
  const auto finish = [&](ProtocolReply reply, std::string label) {
    if (!extended) return reply;
    std::string body = label + ":" + separator;
    if (reply.accepted && reply.commandClass == CommandClass::Read) body += reply.response;
    body += "RPRT " + std::to_string(reply.errorCode) + separator;
    reply.response = std::move(body); return reply;
  };
  if (op == "f" || op == "get_freq") return finish(readReply("get_freq", std::to_string(state.frequencyHz) + "\n"), "get_freq");
  if (op == "m" || op == "get_mode") return finish(readReply("get_mode", state.mode + "\n" + std::to_string(state.passbandHz) + "\n"), "get_mode");
  if (op == "v" || op == "get_vfo") return finish(readReply("get_vfo", state.vfo + "\n"), "get_vfo");
  if (op == "s" || op == "get_split_vfo") return finish(readReply("get_split_vfo", std::string(state.split ? "1\n" : "0\n") + state.vfo + "\n"), "get_split_vfo");
  if (op == "i" || op == "get_split_freq") return finish(readReply("get_split_freq", std::to_string(state.splitFrequencyHz) + "\n"), "get_split_freq");
  if (op == "x" || op == "get_split_mode") return finish(readReply("get_split_mode", state.splitMode + "\n" + std::to_string(state.passbandHz) + "\n"), "get_split_mode");
  if (op == "j" || op == "get_rit") return finish(readReply("get_rit", std::to_string(state.ritHz) + "\n"), "get_rit");
  if (op == "z" || op == "get_xit") return finish(readReply("get_xit", std::to_string(state.xitHz) + "\n"), "get_xit");
  if (op == "t" || op == "get_ptt") return finish(readReply("get_ptt", state.ptt ? "1\n" : "0\n"), "get_ptt");
  if (op == "dump_state" || op == "dump_caps" || op == "1") {
    return finish(readReply("dump_state", "0\n2\n1\n0\n0\n0\n0\n0\n"), "dump_state");
  }
  const bool ptt = op == "T" || op == "set_ptt";
  const bool safe = op == "F" || op == "set_freq" || op == "M" || op == "set_mode" ||
                    op == "V" || op == "set_vfo" || op == "S" || op == "set_split_vfo" ||
                    op == "I" || op == "set_split_freq" || op == "X" || op == "set_split_mode" ||
                    op == "J" || op == "set_rit" || op == "Z" || op == "set_xit";
  if (!ptt && !safe) return finish(denied(CommandClass::Unsupported, op, -4), op);
  if (!writerLease || (ptt && !transmitLease)) return finish(denied(ptt ? CommandClass::Transmit : CommandClass::SafeSet, op), op);
  if (args.size() < 2) return finish(denied(ptt ? CommandClass::Transmit : CommandClass::SafeSet, op, -1), op);
  if ((op == "F" || op == "set_freq" || op == "I" || op == "set_split_freq") && !number<std::uint64_t>(args[1]))
    return finish(denied(CommandClass::SafeSet, op, -1), op);
  if ((op == "M" || op == "set_mode" || op == "X" || op == "set_split_mode") && !validMode(upper(args[1])))
    return finish(denied(CommandClass::SafeSet, op, -1), op);
  std::vector<std::string> arguments(args.begin() + 1, args.end());
  return finish({true, 0, ptt ? CommandClass::Transmit : CommandClass::SafeSet,
                 op, "RPRT 0\n", std::move(arguments)}, op);
}

ProtocolReply handleTci(std::string_view raw, const RigState &state,
                        bool writerLease, bool transmitLease) {
  if (raw.empty() || raw.size() > 4096 || raw.back() != ';' || raw.find('\0') != std::string_view::npos)
    return denied(CommandClass::Unsupported, "malformed", -1);
  std::string command(raw.substr(0, raw.size() - 1));
  const auto colon = command.find(':');
  const std::string op = upper(command.substr(0, colon));
  const std::string value = colon == std::string::npos ? "" : command.substr(colon + 1);
  if (op == "START" || op == "READY" || op == "PROTOCOL") return readReply(op, "protocol:1.9;device:ShackCQ;ready;start;\n");
  if (op == "VFO" && value.empty()) return readReply(op, "vfo:0,0," + std::to_string(state.frequencyHz) + ";\n");
  if (op == "MODULATION" && value.empty()) return readReply(op, "modulation:0," + state.mode + ";\n");
  if (op == "TRX" && value.empty()) return readReply(op, std::string("trx:0,") + (state.ptt ? "true" : "false") + ";\n");
  const bool tx = op == "TRX" || op == "TUNE" || op == "DRIVE";
  const bool safe = op == "VFO" || op == "IF" || op == "MODULATION" || op == "SPLIT_ENABLE" ||
                    op == "RIT" || op == "XIT" || op == "RX_FILTER_BAND" || op == "RX_VOLUME";
  if (!tx && !safe) return denied(CommandClass::Unsupported, op, -4);
  if (!writerLease || (tx && !transmitLease)) return denied(tx ? CommandClass::Transmit : CommandClass::SafeSet, op);
  return {true, 0, tx ? (op == "TUNE" ? CommandClass::Tune : CommandClass::Transmit) : CommandClass::SafeSet,
          op, "", commaTokens(value)};
}

std::string roleName(Role role) {
  switch (role) { case Role::Observer: return "OBSERVER"; case Role::Operator: return "OPERATOR"; case Role::Admin: return "ADMIN"; }
  return "OBSERVER";
}

} // namespace shackcq::remote
