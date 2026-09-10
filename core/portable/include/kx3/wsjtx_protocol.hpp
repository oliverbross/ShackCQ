#pragma once

#include <cstddef>
#include <cstdint>
#include <optional>
#include <string>
#include <variant>

namespace kx3::wsjtx {

constexpr std::uint32_t kMagic = 0xadbccbdaU;
constexpr std::size_t kMaxDatagramBytes = 8192;
constexpr std::size_t kMaxIdentifierBytes = 64;
constexpr std::size_t kMaxTextBytes = 512;
constexpr std::size_t kMaxAdifBytes = 4096;

enum class MessageType : std::uint32_t { Status = 1, Decode = 2, QsoLogged = 5, LoggedAdif = 12 };
enum class ParseError : std::uint8_t {
    None,
    Empty,
    TooLarge,
    Truncated,
    BadMagic,
    UnsupportedSchema,
    UnsupportedType,
    InvalidLength,
    InvalidText,
    TrailingData,
    InvalidAdif,
};

struct Header {
    std::uint32_t schema{};
    MessageType type{MessageType::Status};
    std::string id;
};

struct Status {
    std::uint64_t dial_frequency_hz{};
    std::string mode;
    std::string dx_call;
    std::string report;
    std::string tx_mode;
    bool tx_enabled{};
    bool transmitting{};
    bool decoding{};
    std::uint32_t rx_df_hz{};
    std::uint32_t tx_df_hz{};
    std::string de_call;
    std::string de_grid;
    std::string dx_grid;
    bool tx_watchdog{};
    std::string sub_mode;
    bool fast_mode{};
    std::uint8_t special_operation_mode{};
    std::uint32_t frequency_tolerance{};
    std::uint32_t tr_period{};
    std::string configuration_name;
    std::string tx_message;
};

struct Decode {
    bool is_new{};
    std::uint32_t milliseconds_since_midnight{};
    std::int32_t snr_db{};
    double delta_time_seconds{};
    std::uint32_t delta_frequency_hz{};
    std::string mode;
    std::string message;
    bool low_confidence{};
    bool off_air{};
};

struct LoggedAdif {
    std::string raw;
    std::string call;
    std::string band;
    std::string mode;
    std::string submode;
    std::string frequency_mhz;
    std::string gridsquare;
    std::string qso_date;
    std::string time_on;
};

struct QsoLogged {
    std::int64_t off_utc_milliseconds{};
    std::string dx_call;
    std::string dx_grid;
    std::uint64_t tx_frequency_hz{};
    std::string mode;
    std::string report_sent;
    std::string report_received;
    std::string tx_power;
    std::string comments;
    std::string name;
    std::int64_t on_utc_milliseconds{};
};

using Payload = std::variant<Status, Decode, QsoLogged, LoggedAdif>;

struct Message {
    Header header;
    Payload payload;
};

std::optional<Message> parse_datagram(const std::uint8_t* data, std::size_t size,
                                      ParseError* error = nullptr);
const char* parse_error_text(ParseError error);

}  // namespace kx3::wsjtx
