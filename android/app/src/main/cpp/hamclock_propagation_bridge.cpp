#include <jni.h>

#include <iomanip>
#include <sstream>

#include "shackcq/propagation/p533.hpp"

namespace {
std::string json_escape(const std::string& value) {
    std::ostringstream out;
    for (const char ch : value) {
        if (ch == '\\' || ch == '"') out << '\\' << ch;
        else if (ch == '\n') out << "\\n";
        else out << ch;
    }
    return out.str();
}
}

extern "C" JNIEXPORT jstring JNICALL
Java_app_shackcq_mobile_hamclock_finishline_HamClockNativePropagation_nativeEvaluate(
    JNIEnv* env, jobject, jdouble tx_lat, jdouble tx_lon, jdouble rx_lat, jdouble rx_lon,
    jint year, jint month, jint hour, jint ssn, jdouble power, jdouble tx_gain, jdouble rx_gain,
    jdoubleArray frequencies, jint noise, jint reliability, jdouble required_snr,
    jdouble bandwidth, jboolean digital, jboolean long_path) {
    using namespace shackcq::propagation;
    P533Input input;
    input.tx_latitude = tx_lat; input.tx_longitude = tx_lon;
    input.rx_latitude = rx_lat; input.rx_longitude = rx_lon;
    input.year = year; input.month = month; input.utc_hour = hour; input.sunspot_number = ssn;
    input.tx_power_watts = power; input.tx_gain_db = tx_gain; input.rx_gain_db = rx_gain;
    input.noise = static_cast<NoiseEnvironment>(noise);
    input.required_reliability = reliability; input.required_snr_db = required_snr;
    input.bandwidth_hz = bandwidth;
    input.modulation = digital ? Modulation::Digital : Modulation::Analog;
    input.path = long_path ? PathType::Long : PathType::Short;
    const jsize count = env->GetArrayLength(frequencies);
    input.frequencies_mhz.resize(static_cast<std::size_t>(count));
    env->GetDoubleArrayRegion(frequencies, 0, count, input.frequencies_mhz.data());
    const auto result = evaluate_p533(input);
    std::ostringstream json;
    json << "{\"available\":" << (result.available ? "true" : "false")
         << ",\"status\":\"" << json_escape(result.status)
         << "\",\"engine\":\"" << json_escape(result.engine)
         << "\",\"model\":\"" << json_escape(result.model)
         << "\",\"dataPack\":\"" << json_escape(result.data_pack)
         << "\",\"elapsedMicros\":" << result.elapsed_micros << ",\"errors\":[";
    for (std::size_t i = 0; i < result.errors.size(); ++i) {
        if (i) json << ',';
        json << '"' << json_escape(result.errors[i]) << '"';
    }
    json << "]}";
    return env->NewStringUTF(json.str().c_str());
}

