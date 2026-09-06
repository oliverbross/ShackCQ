#include "shackcq/propagation/p533.hpp"

#include <cstdlib>
#include <iostream>

using shackcq::propagation::P533Input;

namespace {
void require(bool value, const char* message) {
    if (!value) {
        std::cerr << message << '\n';
        std::exit(1);
    }
}

P533Input valid_input() {
    P533Input input;
    input.tx_latitude = 48.6875;
    input.tx_longitude = 16.625;
    input.rx_latitude = -33.8688;
    input.rx_longitude = 151.2093;
    input.year = 2026;
    input.month = 8;
    input.utc_hour = 12;
    input.sunspot_number = 120;
    input.frequencies_mhz = {3.6, 7.1, 14.1, 21.1, 28.1};
    return input;
}
}

int main() {
    auto input = valid_input();
    require(shackcq::propagation::validate_p533_input(input).empty(), "valid input rejected");
    const auto unavailable = shackcq::propagation::evaluate_p533(input);
    require(!unavailable.available, "blocked engine reported availability");
    require(unavailable.status == "LICENSE_BLOCKED", "blocked status was not explicit");

    input.tx_latitude = 91.0;
    require(!shackcq::propagation::validate_p533_input(input).empty(), "invalid latitude accepted");
    require(shackcq::propagation::evaluate_p533(input).status == "INVALID_INPUT", "invalid input status missing");

    input = valid_input();
    input.frequencies_mhz.assign(65, 14.1);
    require(!shackcq::propagation::validate_p533_input(input).empty(), "unbounded frequency batch accepted");
    return 0;
}

