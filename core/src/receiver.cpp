// SPDX-License-Identifier: GPL-3.0-only
#include "shackcq/receiver.hpp"

#include <algorithm>
#include <cmath>

namespace shackcq {

Float32Ring::Float32Ring(std::size_t capacity_values)
    : values_(std::max<std::size_t>(capacity_values, 2U)) {}

std::size_t Float32Ring::capacity() const {
    return values_.size();
}

std::size_t Float32Ring::size() const {
    const std::lock_guard<std::mutex> lock(mutex_);
    return size_;
}

std::uint64_t Float32Ring::dropped_values() const {
    const std::lock_guard<std::mutex> lock(mutex_);
    return dropped_values_;
}

bool Float32Ring::push(const float *values, std::size_t count) {
    if ((values == nullptr && count != 0U) ||
        !std::all_of(values, values + count, [](float value) { return std::isfinite(value); })) {
        return false;
    }
    if (count == 0U) return true;

    const std::lock_guard<std::mutex> lock(mutex_);
    if (count >= values_.size()) {
        dropped_values_ += size_ + count - values_.size();
        const float *tail = values + (count - values_.size());
        std::copy(tail, tail + values_.size(), values_.begin());
        head_ = 0U;
        size_ = values_.size();
        return true;
    }

    const std::size_t overflow = size_ + count > values_.size()
        ? size_ + count - values_.size()
        : 0U;
    head_ = (head_ + overflow) % values_.size();
    size_ -= overflow;
    dropped_values_ += overflow;

    std::size_t tail = (head_ + size_) % values_.size();
    for (std::size_t index = 0; index < count; ++index) {
        values_[tail] = values[index];
        tail = (tail + 1U) % values_.size();
    }
    size_ += count;
    return true;
}

std::size_t Float32Ring::pop(float *output, std::size_t output_capacity) {
    if (output == nullptr || output_capacity == 0U) return 0U;
    const std::lock_guard<std::mutex> lock(mutex_);
    const std::size_t count = std::min(size_, output_capacity);
    for (std::size_t index = 0; index < count; ++index) {
        output[index] = values_[(head_ + index) % values_.size()];
    }
    head_ = (head_ + count) % values_.size();
    size_ -= count;
    return count;
}

void Float32Ring::clear() {
    const std::lock_guard<std::mutex> lock(mutex_);
    head_ = 0U;
    size_ = 0U;
}

} // namespace shackcq
