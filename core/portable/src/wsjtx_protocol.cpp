#include "kx3/wsjtx_protocol.hpp"

#include <algorithm>
#include <cstring>
#include <limits>
#include <string_view>

namespace kx3::wsjtx {
namespace {

class Reader {
public:
    Reader(const std::uint8_t* data, std::size_t size) : data_(data), size_(size) {}
    std::size_t remaining() const { return size_ - offset_; }
    bool ok() const { return ok_; }

    bool u8(std::uint8_t& value) {
        if (remaining() < 1) return fail();
        value = data_[offset_++];
        return true;
    }
    bool boolean(bool& value) {
        std::uint8_t raw{};
        if (!u8(raw) || raw > 1) return fail();
        value = raw != 0;
        return true;
    }
    bool u32(std::uint32_t& value) {
        if (remaining() < 4) return fail();
        value = (static_cast<std::uint32_t>(data_[offset_]) << 24U) |
                (static_cast<std::uint32_t>(data_[offset_ + 1]) << 16U) |
                (static_cast<std::uint32_t>(data_[offset_ + 2]) << 8U) |
                static_cast<std::uint32_t>(data_[offset_ + 3]);
        offset_ += 4;
        return true;
    }
    bool i32(std::int32_t& value) {
        std::uint32_t raw{};
        if (!u32(raw)) return false;
        value = static_cast<std::int32_t>(raw);
        return true;
    }
    bool i64(std::int64_t& value) {
        std::uint64_t raw{};
        if (!u64(raw)) return false;
        value = static_cast<std::int64_t>(raw);
        return true;
    }
    bool u64(std::uint64_t& value) {
        std::uint32_t high{}, low{};
        if (!u32(high) || !u32(low)) return false;
        value = (static_cast<std::uint64_t>(high) << 32U) | low;
        return true;
    }
    bool floating(double& value) {
        std::uint64_t bits{};
        if (!u64(bits)) return false;
        static_assert(sizeof(bits) == sizeof(value));
        std::memcpy(&value, &bits, sizeof(value));
        return true;
    }
    bool bytes(std::string& value, std::size_t maximum, bool nullable = true) {
        std::uint32_t length{};
        if (!u32(length)) return false;
        if (length == std::numeric_limits<std::uint32_t>::max() && nullable) {
            value.clear();
            return true;
        }
        if (length > maximum || length > remaining()) return fail();
        value.assign(reinterpret_cast<const char*>(data_ + offset_), length);
        offset_ += length;
        return valid_utf8(value) || fail();
    }

private:
    static bool valid_utf8(std::string_view value) {
        for (std::size_t i = 0; i < value.size();) {
            const auto lead = static_cast<unsigned char>(value[i]);
            if (lead < 0x80U) {
                if (lead == 0U) return false;
                ++i;
                continue;
            }
            unsigned count{};
            std::uint32_t code{};
            if ((lead & 0xE0U) == 0xC0U) { count = 2; code = lead & 0x1FU; }
            else if ((lead & 0xF0U) == 0xE0U) { count = 3; code = lead & 0x0FU; }
            else if ((lead & 0xF8U) == 0xF0U) { count = 4; code = lead & 0x07U; }
            else return false;
            if (i + count > value.size()) return false;
            for (unsigned j = 1; j < count; ++j) {
                const auto continuation = static_cast<unsigned char>(value[i + j]);
                if ((continuation & 0xC0U) != 0x80U) return false;
                code = (code << 6U) | (continuation & 0x3FU);
            }
            if ((count == 2 && code < 0x80U) || (count == 3 && code < 0x800U) ||
                (count == 4 && code < 0x10000U) || code > 0x10FFFFU ||
                (code >= 0xD800U && code <= 0xDFFFU)) return false;
            i += count;
        }
        return true;
    }
    bool fail() { ok_ = false; return false; }
    const std::uint8_t* data_{};
    std::size_t size_{};
    std::size_t offset_{};
    bool ok_{true};
};

void set_error(ParseError* error, ParseError value) {
    if (error != nullptr) *error = value;
}

std::string upper(std::string_view value) {
    std::string result(value);
    std::transform(result.begin(), result.end(), result.begin(), [](unsigned char character) {
        return character >= 'a' && character <= 'z' ? static_cast<char>(character - 'a' + 'A') :
                                                      static_cast<char>(character);
    });
    return result;
}

std::optional<std::string> adif_field(std::string_view document, std::string_view wanted) {
    const std::string wanted_upper = upper(wanted);
    std::size_t at{};
    while ((at = document.find('<', at)) != std::string_view::npos) {
        const auto close = document.find('>', at + 1);
        if (close == std::string_view::npos || close - at > 80) return std::nullopt;
        const std::string header = upper(document.substr(at + 1, close - at - 1));
        const auto colon = header.find(':');
        const std::string name = header.substr(0, colon);
        if (name == "EOR" || name == "EOH") { at = close + 1; continue; }
        if (colon == std::string::npos) return std::nullopt;
        const auto second_colon = header.find(':', colon + 1);
        const std::string length_text = header.substr(colon + 1,
            second_colon == std::string::npos ? std::string::npos : second_colon - colon - 1);
        if (length_text.empty() || length_text.size() > 8 ||
            !std::all_of(length_text.begin(), length_text.end(), [](unsigned char c) { return c >= '0' && c <= '9'; }))
            return std::nullopt;
        std::size_t length{};
        for (const char digit : length_text) {
            length = length * 10U + static_cast<unsigned>(digit - '0');
            if (length > kMaxAdifBytes) return std::nullopt;
        }
        if (close + 1U + length > document.size()) return std::nullopt;
        if (name == wanted_upper) return std::string(document.substr(close + 1, length));
        at = close + 1U + length;
    }
    return std::string{};
}

bool parse_status(Reader& reader, Status& value) {
    if (!reader.u64(value.dial_frequency_hz) ||
        !reader.bytes(value.mode, 32) || !reader.bytes(value.dx_call, 32) ||
        !reader.bytes(value.report, 32) || !reader.bytes(value.tx_mode, 32) ||
        !reader.boolean(value.tx_enabled) || !reader.boolean(value.transmitting) ||
        !reader.boolean(value.decoding) || !reader.u32(value.rx_df_hz) ||
        !reader.u32(value.tx_df_hz) || !reader.bytes(value.de_call, 32) ||
        !reader.bytes(value.de_grid, 16) || !reader.bytes(value.dx_grid, 16) ||
        !reader.boolean(value.tx_watchdog) || !reader.bytes(value.sub_mode, 32) ||
        !reader.boolean(value.fast_mode) || !reader.u8(value.special_operation_mode)) return false;
    if (reader.remaining() == 0) return true;
    if (!reader.u32(value.frequency_tolerance) || !reader.u32(value.tr_period) ||
        !reader.bytes(value.configuration_name, 128) || !reader.bytes(value.tx_message, kMaxTextBytes))
        return false;
    return reader.remaining() == 0;
}

bool parse_decode(Reader& reader, Decode& value) {
    return reader.boolean(value.is_new) && reader.u32(value.milliseconds_since_midnight) &&
        value.milliseconds_since_midnight < 86400000U && reader.i32(value.snr_db) &&
        value.snr_db >= -100 && value.snr_db <= 100 && reader.floating(value.delta_time_seconds) &&
        reader.u32(value.delta_frequency_hz) && reader.bytes(value.mode, 32) &&
        reader.bytes(value.message, kMaxTextBytes) && reader.boolean(value.low_confidence) &&
        reader.boolean(value.off_air) && reader.remaining() == 0;
}

bool qdatetime(Reader& reader, std::int64_t& utc_milliseconds) {
    std::int64_t julian_day{};
    std::uint32_t milliseconds{};
    std::uint8_t specification{};
    if (!reader.i64(julian_day) || !reader.u32(milliseconds) || milliseconds >= 86'400'000U ||
        !reader.u8(specification)) return false;
    std::int32_t offset_seconds{};
    if (specification == 2 && !reader.i32(offset_seconds)) return false;
    // WSJT-X sends UTC (1) or an explicit UTC offset (2). Reject local/time-zone
    // forms because they are not deterministic on a headless Agent.
    if (specification != 1 && specification != 2) return false;
    constexpr std::int64_t UnixEpochJulianDay = 2'440'588;
    if (julian_day < UnixEpochJulianDay || julian_day > 5'000'000) return false;
    utc_milliseconds = (julian_day - UnixEpochJulianDay) * 86'400'000LL +
                       milliseconds - static_cast<std::int64_t>(offset_seconds) * 1000LL;
    return true;
}

bool parse_qso_logged(Reader& reader, QsoLogged& value) {
    return qdatetime(reader, value.off_utc_milliseconds) &&
        reader.bytes(value.dx_call, 32) && !value.dx_call.empty() &&
        reader.bytes(value.dx_grid, 16) && reader.u64(value.tx_frequency_hz) &&
        value.tx_frequency_hz <= 10'500'000'000ULL && reader.bytes(value.mode, 32) &&
        reader.bytes(value.report_sent, 32) && reader.bytes(value.report_received, 32) &&
        reader.bytes(value.tx_power, 32) && reader.bytes(value.comments, kMaxTextBytes) &&
        reader.bytes(value.name, 128) && qdatetime(reader, value.on_utc_milliseconds) &&
        reader.remaining() == 0;
}

bool parse_logged_adif(Reader& reader, LoggedAdif& value) {
    if (!reader.bytes(value.raw, kMaxAdifBytes, false) || reader.remaining() != 0 ||
        (value.raw.find("<EOR>") == std::string::npos && value.raw.find("<eor>") == std::string::npos))
        return false;
    const auto call = adif_field(value.raw, "CALL");
    const auto band = adif_field(value.raw, "BAND");
    const auto mode = adif_field(value.raw, "MODE");
    const auto submode = adif_field(value.raw, "SUBMODE");
    const auto frequency = adif_field(value.raw, "FREQ");
    const auto grid = adif_field(value.raw, "GRIDSQUARE");
    const auto date = adif_field(value.raw, "QSO_DATE");
    const auto time = adif_field(value.raw, "TIME_ON");
    if (!call || !band || !mode || !submode || !frequency || !grid || !date || !time || call->empty()) return false;
    value.call = upper(*call);
    value.band = upper(*band);
    value.mode = upper(*mode);
    value.submode = upper(*submode);
    value.frequency_mhz = *frequency;
    value.gridsquare = upper(*grid);
    value.qso_date = *date;
    value.time_on = *time;
    return true;
}

}  // namespace

std::optional<Message> parse_datagram(const std::uint8_t* data, std::size_t size,
                                      ParseError* error) {
    set_error(error, ParseError::None);
    if (data == nullptr || size == 0) { set_error(error, ParseError::Empty); return std::nullopt; }
    if (size > kMaxDatagramBytes) { set_error(error, ParseError::TooLarge); return std::nullopt; }
    if (size < 16) { set_error(error, ParseError::Truncated); return std::nullopt; }
    Reader reader(data, size);
    std::uint32_t magic{}, raw_type{};
    Message message;
    if (!reader.u32(magic) || magic != kMagic) { set_error(error, ParseError::BadMagic); return std::nullopt; }
    if (!reader.u32(message.header.schema)) { set_error(error, ParseError::Truncated); return std::nullopt; }
    if (message.header.schema < 2 || message.header.schema > 3) {
        set_error(error, ParseError::UnsupportedSchema); return std::nullopt;
    }
    if (!reader.u32(raw_type) || !reader.bytes(message.header.id, kMaxIdentifierBytes, false)) {
        set_error(error, reader.ok() ? ParseError::InvalidText : ParseError::InvalidLength);
        return std::nullopt;
    }
    if (message.header.id.empty()) { set_error(error, ParseError::InvalidText); return std::nullopt; }
    if (raw_type == static_cast<std::uint32_t>(MessageType::Status)) {
        message.header.type = MessageType::Status;
        Status value;
        if (!parse_status(reader, value)) { set_error(error, ParseError::Truncated); return std::nullopt; }
        message.payload = std::move(value);
    } else if (raw_type == static_cast<std::uint32_t>(MessageType::Decode)) {
        message.header.type = MessageType::Decode;
        Decode value;
        if (!parse_decode(reader, value)) { set_error(error, ParseError::InvalidLength); return std::nullopt; }
        message.payload = std::move(value);
    } else if (raw_type == static_cast<std::uint32_t>(MessageType::QsoLogged)) {
        message.header.type = MessageType::QsoLogged;
        QsoLogged value;
        if (!parse_qso_logged(reader, value)) { set_error(error, ParseError::InvalidLength); return std::nullopt; }
        message.payload = std::move(value);
    } else if (raw_type == static_cast<std::uint32_t>(MessageType::LoggedAdif)) {
        message.header.type = MessageType::LoggedAdif;
        LoggedAdif value;
        if (!parse_logged_adif(reader, value)) { set_error(error, ParseError::InvalidAdif); return std::nullopt; }
        message.payload = std::move(value);
    } else {
        set_error(error, ParseError::UnsupportedType);
        return std::nullopt;
    }
    return message;
}

const char* parse_error_text(ParseError error) {
    switch (error) {
    case ParseError::None: return "OK";
    case ParseError::Empty: return "EMPTY DATAGRAM";
    case ParseError::TooLarge: return "DATAGRAM TOO LARGE";
    case ParseError::Truncated: return "TRUNCATED DATAGRAM";
    case ParseError::BadMagic: return "BAD MAGIC";
    case ParseError::UnsupportedSchema: return "UNSUPPORTED SCHEMA";
    case ParseError::UnsupportedType: return "UNSUPPORTED MESSAGE TYPE";
    case ParseError::InvalidLength: return "INVALID FIELD LENGTH";
    case ParseError::InvalidText: return "INVALID UTF-8 TEXT";
    case ParseError::TrailingData: return "TRAILING DATA";
    case ParseError::InvalidAdif: return "INVALID LOGGED ADIF";
    }
    return "UNKNOWN PARSE ERROR";
}

}  // namespace kx3::wsjtx
