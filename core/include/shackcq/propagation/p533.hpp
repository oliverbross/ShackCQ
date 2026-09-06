#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace shackcq::propagation {

enum class NoiseEnvironment { QuietRural, Rural, Residential, City, Industrial };
enum class Modulation { Analog, Digital };
enum class PathType { Short, Long };

struct P533Input {
    double tx_latitude = 0.0;
    double tx_longitude = 0.0;
    double rx_latitude = 0.0;
    double rx_longitude = 0.0;
    int year = 0;
    int month = 0;
    int utc_hour = 0;
    int sunspot_number = 0;
    double tx_power_watts = 100.0;
    double tx_gain_db = 0.0;
    double rx_gain_db = 0.0;
    std::vector<double> frequencies_mhz;
    NoiseEnvironment noise = NoiseEnvironment::Residential;
    int required_reliability = 90;
    double required_snr_db = 10.0;
    double bandwidth_hz = 2400.0;
    Modulation modulation = Modulation::Analog;
    PathType path = PathType::Short;
};

struct P533Result {
    bool available = false;
    std::string status;
    std::string engine = "ShackCQ P533 adapter";
    std::string model = "ITU-R P.533-14";
    std::string data_pack = "none";
    std::vector<std::string> errors;
    std::vector<std::string> warnings;
    std::int64_t elapsed_micros = 0;
};

std::vector<std::string> validate_p533_input(const P533Input& input);
P533Result evaluate_p533(const P533Input& input);

}  // namespace shackcq::propagation

