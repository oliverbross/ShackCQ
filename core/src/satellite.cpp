#include "shackcq/satellite.h"

#include "CoordGeodetic.h"
#include "CoordTopocentric.h"
#include "DateTime.h"
#include "DecayedException.h"
#include "Observer.h"
#include "SGP4.h"
#include "SatelliteException.h"
#include "Tle.h"
#include "TleException.h"
#include "Util.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <iomanip>
#include <memory>
#include <sstream>
#include <stdexcept>
#include <string>

namespace {
constexpr int64_t kMaximumIntervalSeconds = 7 * 24 * 60 * 60;
constexpr int kMaximumSamples = 2000;
constexpr int kMaximumPasses = 100;
constexpr double kSpeedOfLightMetresPerSecond = 299792458.0;
constexpr double kRadToDeg = 180.0 / 3.14159265358979323846;

struct Look {
    int64_t epoch{};
    double latitude{};
    double longitude{};
    double altitude{};
    double azimuth{};
    double elevation{};
    double range{};
    double range_rate{};
};

libsgp4::DateTime date_time(int64_t epoch) {
    return libsgp4::DateTime(1970, 1, 1).AddSeconds(static_cast<double>(epoch));
}

int64_t unix_time(const libsgp4::DateTime& value) {
    return static_cast<int64_t>(std::llround((value - libsgp4::DateTime(1970, 1, 1)).TotalSeconds()));
}

std::unique_ptr<libsgp4::Tle> parse_elements(const char *format, const char *name,
                                             const char *one, const char *two) {
    const std::string type(format ? format : "");
    if (type == "CSV") {
        if (!one || std::strlen(one) == 0) throw std::invalid_argument("invalid elements");
        return std::make_unique<libsgp4::Tle>(libsgp4::Tle::FromCsv(one));
    }
    if (type != "TLE" || !one || !two || std::strlen(one) == 0 || std::strlen(two) == 0)
        throw std::invalid_argument("invalid elements");
    return std::make_unique<libsgp4::Tle>(name ? name : "", one, two);
}

void validate_age(const libsgp4::Tle& tle, int64_t target, int64_t maximum_age) {
    if (maximum_age <= 0) return;
    const int64_t age = target - unix_time(tle.Epoch());
    if (age > maximum_age) throw std::range_error("stale elements");
}

Look look_at(const libsgp4::SGP4& sgp4, libsgp4::Observer& observer, int64_t epoch) {
    const libsgp4::Eci eci = sgp4.FindPosition(date_time(epoch));
    const libsgp4::CoordGeodetic geo = eci.ToGeodetic();
    const libsgp4::CoordTopocentric top = observer.GetLookAngle(eci);
    return Look{epoch, geo.latitude * kRadToDeg, geo.longitude * kRadToDeg, geo.altitude,
                top.azimuth * kRadToDeg, top.elevation * kRadToDeg, top.range, top.range_rate};
}

int64_t crossing(const libsgp4::SGP4& sgp4, libsgp4::Observer& observer,
                 int64_t low, int64_t high, double horizon, bool rising) {
    while (high - low > 1) {
        const int64_t middle = low + (high - low) / 2;
        const bool above = look_at(sgp4, observer, middle).elevation >= horizon;
        if (above == rising) high = middle; else low = middle;
    }
    return rising ? high : low;
}

Look peak(const libsgp4::SGP4& sgp4, libsgp4::Observer& observer, int64_t start, int64_t end) {
    int64_t low = start;
    int64_t high = end;
    while (high - low > 2) {
        const int64_t third = (high - low) / 3;
        const int64_t left = low + third;
        const int64_t right = high - third;
        if (look_at(sgp4, observer, left).elevation < look_at(sgp4, observer, right).elevation) low = left;
        else high = right;
    }
    Look best = look_at(sgp4, observer, low);
    for (int64_t epoch = low + 1; epoch <= high; ++epoch) {
        Look candidate = look_at(sgp4, observer, epoch);
        if (candidate.elevation > best.elevation) best = candidate;
    }
    return best;
}

std::string error_json(const char *code) {
    return std::string("{\"version\":1,\"ok\":false,\"error\":{\"code\":\"") + code + "\"}}";
}

int copy_output(char *output, size_t output_size, const std::string& value) {
    if (!output || output_size == 0 || value.size() + 1 > output_size) return -1;
    std::memcpy(output, value.c_str(), value.size() + 1);
    return static_cast<int>(value.size());
}

template <typename Work>
int guarded(char *output, size_t output_size, Work work) {
    try {
        return copy_output(output, output_size, work());
    } catch (const libsgp4::DecayedException&) {
        return copy_output(output, output_size, error_json("DECAYED"));
    } catch (const std::range_error&) {
        return copy_output(output, output_size, error_json("STALE_ELEMENTS"));
    } catch (const libsgp4::TleException&) {
        return copy_output(output, output_size, error_json("INVALID_ELEMENTS"));
    } catch (const libsgp4::SatelliteException&) {
        return copy_output(output, output_size, error_json("PROPAGATION_ERROR"));
    } catch (const std::exception&) {
        return copy_output(output, output_size, error_json("INVALID_REQUEST"));
    }
}

void append_look(std::ostringstream& out, const Look& value, bool geodetic) {
    out << "{\"epoch\":" << value.epoch;
    if (geodetic) out << ",\"latitude_deg\":" << value.latitude << ",\"longitude_deg\":" << value.longitude
                      << ",\"altitude_km\":" << value.altitude;
    out << ",\"azimuth_deg\":" << value.azimuth << ",\"elevation_deg\":" << value.elevation
        << ",\"range_km\":" << value.range << ",\"range_rate_km_s\":" << value.range_rate << '}';
}
}  // namespace

extern "C" int shackcq_satellite_inspect_json(char *output, size_t output_size,
    const char *format, const char *name, const char *element_one, const char *element_two) {
    return guarded(output, output_size, [&] {
        const auto tle = parse_elements(format, name, element_one, element_two);
        std::ostringstream out;
        out << "{\"version\":1,\"ok\":true,\"norad_id\":" << tle->NoradNumber()
            << ",\"element_epoch\":" << unix_time(tle->Epoch()) << '}';
        return out.str();
    });
}

extern "C" int shackcq_satellite_propagate_json(char *output, size_t output_size,
    const char *format, const char *name, const char *element_one, const char *element_two,
    int64_t epoch_utc, int64_t max_element_age_seconds, double observer_latitude_deg,
    double observer_longitude_deg, double observer_altitude_km) {
    return guarded(output, output_size, [&] {
        const auto tle = parse_elements(format, name, element_one, element_two);
        validate_age(*tle, epoch_utc, max_element_age_seconds);
        const libsgp4::SGP4 sgp4(*tle);
        libsgp4::Observer observer(observer_latitude_deg, observer_longitude_deg, observer_altitude_km);
        const Look value = look_at(sgp4, observer, epoch_utc);
        std::ostringstream out; out << std::fixed << std::setprecision(6) << "{\"version\":1,\"ok\":true,\"point\":";
        append_look(out, value, true); out << '}'; return out.str();
    });
}

extern "C" int shackcq_satellite_passes_json(char *output, size_t output_size,
    const char *format, const char *name, const char *element_one, const char *element_two,
    int64_t start_utc, int64_t end_utc, int64_t max_element_age_seconds,
    double observer_latitude_deg, double observer_longitude_deg, double observer_altitude_km,
    double horizon_deg, double minimum_peak_deg, int coarse_step_seconds, int maximum_passes) {
    return guarded(output, output_size, [&] {
        if (end_utc <= start_utc || end_utc - start_utc > kMaximumIntervalSeconds) throw std::invalid_argument("interval");
        const auto tle = parse_elements(format, name, element_one, element_two);
        validate_age(*tle, end_utc, max_element_age_seconds);
        const libsgp4::SGP4 sgp4(*tle);
        libsgp4::Observer observer(observer_latitude_deg, observer_longitude_deg, observer_altitude_km);
        const int64_t step = std::clamp(coarse_step_seconds, 5, 600);
        const int limit = std::clamp(maximum_passes, 1, kMaximumPasses);
        std::ostringstream rows; rows << std::fixed << std::setprecision(6);
        int count = 0; bool first = true;
        int64_t previous_time = start_utc;
        Look previous = look_at(sgp4, observer, previous_time);
        bool active = previous.elevation >= horizon_deg;
        int64_t aos = active ? start_utc : 0;
        bool already_active = active;
        for (int64_t current_time = std::min(start_utc + step, end_utc); current_time <= end_utc && count < limit;) {
            Look current = look_at(sgp4, observer, current_time);
            if (!active && previous.elevation < horizon_deg && current.elevation >= horizon_deg) {
                aos = crossing(sgp4, observer, previous_time, current_time, horizon_deg, true);
                active = true; already_active = false;
            }
            if (active && previous.elevation >= horizon_deg && current.elevation < horizon_deg) {
                const int64_t los = crossing(sgp4, observer, previous_time, current_time, horizon_deg, false);
                const Look maximum = peak(sgp4, observer, aos, los);
                if (maximum.elevation >= minimum_peak_deg) {
                    const Look aos_look = look_at(sgp4, observer, aos);
                    const Look los_look = look_at(sgp4, observer, los);
                    if (!first) {
                        rows << ',';
                    }
                    first = false;
                    rows << "{\"aos\":" << aos << ",\"tca\":" << maximum.epoch << ",\"los\":" << los
                         << ",\"maximum_elevation_deg\":" << maximum.elevation
                         << ",\"aos_azimuth_deg\":" << aos_look.azimuth << ",\"los_azimuth_deg\":" << los_look.azimuth
                         << ",\"duration_seconds\":" << (los - aos) << ",\"already_active\":" << (already_active ? "true" : "false") << '}';
                    ++count;
                }
                active = false; aos = 0; already_active = false;
            }
            previous_time = current_time; previous = current;
            if (current_time == end_utc) break;
            current_time = std::min(current_time + step, end_utc);
        }
        std::ostringstream out; out << "{\"version\":1,\"ok\":true,\"passes\":[" << rows.str() << "]}"; return out.str();
    });
}

extern "C" int shackcq_satellite_samples_json(char *output, size_t output_size,
    const char *format, const char *name, const char *element_one, const char *element_two,
    int64_t start_utc, int64_t end_utc, int64_t max_element_age_seconds,
    double observer_latitude_deg, double observer_longitude_deg, double observer_altitude_km,
    int step_seconds, int maximum_samples, int kind) {
    return guarded(output, output_size, [&] {
        if (end_utc < start_utc || end_utc - start_utc > kMaximumIntervalSeconds || (kind != 0 && kind != 1)) throw std::invalid_argument("interval");
        const auto tle = parse_elements(format, name, element_one, element_two);
        validate_age(*tle, end_utc, max_element_age_seconds);
        const libsgp4::SGP4 sgp4(*tle);
        libsgp4::Observer observer(observer_latitude_deg, observer_longitude_deg, observer_altitude_km);
        const int64_t step = std::clamp(step_seconds, 1, 3600);
        const int limit = std::clamp(maximum_samples, 1, kMaximumSamples);
        std::ostringstream out; out << std::fixed << std::setprecision(6) << "{\"version\":1,\"ok\":true,\"kind\":\"" << (kind == 0 ? "GROUND" : "SKY") << "\",\"samples\":[";
        int count = 0;
        for (int64_t epoch = start_utc; epoch <= end_utc && count < limit; epoch = std::min(epoch + step, end_utc)) {
            if (count++) out << ',';
            append_look(out, look_at(sgp4, observer, epoch), kind == 0);
            if (epoch == end_utc) break;
        }
        out << "]}"; return out.str();
    });
}

extern "C" double shackcq_satellite_doppler_hz(double nominal_frequency_hz, double range_rate_km_s) {
    if (!std::isfinite(nominal_frequency_hz) || nominal_frequency_hz <= 0.0 || !std::isfinite(range_rate_km_s)) return 0.0;
    const double radial_metres_per_second = range_rate_km_s * 1000.0;
    return nominal_frequency_hz * kSpeedOfLightMetresPerSecond / (kSpeedOfLightMetresPerSecond + radial_metres_per_second);
}
