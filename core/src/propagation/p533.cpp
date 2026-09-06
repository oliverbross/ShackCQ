#include "shackcq/propagation/p533.hpp"

#include <algorithm>
#include <chrono>
#include <cmath>

namespace shackcq::propagation {

std::vector<std::string> validate_p533_input(const P533Input& input) {
    std::vector<std::string> errors;
    const auto finite_between = [](double value, double low, double high) {
        return std::isfinite(value) && value >= low && value <= high;
    };
    if (!finite_between(input.tx_latitude, -90.0, 90.0) ||
        !finite_between(input.rx_latitude, -90.0, 90.0)) errors.emplace_back("latitude out of range");
    if (!finite_between(input.tx_longitude, -180.0, 180.0) ||
        !finite_between(input.rx_longitude, -180.0, 180.0)) errors.emplace_back("longitude out of range");
    if (input.year < 1900 || input.year > 2200) errors.emplace_back("year out of range");
    if (input.month < 1 || input.month > 12) errors.emplace_back("month out of range");
    if (input.utc_hour < 0 || input.utc_hour > 23) errors.emplace_back("UTC hour out of range");
    if (input.sunspot_number < 0 || input.sunspot_number > 400) errors.emplace_back("sunspot number out of range");
    if (!finite_between(input.tx_power_watts, 0.001, 1'000'000.0)) errors.emplace_back("power out of range");
    if (!finite_between(input.tx_gain_db, -100.0, 100.0) ||
        !finite_between(input.rx_gain_db, -100.0, 100.0)) errors.emplace_back("gain out of range");
    if (input.frequencies_mhz.empty() || input.frequencies_mhz.size() > 64) errors.emplace_back("frequency count out of range");
    if (std::any_of(input.frequencies_mhz.begin(), input.frequencies_mhz.end(),
            [&](double value) { return !finite_between(value, 0.1, 60.0); })) errors.emplace_back("frequency out of range");
    if (input.required_reliability < 1 || input.required_reliability > 99) errors.emplace_back("reliability out of range");
    if (!finite_between(input.required_snr_db, -50.0, 100.0)) errors.emplace_back("required SNR out of range");
    if (!finite_between(input.bandwidth_hz, 1.0, 1'000'000.0)) errors.emplace_back("bandwidth out of range");
    return errors;
}

P533Result evaluate_p533(const P533Input& input) {
    const auto started = std::chrono::steady_clock::now();
    P533Result result;
    result.errors = validate_p533_input(input);
    if (!result.errors.empty()) {
        result.status = "INVALID_INPUT";
    } else {
        result.status = "LICENSE_BLOCKED";
        result.errors.emplace_back("Official ITU-R-HF source and data are not bundled because redistribution permission is unresolved");
    }
    result.elapsed_micros = std::chrono::duration_cast<std::chrono::microseconds>(
        std::chrono::steady_clock::now() - started).count();
    return result;
}

}  // namespace shackcq::propagation

