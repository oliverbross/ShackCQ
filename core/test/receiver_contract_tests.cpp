// SPDX-License-Identifier: GPL-3.0-only
#include "shackcq/receiver.hpp"

#include <array>
#include <cassert>
#include <cmath>
#include <limits>

int main() {
    shackcq::ReceiverState receiver;
    receiver.id = "tci:0";
    receiver.backend_index = 0U;
    receiver.active_for_control = true;
    receiver.active_for_listening = true;
    receiver.centre_frequency_hz = 14'074'000U;
    receiver.if_offset_hz = -1'500;
    receiver.effective_receive_hz = 14'072'500U;
    assert(receiver.id == "tci:0");
    assert(receiver.iq_state == shackcq::ReceiverStreamState::Stopped);

    shackcq::Float32Ring ring(8U);
    const std::array<float, 6> first{0.0F, 1.0F, 2.0F, 3.0F, 4.0F, 5.0F};
    assert(ring.push(first.data(), first.size()));
    assert(ring.size() == first.size());

    std::array<float, 2> prefix{};
    assert(ring.pop(prefix.data(), prefix.size()) == prefix.size());
    assert(prefix[0] == 0.0F && prefix[1] == 1.0F);

    const std::array<float, 6> second{6.0F, 7.0F, 8.0F, 9.0F, 10.0F, 11.0F};
    assert(ring.push(second.data(), second.size()));
    assert(ring.size() == ring.capacity());
    assert(ring.dropped_values() == 2U);

    std::array<float, 8> retained{};
    assert(ring.pop(retained.data(), retained.size()) == retained.size());
    const std::array<float, 8> expected{4.0F, 5.0F, 6.0F, 7.0F, 8.0F, 9.0F, 10.0F, 11.0F};
    assert(retained == expected);

    const std::array<float, 12> oversized{0.0F, 1.0F, 2.0F, 3.0F, 4.0F, 5.0F,
                                           6.0F, 7.0F, 8.0F, 9.0F, 10.0F, 11.0F};
    assert(ring.push(oversized.data(), oversized.size()));
    assert(ring.dropped_values() == 6U);
    assert(ring.pop(retained.data(), retained.size()) == retained.size());
    assert(retained == expected);

    const std::array<float, 2> invalid{1.0F, std::numeric_limits<float>::infinity()};
    assert(!ring.push(invalid.data(), invalid.size()));
    assert(ring.size() == 0U);

    assert(ring.push(first.data(), first.size()));
    ring.clear();
    assert(ring.size() == 0U);
}
