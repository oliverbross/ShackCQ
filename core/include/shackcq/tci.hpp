// SPDX-License-Identifier: GPL-3.0-only
#pragma once

#include <cstddef>
#include <cstdint>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

namespace shackcq::tci {

constexpr std::size_t BinaryHeaderBytes = 64U;
constexpr std::uint32_t Float32Format = 3U;
constexpr std::size_t DefaultMaximumMessageBytes = 8U * 1024U * 1024U;
constexpr std::uint32_t DefaultMaximumReceivers = 8U;

enum class DataType : std::uint32_t {
    Iq = 0U,
    RxAudio = 1U,
    TxAudio = 2U,
    TxChrono = 3U,
};

enum class BinaryError {
    None,
    HeaderTooShort,
    MessageTooLarge,
    ReceiverOutOfRange,
    UnsupportedFormat,
    InvalidSampleRate,
    InvalidChannels,
    UnknownDataType,
    LengthOverflow,
    PayloadLengthMismatch,
    NonFiniteSample,
};

struct StatusCommand {
    std::string name;
    std::string arguments;
};

struct BinaryHeader {
    std::uint32_t receiver{};
    std::uint32_t sample_rate{};
    std::uint32_t format{};
    std::uint32_t value_count{};
    DataType data_type{DataType::Iq};
    std::uint32_t channels{};
};

struct BinaryFrame {
    BinaryHeader header;
    std::vector<float> values;
};

std::vector<StatusCommand> parse_status(std::string_view text);
std::optional<std::string> canonical_mode(std::string_view mode);

std::optional<std::string> build_vfo(std::uint32_t receiver, std::uint32_t channel,
                                     std::uint64_t frequency_hz);
std::optional<std::string> build_if(std::uint32_t receiver, std::uint32_t channel,
                                    std::int64_t offset_hz);
std::optional<std::string> build_mode(std::uint32_t receiver, std::string_view mode);
std::optional<std::string> build_volume(std::int32_t decibels);
std::optional<std::string> build_split_enable(std::uint32_t receiver, bool enabled);
std::optional<std::string> build_iq_sample_rate(std::uint32_t sample_rate);
std::optional<std::string> build_iq_start(std::uint32_t receiver);
std::optional<std::string> build_iq_stop(std::uint32_t receiver);
std::optional<std::string> build_audio_start(std::uint32_t receiver);
std::optional<std::string> build_audio_stop(std::uint32_t receiver);
std::optional<std::string> build_rx_enable(std::uint32_t receiver, bool enabled);
std::optional<std::string> build_mute(std::uint32_t receiver, bool muted);
std::optional<std::string> build_safe_stop(std::uint32_t receiver);
std::optional<std::string> build_trx(std::uint32_t receiver, bool transmitting);
std::optional<std::string> build_tune(std::uint32_t receiver, bool enabled);
std::optional<std::string> build_drive(std::uint32_t receiver, std::uint32_t percent);
std::optional<std::string> build_tune_drive(std::uint32_t receiver, std::uint32_t percent);
std::optional<std::string> build_audio_sample_rate(std::uint32_t sample_rate);
std::optional<std::string> build_tx_sensors_enable(std::uint32_t receiver, bool enabled);
std::optional<std::string> build_xit_enable(std::uint32_t receiver, bool enabled);
std::optional<std::string> build_xit_offset(std::uint32_t receiver, std::int64_t offset_hz);
std::optional<std::string> build_monitor_enable(std::uint32_t receiver, bool enabled);

std::optional<BinaryFrame> decode_binary(
    const std::uint8_t *message,
    std::size_t message_size,
    BinaryError *error = nullptr,
    std::uint32_t maximum_receivers = DefaultMaximumReceivers,
    std::size_t maximum_message_bytes = DefaultMaximumMessageBytes);

std::vector<std::uint8_t> build_binary_for_test(
    DataType data_type,
    std::uint32_t receiver,
    std::uint32_t sample_rate,
    std::uint32_t channels,
    const std::vector<float> &values,
    std::uint32_t chrono_requested_values = 0U);

// Builds the only audited Android TX payload: bounded FLOAT32 stereo TCI
// TX_AUDIO. target_frame_offset makes separately paced packets one continuous
// resampling timeline. Non-finite input or invalid protocol bounds fail closed.
std::optional<std::vector<std::uint8_t>> build_tx_audio(
    std::uint32_t receiver,
    std::uint32_t source_sample_rate,
    std::uint32_t target_sample_rate,
    const float *mono,
    std::size_t mono_frames,
    std::uint64_t target_frame_offset,
    std::uint32_t requested_values,
    float level);

} // namespace shackcq::tci
