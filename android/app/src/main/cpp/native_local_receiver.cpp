// SPDX-License-Identifier: GPL-3.0-only
#include <jni.h>

#include "shackcq/local_receiver.hpp"

#include <algorithm>
#include <chrono>
#include <cstdint>
#include <limits>
#include <sstream>
#include <vector>

namespace {
constexpr std::size_t kMaximumJniSamples = 4U * 1024U * 1024U;
constexpr std::size_t kHeader = 18U;

shackcq::LocalReceiverDsp *receiver(jlong handle) {
    return reinterpret_cast<shackcq::LocalReceiverDsp *>(static_cast<intptr_t>(handle));
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_shackcq_mobile_NativeLocalReceiver_create(JNIEnv *, jobject) {
    return static_cast<jlong>(reinterpret_cast<intptr_t>(new shackcq::LocalReceiverDsp()));
}

extern "C" JNIEXPORT void JNICALL
Java_app_shackcq_mobile_NativeLocalReceiver_destroy(JNIEnv *, jobject, jlong handle) {
    if (handle != 0) delete receiver(handle);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_shackcq_mobile_NativeLocalReceiver_configure(JNIEnv *, jobject, jlong handle, jint input_rate,
        jint mode, jfloat offset_hz, jfloat low_hz, jfloat high_hz, jfloat cw_pitch_hz,
        jfloat squelch_db, jint deemphasis_us) {
    if (handle == 0 || mode < 0 || mode > static_cast<jint>(shackcq::LocalReceiverMode::Spectrum)) return JNI_FALSE;
    shackcq::LocalReceiverConfig config;
    config.input_sample_rate = static_cast<std::uint32_t>(input_rate);
    config.output_sample_rate = 48000U;
    config.mode = static_cast<shackcq::LocalReceiverMode>(mode);
    config.offset_hz = offset_hz;
    config.filter_low_hz = low_hz;
    config.filter_high_hz = high_hz;
    config.cw_pitch_hz = cw_pitch_hz;
    config.squelch_db = squelch_db;
    config.fm_deemphasis_us = static_cast<float>(deemphasis_us);
    return receiver(handle)->configure(config) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_app_shackcq_mobile_NativeLocalReceiver_process(JNIEnv *env, jobject, jlong handle, jfloatArray input) {
    if (handle == 0 || input == nullptr) return env->NewFloatArray(0);
    const jsize count = env->GetArrayLength(input);
    if (count <= 0 || static_cast<std::size_t>(count) > kMaximumJniSamples || count % 2 != 0) return env->NewFloatArray(0);
    jfloat *values = env->GetFloatArrayElements(input, nullptr);
    if (values == nullptr) return env->NewFloatArray(0);
    const auto started = std::chrono::steady_clock::now();
    auto result = receiver(handle)->process(values, static_cast<std::size_t>(count));
    env->ReleaseFloatArrayElements(input, values, JNI_ABORT);
    const float elapsed_ms = std::chrono::duration<float, std::milli>(std::chrono::steady_clock::now() - started).count();
    if (result.audio.size() > static_cast<std::size_t>(std::numeric_limits<jsize>::max()) - kHeader) return env->NewFloatArray(0);
    std::vector<float> output(kHeader + result.audio.size());
    const auto &metrics = result.metrics;
    output[0] = static_cast<float>(result.channels);
    output[1] = metrics.signal_db;
    output[2] = metrics.carrier_level;
    output[3] = metrics.modulation_depth;
    output[4] = static_cast<float>(metrics.sam_state);
    output[5] = metrics.sam_error_hz;
    output[6] = metrics.ctcss_hz;
    output[7] = metrics.ctcss_confidence;
    output[8] = static_cast<float>(metrics.dcs_code);
    output[9] = metrics.dcs_inverted ? 1.0F : 0.0F;
    output[10] = metrics.dcs_confidence;
    output[11] = metrics.wfm_pilot;
    output[12] = metrics.stereo_separation_db;
    output[13] = static_cast<float>(metrics.rds_pi);
    output[14] = static_cast<float>(metrics.rds_pty);
    output[15] = metrics.rds_error_rate;
    output[16] = metrics.rds_valid ? 1.0F : 0.0F;
    output[17] = elapsed_ms;
    std::copy(result.audio.begin(), result.audio.end(), output.begin() + static_cast<std::ptrdiff_t>(kHeader));
    jfloatArray array = env->NewFloatArray(static_cast<jsize>(output.size()));
    if (array != nullptr) env->SetFloatArrayRegion(array, 0, static_cast<jsize>(output.size()), output.data());
    return array;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_shackcq_mobile_NativeLocalReceiver_debugRdsGroup(JNIEnv *, jobject, jlong handle,
        jint a, jint b, jint c, jint d) {
    if (handle == 0) return JNI_FALSE;
    return receiver(handle)->consume_rds_group(static_cast<std::uint16_t>(a), static_cast<std::uint16_t>(b),
        static_cast<std::uint16_t>(c), static_cast<std::uint16_t>(d)) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_app_shackcq_mobile_NativeLocalReceiver_metadata(JNIEnv *env, jobject, jlong handle) {
    if (handle == 0) return env->NewStringUTF("{}");
    const auto &value = receiver(handle)->metrics();
    auto clean = [](std::string text) {
        for (char &character : text) if (character < 0x20 || character == '"' || character == '\\') character = ' ';
        while (!text.empty() && text.back() == ' ') text.pop_back();
        return text;
    };
    std::ostringstream json;
    json << "{\"pi\":" << value.rds_pi << ",\"pty\":" << static_cast<unsigned>(value.rds_pty)
         << ",\"tp\":" << (value.rds_tp ? "true" : "false") << ",\"ta\":" << (value.rds_ta ? "true" : "false")
         << ",\"clock\":\"" << clean(value.rds_clock) << "\",\"ps\":\"" << clean(value.rds_ps)
         << "\",\"text\":\"" << clean(value.rds_text) << "\",\"af_khz\":[";
    for (std::size_t index = 0; index < value.rds_af_khz.size(); ++index) { if (index != 0U) json << ','; json << value.rds_af_khz[index]; }
    json << "]}";
    return env->NewStringUTF(json.str().c_str());
}
