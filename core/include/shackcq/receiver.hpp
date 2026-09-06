// SPDX-License-Identifier: GPL-3.0-only
#pragma once

#include <cstddef>
#include <cstdint>
#include <mutex>
#include <string>
#include <vector>

namespace shackcq {

enum class ReceiverStreamState {
    Stopped,
    Starting,
    Running,
    Stale,
    Error,
};

struct ReceiverState {
    std::string id;
    std::string label;
    std::uint32_t backend_index{};
    bool enabled{};
    bool muted{};
    bool active_for_control{};
    bool active_for_listening{};
    std::uint64_t centre_frequency_hz{};
    std::uint64_t vfo_a_hz{};
    std::uint64_t vfo_b_hz{};
    std::uint32_t selected_channel{};
    std::int64_t if_offset_hz{};
    std::uint64_t effective_receive_hz{};
    std::string mode;
    std::int32_t filter_low_hz{};
    std::int32_t filter_high_hz{};
    std::uint32_t sample_rate{};
    ReceiverStreamState iq_state{ReceiverStreamState::Stopped};
    ReceiverStreamState audio_state{ReceiverStreamState::Stopped};
    float signal_db{};
    float forward_power_w{};
    float swr{};
    std::uint64_t observed_monotonic_ms{};
    std::uint64_t dropped_iq_frames{};
    std::string error;
};

class Float32Ring final {
public:
    explicit Float32Ring(std::size_t capacity_values);

    std::size_t capacity() const;
    std::size_t size() const;
    std::uint64_t dropped_values() const;

    // Returns false without changing the ring when any value is non-finite.
    // When input exceeds available space, the oldest values are discarded.
    bool push(const float *values, std::size_t count);
    std::size_t pop(float *output, std::size_t output_capacity);
    void clear();

private:
    mutable std::mutex mutex_;
    std::vector<float> values_;
    std::size_t head_{};
    std::size_t size_{};
    std::uint64_t dropped_values_{};
};

} // namespace shackcq
