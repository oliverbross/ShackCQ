#include "shackcq/core.h"

#include <algorithm>
#include <charconv>
#include <array>
#include <chrono>
#include <cctype>
#include <cstdio>
#include <cstring>
#include <iomanip>
#include <sstream>
#include <string>
#include <string_view>

namespace {
constexpr size_t kMaxBufferedBytes = 512;
constexpr size_t kMaxFrameBytes = 128;

struct CoreContext {
    shackcq_radio_state state{};
    std::string pending;
};

void copy_text(char *destination, size_t size, std::string_view value) {
    if (size == 0) return;
    const size_t count = std::min(size - 1, value.size());
    std::memcpy(destination, value.data(), count);
    destination[count] = '\0';
}

std::string upper(std::string value) {
    std::transform(value.begin(), value.end(), value.begin(),
                   [](unsigned char c) { return static_cast<char>(std::toupper(c)); });
    return value;
}

bool all_digits(std::string_view value) {
    return !value.empty() && std::all_of(value.begin(), value.end(),
        [](unsigned char c) { return std::isdigit(c) != 0; });
}

bool has_suffix(std::string_view value, std::string_view suffix) {
    return value.size() >= suffix.size() &&
           value.substr(value.size() - suffix.size()) == suffix;
}

bool unsigned_payload(std::string_view frame, std::string_view prefix, int& output) {
    if (frame.rfind(prefix, 0) != 0) return false;
    const auto payload = frame.substr(prefix.size());
    if (!all_digits(payload)) return false;
    int parsed = 0;
    const auto result = std::from_chars(payload.data(), payload.data() + payload.size(), parsed);
    if (result.ec != std::errc{} || result.ptr != payload.data() + payload.size()) return false;
    output = parsed;
    return true;
}

bool spaced_unsigned_payload(std::string_view frame, std::string_view prefix, int& output) {
    if (frame.rfind(prefix, 0) != 0) return false;
    auto payload = frame.substr(prefix.size());
    if (!payload.empty() && payload.front() == ' ') payload.remove_prefix(1);
    if (!all_digits(payload)) return false;
    int parsed = 0;
    const auto result = std::from_chars(payload.data(), payload.data() + payload.size(), parsed);
    if (result.ec != std::errc{} || result.ptr != payload.data() + payload.size()) return false;
    output = parsed;
    return true;
}

bool bool_payload(std::string_view frame, std::string_view prefix, int& output) {
    int value = 0;
    if (!unsigned_payload(frame, prefix, value)) return false;
    output = value != 0 ? 1 : 0;
    return true;
}

const char *mode_name(int code) {
    switch (code) {
        case 1: return "LSB";
        case 2: return "USB";
        case 3: return "CW";
        case 4: return "FM";
        case 5: return "AM";
        case 6: return "DATA";
        case 7: return "CW-R";
        case 9: return "DATA-R";
        default: return "UNKNOWN";
    }
}

bool apply_frame(CoreContext &context, const std::string &raw) {
    if (raw.size() < 2 || raw.size() > kMaxFrameBytes) return false;
    const std::string frame = upper(raw);
    auto next = context.state;
    bool recognized = false;

    if (frame.rfind("ID", 0) == 0) {
        copy_text(next.identity, sizeof(next.identity), frame.substr(2));
        if (frame.find("KX3") != std::string::npos) copy_text(next.model, sizeof(next.model), "KX3");
        else if (frame.find("KX2") != std::string::npos) copy_text(next.model, sizeof(next.model), "KX2");
        else if (std::strlen(next.model) == 0 || std::string_view(next.model) == "UNIDENTIFIED")
            copy_text(next.model, sizeof(next.model), "ELECRAFT");
        next.connected = 1;
        recognized = true;
    } else if (frame.rfind("K3", 0) == 0 && frame.size() == 3 &&
               (frame[2] == '0' || frame[2] == '1')) {
        if (std::strlen(next.model) == 0 || std::string_view(next.model) == "UNIDENTIFIED")
            copy_text(next.model, sizeof(next.model), "ELECRAFT K3 FAMILY");
        next.connected = 1;
        recognized = true;
    } else if (frame.rfind("OM", 0) == 0) {
        if (has_suffix(frame, "02")) copy_text(next.model, sizeof(next.model), "KX3");
        else if (has_suffix(frame, "01")) copy_text(next.model, sizeof(next.model), "KX2");
        else if (std::string_view(next.model).find("KX") == std::string_view::npos)
            copy_text(next.model, sizeof(next.model), "K3/K3S");
        next.connected = 1;
        recognized = true;
    } else if (frame.rfind("FA", 0) == 0 && frame.size() >= 13 && all_digits(std::string_view(frame).substr(2, 11))) {
        next.vfo_a_hz = std::stoull(frame.substr(2, 11));
        recognized = true;
    } else if (frame.rfind("FB", 0) == 0 && frame.size() >= 13 && all_digits(std::string_view(frame).substr(2, 11))) {
        next.vfo_b_hz = std::stoull(frame.substr(2, 11));
        recognized = true;
    } else if (frame.rfind("MD", 0) == 0 && frame.size() >= 3 && std::isdigit(static_cast<unsigned char>(frame[2]))) {
        copy_text(next.mode, sizeof(next.mode), mode_name(frame[2] - '0'));
        recognized = true;
    } else if (frame.rfind("IF", 0) == 0 && frame.size() >= 36) {
        const auto payload = std::string_view(frame).substr(2);
        if (all_digits(payload.substr(0, 11))) {
            next.vfo_a_hz = std::stoull(std::string(payload.substr(0, 11)));
            if ((payload[16] == '+' || payload[16] == '-') && all_digits(payload.substr(17, 4))) {
                next.rit_xit_offset_hz = std::stoi(std::string(payload.substr(17, 4)));
                if (payload[16] == '-') next.rit_xit_offset_hz = -next.rit_xit_offset_hz;
            }
            if (payload[21] == '0' || payload[21] == '1') next.rit = payload[21] - '0';
            if (payload[22] == '0' || payload[22] == '1') next.xit = payload[22] - '0';
            if (payload[26] == '0' || payload[26] == '1') next.transmitting = payload[26] - '0';
            if (std::isdigit(static_cast<unsigned char>(payload[27])))
                copy_text(next.mode, sizeof(next.mode), mode_name(payload[27] - '0'));
            if (payload[28] == '0' || payload[28] == '1') next.rx_vfo = payload[28] - '0';
            if (payload[30] == '0' || payload[30] == '1') next.split = payload[30] - '0';
            if (std::isdigit(static_cast<unsigned char>(payload[32]))) next.data_submode = payload[32] - '0';
            recognized = true;
        }
    } else if (frame.rfind("TQ", 0) == 0 && frame.size() >= 3 && (frame[2] == '0' || frame[2] == '1')) {
        next.transmitting = frame[2] == '1' ? 1 : 0;
        recognized = true;
    } else if (frame.rfind("DS", 0) == 0 && frame.size() >= 12) {
        const auto icons = static_cast<unsigned char>(frame[10]);
        const auto extended_icons = static_cast<unsigned char>(frame[11]);
        next.preamp = (icons & 0x10U) != 0U;
        next.attenuator = (icons & 0x08U) != 0U;
        next.rit = (icons & 0x02U) != 0U;
        next.xit = (icons & 0x01U) != 0U;
        next.cwt = (extended_icons & 0x08U) != 0U;
        recognized = true;
    } else if (frame.rfind("GT", 0) == 0 && frame.size() >= 5 &&
               all_digits(std::string_view(frame).substr(2, 3))) {
        next.agc_mode = std::stoi(frame.substr(2, 3));
        if (frame.size() >= 6 && frame[5] == '0') next.agc_mode = 0;
        recognized = true;
    } else if (unsigned_payload(frame, "SM", next.meter)) {
        next.meter = std::clamp(next.meter, 0, 30); recognized = true;
    } else if (unsigned_payload(frame, "SW", next.swr_tenths)) {
        next.swr_tenths = std::clamp(next.swr_tenths, 0, 999); recognized = true;
    } else if (unsigned_payload(frame, "PO", next.rf_output_tenths)) {
        next.rf_output_tenths = std::clamp(next.rf_output_tenths, 0, 999); recognized = true;
    } else if (unsigned_payload(frame, "AG", next.af_gain)) {
        next.af_gain = std::clamp(next.af_gain, 0, 255); recognized = true;
    } else if (unsigned_payload(frame, "RG", next.rf_gain)) {
        next.rf_gain = std::clamp(next.rf_gain, 0, 250); recognized = true;
    } else if (int bandwidth_units = 0; unsigned_payload(frame, "BW", bandwidth_units)) {
        next.bandwidth_hz = std::clamp(bandwidth_units, 0, 9999) * 10; recognized = true;
    } else if (unsigned_payload(frame, "PC", next.power_w)) {
        next.power_w = std::clamp(next.power_w, 0, 100); recognized = true;
    } else if (unsigned_payload(frame, "ML", next.monitor_level)) {
        next.monitor_level = std::clamp(next.monitor_level, 0, 60); recognized = true;
    } else if (unsigned_payload(frame, "MG", next.mic_gain)) {
        next.mic_gain = std::clamp(next.mic_gain, 0, 60); recognized = true;
    } else if (unsigned_payload(frame, "KS", next.keyer_speed)) {
        next.keyer_speed = std::clamp(next.keyer_speed, 8, 50); recognized = true;
    } else if (spaced_unsigned_payload(frame, "IS", next.if_shift_hz)) {
        next.if_shift_hz = std::clamp(next.if_shift_hz, 0, 9999); recognized = true;
    } else if (bool_payload(frame, "PA", next.preamp)) {
        recognized = true;
    } else if (unsigned_payload(frame, "RA", next.attenuator)) {
        next.attenuator = next.attenuator != 0 ? 1 : 0; recognized = true;
    } else if (bool_payload(frame, "RT", next.rit)) {
        recognized = true;
    } else if (bool_payload(frame, "XT", next.xit)) {
        recognized = true;
    } else if (unsigned_payload(frame, "FR", next.rx_vfo)) {
        next.rx_vfo = std::clamp(next.rx_vfo, 0, 1); next.split = next.rx_vfo != next.tx_vfo; recognized = true;
    } else if (unsigned_payload(frame, "FT", next.tx_vfo)) {
        next.tx_vfo = std::clamp(next.tx_vfo, 0, 1); next.split = next.rx_vfo != next.tx_vfo; recognized = true;
    }

    if (recognized) {
        const uint64_t rx_base = next.rx_vfo == 1 ? next.vfo_b_hz : next.vfo_a_hz;
        const uint64_t tx_base = next.tx_vfo == 1 ? next.vfo_b_hz : next.vfo_a_hz;
        const auto with_offset = [](uint64_t base, int offset) -> uint64_t {
            if (offset >= 0) return base + static_cast<uint64_t>(offset);
            const uint64_t magnitude = static_cast<uint64_t>(-static_cast<int64_t>(offset));
            return base > magnitude ? base - magnitude : 0U;
        };
        next.effective_rx_hz = with_offset(rx_base, next.rit ? next.rit_xit_offset_hz : 0);
        next.effective_tx_hz = with_offset(tx_base, next.xit ? next.rit_xit_offset_hz : 0);
        next.updated_monotonic_ms = static_cast<uint64_t>(std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now().time_since_epoch()).count());
        next.revision = context.state.revision + 1;
        context.state = next;
    }
    return recognized;
}

std::string normalized(std::string value) {
    value.erase(std::remove_if(value.begin(), value.end(),
        [](unsigned char c) { return std::isspace(c) != 0; }), value.end());
    return upper(value);
}

uint64_t fnv1a(std::string_view value) {
    uint64_t hash = 1469598103934665603ULL;
    for (unsigned char byte : value) {
        hash ^= byte;
        hash *= 1099511628211ULL;
    }
    return hash;
}

std::string adif_field(std::string_view name, std::string_view value) {
    return "<" + std::string(name) + ":" + std::to_string(value.size()) + ">" + std::string(value);
}
}  // namespace

struct shackcq_context { CoreContext core; };

shackcq_context *shackcq_context_create(void) {
    auto *context = new shackcq_context{};
    shackcq_context_reset(context);
    return context;
}

void shackcq_context_destroy(shackcq_context *context) { delete context; }

void shackcq_context_reset(shackcq_context *context) {
    if (!context) return;
    context->core = {};
    copy_text(context->core.state.identity, sizeof(context->core.state.identity), "UNAVAILABLE");
    copy_text(context->core.state.model, sizeof(context->core.state.model), "UNIDENTIFIED");
    copy_text(context->core.state.mode, sizeof(context->core.state.mode), "--");
    context->core.state.vfo_a_hz = 0;
    context->core.state.vfo_b_hz = 0;
    context->core.state.connected = 0;
    context->core.state.transmitting = 0;
    context->core.state.meter = 0;
    context->core.state.swr_tenths = -1;
    context->core.state.rf_output_tenths = -1;
    context->core.state.agc_mode = -1;
    context->core.state.cwt = 0;
    context->core.state.monitor_level = -1;
    context->core.state.mic_gain = -1;
    context->core.state.keyer_speed = -1;
    context->core.state.if_shift_hz = -1;
    context->core.state.data_submode = -1;
    context->core.state.revision = 0;
}

int shackcq_context_feed(shackcq_context *context, const char *bytes, size_t length) {
    if (!context || !bytes || length == 0 || length > kMaxBufferedBytes) return 0;
    if (context->core.pending.size() + length > kMaxBufferedBytes) context->core.pending.clear();
    context->core.pending.append(bytes, length);
    int applied = 0;
    size_t end = 0;
    while ((end = context->core.pending.find(';')) != std::string::npos) {
        const std::string frame = context->core.pending.substr(0, end);
        context->core.pending.erase(0, end + 1);
        if (apply_frame(context->core, frame)) ++applied;
    }
    return applied;
}

shackcq_radio_state shackcq_context_state(const shackcq_context *context) {
    return context ? context->core.state : shackcq_radio_state{};
}

shackcq_command_class shackcq_classify_command(const char *command) {
    if (!command) return SHACKCQ_COMMAND_UNKNOWN;
    const std::string value = upper(command);
    static constexpr std::array<std::string_view, 27> safe{
        "K3;", "OM;", "ID;", "FA;", "FB;", "MD;", "IF;", "TQ;", "SM;", "SW;", "PO;",
        "AG;", "RG;", "BW;", "PC;", "ML;", "MG;", "KS;", "IS;", "PA;", "RA;", "RT;", "XT;", "FR;", "FT;", "DS;", "GT;"
    };
    if (std::find(safe.begin(), safe.end(), value) != safe.end()) return SHACKCQ_COMMAND_READ_ONLY;
    if (value.rfind("TX", 0) == 0 || value.rfind("RX", 0) == 0 ||
        value.rfind("SWT", 0) == 0 || value.rfind("SWH", 0) == 0 ||
        value.rfind("KY", 0) == 0) return SHACKCQ_COMMAND_TRANSMIT;
    if (value.size() > 3 && value.back() == ';') return SHACKCQ_COMMAND_MUTATION;
    return SHACKCQ_COMMAND_UNKNOWN;
}

size_t shackcq_startup_command_count(void) { return 0; }

int shackcq_qso_identity(char *output, size_t output_size, const char *callsign,
                    const char *utc_iso8601, uint64_t frequency_hz, const char *mode) {
    if (!output || output_size < 20 || !callsign || !utc_iso8601 || !mode) return 0;
    const std::string key = normalized(callsign) + "|" + utc_iso8601 + "|" +
                            std::to_string(frequency_hz) + "|" + normalized(mode);
    std::ostringstream stream;
    stream << "rw-" << std::hex << std::setfill('0') << std::setw(16) << fnv1a(key);
    return std::snprintf(output, output_size, "%s", stream.str().c_str()) > 0 ? 1 : 0;
}

int shackcq_adif_serialize(char *output, size_t output_size, const char *identity,
                      const char *callsign, const char *date_yyyymmdd,
                      const char *time_hhmmss, uint64_t frequency_hz,
                      const char *mode, const char *rst_sent, const char *rst_received) {
    if (!output || !identity || !callsign || !date_yyyymmdd || !time_hhmmss || !mode) return 0;
    std::ostringstream mhz;
    mhz << std::fixed << std::setprecision(6) << static_cast<double>(frequency_hz) / 1000000.0;
    std::string record = adif_field("CALL", normalized(callsign)) +
        adif_field("QSO_DATE", date_yyyymmdd) + adif_field("TIME_ON", time_hhmmss) +
        adif_field("FREQ", mhz.str()) + adif_field("MODE", normalized(mode));
    if (rst_sent && *rst_sent) record += adif_field("RST_SENT", rst_sent);
    if (rst_received && *rst_received) record += adif_field("RST_RCVD", rst_received);
    record += adif_field("APP_KX3TOUCH_UUID", identity) + "<EOR>\n";
    if (record.size() + 1 > output_size) return 0;
    std::memcpy(output, record.c_str(), record.size() + 1);
    return static_cast<int>(record.size());
}

const char *shackcq_core_version(void) { return "0.1.0"; }
