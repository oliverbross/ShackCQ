#include <jni.h>
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <functional>
#include <limits>
#include <sstream>
#include <string>
#include <vector>
#include "shackcq/core.h"
#include "shackcq/satellite.h"
#include "shackcq/tci.hpp"
#include "shackcq_flex.h"

namespace {
constexpr jsize kMaximumJniInput = 4 * 1024 * 1024;
constexpr int kMaximumEncodedSamples = 16 * 1024 * 1024;

shackcq_context *context(jlong handle) { return reinterpret_cast<shackcq_context *>(static_cast<intptr_t>(handle)); }
shackcq_feature_context *features(jlong handle) { return reinterpret_cast<shackcq_feature_context *>(static_cast<intptr_t>(handle)); }
shackcq_panadapter_context *panadapter(jlong handle) { return reinterpret_cast<shackcq_panadapter_context *>(static_cast<intptr_t>(handle)); }
shackcq_flex_context *flex(jlong handle) { return reinterpret_cast<shackcq_flex_context *>(static_cast<intptr_t>(handle)); }
shackcq_digi_context *digi(jlong handle) { return reinterpret_cast<shackcq_digi_context *>(static_cast<intptr_t>(handle)); }
std::string utf(JNIEnv *env, jstring value) {
    if (!value) return {};
    const char *chars = env->GetStringUTFChars(value, nullptr);
    std::string result(chars ? chars : "");
    if (chars) env->ReleaseStringUTFChars(value, chars);
    return result;
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_shackcq_mobile_NativeCore_flexCreate(JNIEnv *, jobject) {
    return static_cast<jlong>(reinterpret_cast<intptr_t>(shackcq_flex_context_create()));
}

extern "C" JNIEXPORT void JNICALL
Java_app_shackcq_mobile_NativeCore_flexDestroy(JNIEnv *, jobject, jlong handle) {
    if (!handle) return;
    shackcq_flex_context_destroy(flex(handle));
}

extern "C" JNIEXPORT jint JNICALL
Java_app_shackcq_mobile_NativeCore_flexFeed(JNIEnv *env, jobject, jlong handle, jbyteArray data) {
    if (!handle || !data) return -1;
    const jsize length = env->GetArrayLength(data);
    if (length <= 0 || length > kMaximumJniInput) return -1;
    jbyte *bytes = env->GetByteArrayElements(data, nullptr);
    if (!bytes) return -1;
    const int applied = shackcq_flex_context_feed(flex(handle), reinterpret_cast<const uint8_t *>(bytes), static_cast<size_t>(length));
    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
    return applied;
}

extern "C" JNIEXPORT jstring JNICALL
Java_app_shackcq_mobile_NativeCore_flexState(JNIEnv *env, jobject, jlong handle) {
    if (!handle) return env->NewStringUTF("{}");
    std::string output(65536, '\0');
    const int size = shackcq_flex_state_json(flex(handle), output.data(), output.size());
    return env->NewStringUTF(size >= 0 ? output.c_str() : "{}");
}

namespace {
jstring flex_text(JNIEnv *env, const std::function<int(char *, size_t)> &builder) {
    char output[512]{};
    return env->NewStringUTF(builder(output, sizeof(output)) >= 0 ? output : "");
}
template <typename Work>
jstring satellite_text(JNIEnv *env, Work work) {
    std::vector<char> output(512 * 1024);
    const int written = work(output.data(), output.size());
    if (written < 0) return env->NewStringUTF("{\"version\":1,\"ok\":false,\"error\":{\"code\":\"OUTPUT_LIMIT\"}}");
    return env->NewStringUTF(output.data());
}
}

extern "C" JNIEXPORT jstring JNICALL
Java_app_shackcq_mobile_NativeSatellite_inspectNative(JNIEnv *env, jobject, jstring format, jstring name,
    jstring elementOne, jstring elementTwo) {
    const std::string f = utf(env, format), n = utf(env, name), one = utf(env, elementOne), two = utf(env, elementTwo);
    return satellite_text(env, [&](char *out, size_t size) {
        return shackcq_satellite_inspect_json(out, size, f.c_str(), n.c_str(), one.c_str(), two.c_str());
    });
}

extern "C" JNIEXPORT jstring JNICALL
Java_app_shackcq_mobile_NativeSatellite_propagateNative(JNIEnv *env, jobject, jstring format, jstring name,
    jstring elementOne, jstring elementTwo, jlong epoch, jlong maxAge, jdouble latitude, jdouble longitude, jdouble altitude) {
    const std::string f = utf(env, format), n = utf(env, name), one = utf(env, elementOne), two = utf(env, elementTwo);
    return satellite_text(env, [&](char *out, size_t size) { return shackcq_satellite_propagate_json(out, size, f.c_str(), n.c_str(), one.c_str(), two.c_str(),
        static_cast<int64_t>(epoch), static_cast<int64_t>(maxAge), latitude, longitude, altitude); });
}

extern "C" JNIEXPORT jstring JNICALL
Java_app_shackcq_mobile_NativeSatellite_passesNative(JNIEnv *env, jobject, jstring format, jstring name,
    jstring elementOne, jstring elementTwo, jlong start, jlong end, jlong maxAge, jdouble latitude, jdouble longitude,
    jdouble altitude, jdouble horizon, jdouble minimumPeak, jint step, jint maximumPasses) {
    const std::string f = utf(env, format), n = utf(env, name), one = utf(env, elementOne), two = utf(env, elementTwo);
    return satellite_text(env, [&](char *out, size_t size) { return shackcq_satellite_passes_json(out, size, f.c_str(), n.c_str(), one.c_str(), two.c_str(),
        static_cast<int64_t>(start), static_cast<int64_t>(end), static_cast<int64_t>(maxAge), latitude, longitude, altitude,
        horizon, minimumPeak, step, maximumPasses); });
}

extern "C" JNIEXPORT jstring JNICALL
Java_app_shackcq_mobile_NativeSatellite_samplesNative(JNIEnv *env, jobject, jstring format, jstring name,
    jstring elementOne, jstring elementTwo, jlong start, jlong end, jlong maxAge, jdouble latitude, jdouble longitude,
    jdouble altitude, jint step, jint maximumSamples, jint kind) {
    const std::string f = utf(env, format), n = utf(env, name), one = utf(env, elementOne), two = utf(env, elementTwo);
    return satellite_text(env, [&](char *out, size_t size) { return shackcq_satellite_samples_json(out, size, f.c_str(), n.c_str(), one.c_str(), two.c_str(),
        static_cast<int64_t>(start), static_cast<int64_t>(end), static_cast<int64_t>(maxAge), latitude, longitude, altitude,
        step, maximumSamples, kind); });
}

extern "C" JNIEXPORT jdouble JNICALL
Java_app_shackcq_mobile_NativeSatellite_dopplerNative(JNIEnv *, jobject, jdouble frequency, jdouble rangeRate) {
    return shackcq_satellite_doppler_hz(frequency, rangeRate);
}

extern "C" JNIEXPORT jstring JNICALL
Java_app_shackcq_mobile_NativeCore_flexIdentity(JNIEnv *env, jobject, jstring program) {
    const auto value = utf(env, program); return flex_text(env, [&](char *out, size_t size) { return shackcq_flex_client_identity(value.c_str(), out, size); });
}
extern "C" JNIEXPORT jstring JNICALL
Java_app_shackcq_mobile_NativeCore_flexSubscriptions(JNIEnv *env, jobject) { return flex_text(env, shackcq_flex_subscriptions); }
extern "C" JNIEXPORT jstring JNICALL
Java_app_shackcq_mobile_NativeCore_flexKeepalive(JNIEnv *env, jobject) { return flex_text(env, shackcq_flex_keepalive); }
extern "C" JNIEXPORT jstring JNICALL
Java_app_shackcq_mobile_NativeCore_flexFrequency(JNIEnv *env, jobject, jint slice, jlong hz) {
    return flex_text(env, [&](char *out, size_t size) { return shackcq_flex_frequency(static_cast<uint32_t>(slice), static_cast<uint64_t>(hz), out, size); });
}
extern "C" JNIEXPORT jstring JNICALL
Java_app_shackcq_mobile_NativeCore_flexMode(JNIEnv *env, jobject, jint slice, jstring mode) {
    const auto value = utf(env, mode); return flex_text(env, [&](char *out, size_t size) { return shackcq_flex_mode(static_cast<uint32_t>(slice), value.c_str(), out, size); });
}
extern "C" JNIEXPORT jstring JNICALL
Java_app_shackcq_mobile_NativeCore_flexFilter(JNIEnv *env, jobject, jstring letter, jint low, jint high) {
    const auto value = utf(env, letter); return flex_text(env, [&](char *out, size_t size) { return shackcq_flex_filter(value.c_str(), low, high, out, size); });
}
extern "C" JNIEXPORT jstring JNICALL
Java_app_shackcq_mobile_NativeCore_flexParseDiscovery(JNIEnv *env, jobject, jbyteArray data) {
    if (!data) return env->NewStringUTF("");
    const jsize length = env->GetArrayLength(data);
    if (length <= 0 || length > 65'536) return env->NewStringUTF("");
    jbyte *bytes = env->GetByteArrayElements(data, nullptr);
    if (!bytes) return env->NewStringUTF("");
    char output[4096]{};
    const int size = shackcq_flex_parse_discovery(reinterpret_cast<const uint8_t *>(bytes), static_cast<size_t>(length), output, sizeof(output));
    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
    return env->NewStringUTF(size >= 0 ? output : "");
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_shackcq_mobile_NativeCore_digiCreate(JNIEnv *, jobject, jint sampleRate, jfloat pitch, jboolean reverse, jfloat rttyCentre) {
    if (sampleRate < 8'000 || sampleRate > 384'000 || !std::isfinite(pitch) || !std::isfinite(rttyCentre)) return 0;
    return static_cast<jlong>(reinterpret_cast<intptr_t>(
        shackcq_digi_context_create(static_cast<uint32_t>(sampleRate), pitch, reverse == JNI_TRUE, rttyCentre)));
}

extern "C" JNIEXPORT void JNICALL
Java_app_shackcq_mobile_NativeCore_digiDestroy(JNIEnv *, jobject, jlong handle) {
    if (!handle) return;
    shackcq_digi_context_destroy(digi(handle));
}

jstring digi_feed(JNIEnv *env, jlong handle, jfloatArray data,
                  const std::function<int(shackcq_digi_context *, const float *, size_t, char *, size_t)> &feed) {
    if (!data || !handle) return env->NewStringUTF("{}");
    const jsize length = env->GetArrayLength(data);
    if (length <= 0 || length > kMaximumJniInput) return env->NewStringUTF("{}");
    jfloat *samples = env->GetFloatArrayElements(data, nullptr);
    if (!samples) return env->NewStringUTF("{}");
    std::string output(16384, '\0');
    const int size = feed(digi(handle), samples, static_cast<size_t>(length), output.data(), output.size());
    env->ReleaseFloatArrayElements(data, samples, JNI_ABORT);
    return env->NewStringUTF(size >= 0 ? output.c_str() : "{}");
}

extern "C" JNIEXPORT jstring JNICALL
Java_app_shackcq_mobile_NativeCore_digiFeedCw(JNIEnv *env, jobject, jlong handle, jfloatArray data) {
    return digi_feed(env, handle, data, shackcq_digi_feed_cw);
}

extern "C" JNIEXPORT jstring JNICALL
Java_app_shackcq_mobile_NativeCore_digiFeedRtty(JNIEnv *env, jobject, jlong handle, jfloatArray data) {
    return digi_feed(env, handle, data, shackcq_digi_feed_rtty);
}

extern "C" JNIEXPORT jstring JNICALL
Java_app_shackcq_mobile_NativeCore_digiFeedSstv(JNIEnv *env, jobject, jlong handle, jfloatArray data) {
    return digi_feed(env, handle, data, shackcq_digi_feed_sstv);
}

extern "C" JNIEXPORT jstring JNICALL
Java_app_shackcq_mobile_NativeCore_digiDecodeSlot(JNIEnv *env, jobject, jint mode, jfloatArray data, jint sampleRate) {
    if (!data || sampleRate < 8'000 || sampleRate > 384'000)
        return env->NewStringUTF("{\"error\":\"No audio\",\"decodes\":[]}");
    const jsize length = env->GetArrayLength(data);
    if (length <= 0 || length > kMaximumJniInput)
        return env->NewStringUTF("{\"error\":\"No audio\",\"decodes\":[]}");
    jfloat *samples = env->GetFloatArrayElements(data, nullptr);
    if (!samples) return env->NewStringUTF("{\"error\":\"No audio\",\"decodes\":[]}");
    std::string output(262144, '\0');
    const int size = shackcq_digi_decode_slot(mode, samples, static_cast<size_t>(length),
                                         static_cast<uint32_t>(sampleRate), output.data(), output.size());
    env->ReleaseFloatArrayElements(data, samples, JNI_ABORT);
    return env->NewStringUTF(size >= 0 ? output.c_str() : "{\"error\":\"Decode failed\",\"decodes\":[]}");
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_app_shackcq_mobile_NativeCore_digiSpectrum(JNIEnv *env, jobject, jfloatArray data, jint sampleRate,
                                                  jfloat lowHz, jfloat highHz, jint bins, jint window) {
    if (!data || sampleRate < 8'000 || sampleRate > 384'000 || bins <= 0 || bins > 512 ||
        !std::isfinite(lowHz) || !std::isfinite(highHz) || lowHz >= highHz) return env->NewFloatArray(0);
    const jsize length = env->GetArrayLength(data);
    if (length <= 0 || length > kMaximumJniInput) return env->NewFloatArray(0);
    jfloat *samples = env->GetFloatArrayElements(data, nullptr);
    jfloatArray result = env->NewFloatArray(bins);
    if (!samples || !result) {
        if (samples) env->ReleaseFloatArrayElements(data, samples, JNI_ABORT);
        return env->NewFloatArray(0);
    }
    jfloat *output = env->GetFloatArrayElements(result, nullptr);
    if (!output) {
        env->ReleaseFloatArrayElements(data, samples, JNI_ABORT);
        return env->NewFloatArray(0);
    }
    const int count = shackcq_digi_spectrum(samples, static_cast<size_t>(length), static_cast<uint32_t>(sampleRate),
                                       lowHz, highHz, static_cast<size_t>(bins), window,
                                       output, static_cast<size_t>(bins));
    env->ReleaseFloatArrayElements(data, samples, JNI_ABORT);
    env->ReleaseFloatArrayElements(result, output, count == bins ? 0 : JNI_ABORT);
    if (count != bins) return env->NewFloatArray(0);
    return result;
}

extern "C" JNIEXPORT jstring JNICALL
Java_app_shackcq_mobile_NativeCore_digiDecodePsk31(JNIEnv *env, jobject, jfloatArray data, jfloat carrierHz) {
    if (!data || !std::isfinite(carrierHz)) return env->NewStringUTF("{}");
    const jsize length = env->GetArrayLength(data);
    if (length <= 0 || length > kMaximumJniInput) return env->NewStringUTF("{}");
    jfloat *samples = env->GetFloatArrayElements(data, nullptr);
    if (!samples) return env->NewStringUTF("{}");
    std::string output(65536, '\0');
    const int size = shackcq_digi_decode_psk31(samples, static_cast<size_t>(length), carrierHz, output.data(), output.size());
    env->ReleaseFloatArrayElements(data, samples, JNI_ABORT);
    return env->NewStringUTF(size >= 0 ? output.c_str() : "{}");
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_app_shackcq_mobile_NativeCore_digiSstvImage(JNIEnv *env, jobject, jlong handle) {
    if (!handle) return env->NewByteArray(0);
    const int size = shackcq_digi_copy_sstv_image(digi(handle), nullptr, 0);
    if (size <= 0 || size > kMaximumJniInput) return env->NewByteArray(0);
    jbyteArray result = env->NewByteArray(size);
    if (!result) return env->NewByteArray(0);
    jbyte *bytes = env->GetByteArrayElements(result, nullptr);
    if (!bytes) return env->NewByteArray(0);
    shackcq_digi_copy_sstv_image(digi(handle), reinterpret_cast<uint8_t *>(bytes), static_cast<size_t>(size));
    env->ReleaseByteArrayElements(result, bytes, 0);
    return result;
}

jfloatArray digi_samples(JNIEnv *env, const std::function<int(float *, size_t)> &encode) {
    const int size = encode(nullptr, 0);
    if (size <= 0 || size > kMaximumEncodedSamples) return env->NewFloatArray(0);
    jfloatArray result = env->NewFloatArray(size);
    if (!result) return env->NewFloatArray(0);
    jfloat *samples = env->GetFloatArrayElements(result, nullptr);
    if (!samples) return env->NewFloatArray(0);
    encode(samples, static_cast<size_t>(size));
    env->ReleaseFloatArrayElements(result, samples, 0);
    return result;
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_app_shackcq_mobile_NativeCore_digiEncodeCw(JNIEnv *env, jobject, jstring text, jint wpm, jfloat pitch, jint sampleRate) {
    const auto value = utf(env, text);
    return digi_samples(env, [&](float *out, size_t count) {
        return shackcq_digi_encode_cw(value.c_str(), static_cast<uint32_t>(wpm), pitch,
                                 static_cast<uint32_t>(sampleRate), out, count);
    });
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_app_shackcq_mobile_NativeCore_digiEncodeRtty(JNIEnv *env, jobject, jstring text, jint sampleRate, jboolean reverse) {
    const auto value = utf(env, text);
    return digi_samples(env, [&](float *out, size_t count) {
        return shackcq_digi_encode_rtty(value.c_str(), static_cast<uint32_t>(sampleRate),
                                   reverse == JNI_TRUE, out, count);
    });
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_app_shackcq_mobile_NativeCore_digiEncodeSlot(JNIEnv *env, jobject, jint mode, jstring text, jfloat baseHz) {
    const auto value = utf(env, text);
    return digi_samples(env, [&](float *out, size_t count) {
        return shackcq_digi_encode_slot(mode, value.c_str(), baseHz, out, count);
    });
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_app_shackcq_mobile_NativeCore_digiEncodePsk31(JNIEnv *env, jobject, jstring text, jfloat carrierHz) {
    const auto value = utf(env, text);
    return digi_samples(env, [&](float *out, size_t count) {
        return shackcq_digi_encode_psk31(value.c_str(), carrierHz, out, count);
    });
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_app_shackcq_mobile_NativeCore_digiEncodeSstv(JNIEnv *env, jobject, jint mode, jbyteArray rgb,
                                                    jint width, jint height, jint sampleRate) {
    if (!rgb || width <= 0 || width > 2'048 || height <= 0 || height > 2'048 ||
        sampleRate < 8'000 || sampleRate > 384'000) return env->NewFloatArray(0);
    const int64_t expected = static_cast<int64_t>(width) * height * 3;
    if (expected > env->GetArrayLength(rgb)) return env->NewFloatArray(0);
    jbyte *bytes = env->GetByteArrayElements(rgb, nullptr);
    if (!bytes) return env->NewFloatArray(0);
    auto encode = [&](float *out, size_t count) {
        return shackcq_digi_encode_sstv(mode, reinterpret_cast<const uint8_t *>(bytes),
                                   static_cast<uint32_t>(width), static_cast<uint32_t>(height),
                                   static_cast<uint32_t>(sampleRate), out, count);
    };
    jfloatArray result = digi_samples(env, encode);
    env->ReleaseByteArrayElements(rgb, bytes, JNI_ABORT);
    return result;
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_shackcq_mobile_NativeCore_create(JNIEnv *, jobject) {
    return static_cast<jlong>(reinterpret_cast<intptr_t>(shackcq_context_create()));
}

extern "C" JNIEXPORT void JNICALL
Java_app_shackcq_mobile_NativeCore_destroy(JNIEnv *, jobject, jlong handle) {
    if (!handle) return;
    shackcq_context_destroy(context(handle));
}

extern "C" JNIEXPORT jint JNICALL
Java_app_shackcq_mobile_NativeCore_feed(JNIEnv *env, jobject, jlong handle, jbyteArray data) {
    if (!handle || !data) return 0;
    const jsize length = env->GetArrayLength(data);
    if (length <= 0 || length > kMaximumJniInput) return 0;
    jbyte *bytes = env->GetByteArrayElements(data, nullptr);
    if (!bytes) return 0;
    const int applied = shackcq_context_feed(context(handle), reinterpret_cast<const char *>(bytes), static_cast<size_t>(length));
    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
    return applied;
}

extern "C" JNIEXPORT jstring JNICALL
Java_app_shackcq_mobile_NativeCore_state(JNIEnv *env, jobject, jlong handle) {
    if (!handle) return env->NewStringUTF("");
    const auto state = shackcq_context_state(context(handle));
    std::ostringstream out;
    out << state.identity << '|' << state.model << '|' << state.mode << '|' << state.vfo_a_hz << '|'
        << state.vfo_b_hz << '|' << state.connected << '|' << state.transmitting << '|' << state.meter << '|'
        << state.swr_tenths << '|' << state.rf_output_tenths << '|' << state.af_gain << '|' << state.rf_gain << '|'
        << state.bandwidth_hz << '|' << state.power_w << '|' << state.preamp << '|' << state.attenuator << '|'
        << state.rit << '|' << state.xit << '|' << state.rx_vfo << '|' << state.tx_vfo << '|' << state.split << '|'
        << state.agc_mode << '|' << state.cwt << '|' << state.monitor_level << '|' << state.mic_gain << '|'
        << state.keyer_speed << '|' << state.if_shift_hz << '|' << state.revision;
    out << '|' << state.rit_xit_offset_hz << '|' << state.effective_rx_hz << '|'
        << state.effective_tx_hz << '|' << state.data_submode << '|' << state.updated_monotonic_ms;
    return env->NewStringUTF(out.str().c_str());
}

extern "C" JNIEXPORT jint JNICALL
Java_app_shackcq_mobile_NativeCore_classify(JNIEnv *env, jobject, jstring command) {
    const auto value = utf(env, command);
    return static_cast<jint>(shackcq_classify_command(value.c_str()));
}

extern "C" JNIEXPORT jstring JNICALL
Java_app_shackcq_mobile_NativeCore_qsoIdentity(JNIEnv *env, jobject, jstring callsign, jstring timestamp,
                                                 jlong frequency, jstring mode) {
    const auto call = utf(env, callsign); const auto time = utf(env, timestamp); const auto modeValue = utf(env, mode);
    char output[32]{};
    shackcq_qso_identity(output, sizeof(output), call.c_str(), time.c_str(), static_cast<uint64_t>(frequency), modeValue.c_str());
    return env->NewStringUTF(output);
}

extern "C" JNIEXPORT jstring JNICALL
Java_app_shackcq_mobile_NativeCore_adif(JNIEnv *env, jobject, jstring identity, jstring callsign,
                                         jstring date, jstring time, jlong frequency, jstring mode,
                                         jstring sent, jstring received) {
    const auto id = utf(env, identity); const auto call = utf(env, callsign); const auto day = utf(env, date);
    const auto clock = utf(env, time); const auto modeValue = utf(env, mode); const auto s = utf(env, sent); const auto r = utf(env, received);
    char output[768]{};
    shackcq_adif_serialize(output, sizeof(output), id.c_str(), call.c_str(), day.c_str(), clock.c_str(),
                      static_cast<uint64_t>(frequency), modeValue.c_str(), s.c_str(), r.c_str());
    return env->NewStringUTF(output);
}

extern "C" JNIEXPORT jstring JNICALL
Java_app_shackcq_mobile_NativeCore_version(JNIEnv *env, jobject) {
    return env->NewStringUTF(shackcq_core_version());
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_shackcq_mobile_NativeCore_featureCreate(JNIEnv *, jobject) {
    return static_cast<jlong>(reinterpret_cast<intptr_t>(shackcq_feature_context_create()));
}

extern "C" JNIEXPORT void JNICALL
Java_app_shackcq_mobile_NativeCore_featureDestroy(JNIEnv *, jobject, jlong handle) {
    if (!handle) return;
    shackcq_feature_context_destroy(features(handle));
}

extern "C" JNIEXPORT void JNICALL
Java_app_shackcq_mobile_NativeCore_featureWatchlist(JNIEnv *env, jobject, jlong handle, jstring value) {
    if (!handle) return;
    const auto text = utf(env, value); shackcq_feature_set_watchlist(features(handle), text.c_str());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_shackcq_mobile_NativeCore_featureLoadCty(JNIEnv *env, jobject, jlong handle, jstring value) {
    if (!handle) return JNI_FALSE;
    const auto text = utf(env, value);
    return shackcq_feature_load_cty_text(features(handle), text.c_str()) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_shackcq_mobile_NativeCore_featureClusterLine(JNIEnv *env, jobject, jlong handle, jstring value, jlong epoch) {
    if (!handle) return JNI_FALSE;
    const auto text = utf(env, value);
    return shackcq_feature_ingest_cluster_line(features(handle), text.c_str(), static_cast<int64_t>(epoch)) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_app_shackcq_mobile_NativeCore_featureDxSnapshot(JNIEnv *env, jobject, jlong handle, jlong epoch) {
    if (!handle) return env->NewStringUTF("{}");
    std::string output(131072, '\0');
    const int size = shackcq_feature_dx_snapshot_json(features(handle), output.data(), output.size(), static_cast<int64_t>(epoch));
    return env->NewStringUTF(size > 0 ? output.c_str() : "{}");
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_shackcq_mobile_NativeCore_featureBeginWorkedSync(JNIEnv *, jobject, jlong handle) {
    if (!handle) return JNI_FALSE;
    return shackcq_feature_begin_worked_sync(features(handle)) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_shackcq_mobile_NativeCore_featureAddWorkedQso(JNIEnv *env, jobject, jlong handle,
        jstring callsign, jstring entity, jstring band, jstring mode, jstring submode,
        jlong epoch, jboolean from_wavelog) {
    if (!handle) return JNI_FALSE;
    const auto call_text = utf(env, callsign);
    const auto entity_text = utf(env, entity);
    const auto band_text = utf(env, band);
    const auto mode_text = utf(env, mode);
    const auto submode_text = utf(env, submode);
    return shackcq_feature_add_worked_qso(features(handle), call_text.c_str(), entity_text.c_str(),
        band_text.c_str(), mode_text.c_str(), submode_text.c_str(), static_cast<int64_t>(epoch),
        from_wavelog == JNI_TRUE ? 1 : 0) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_shackcq_mobile_NativeCore_featureEndWorkedSync(JNIEnv *, jobject, jlong handle) {
    if (!handle) return JNI_FALSE;
    return shackcq_feature_end_worked_sync(features(handle)) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_app_shackcq_mobile_NativeCore_featureSolar(JNIEnv *, jobject, jlong handle, jfloat flux, jfloat a, jfloat kp, jlong epoch) {
    if (!handle) return;
    shackcq_feature_set_solar(features(handle), flux, a, kp, static_cast<int64_t>(epoch));
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_shackcq_mobile_NativePanadapter_create(JNIEnv *, jobject) {
    return static_cast<jlong>(reinterpret_cast<intptr_t>(shackcq_panadapter_context_create()));
}

extern "C" JNIEXPORT void JNICALL
Java_app_shackcq_mobile_NativePanadapter_destroy(JNIEnv *, jobject, jlong handle) {
    if (!handle) return;
    shackcq_panadapter_context_destroy(panadapter(handle));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_shackcq_mobile_NativePanadapter_configure(
    JNIEnv *, jobject, jlong handle, jint sampleRate, jint fftSize, jint overlap, jint window,
    jfloat floorDb, jfloat topDb, jfloat attack, jfloat release, jint averageFrames,
    jboolean peakHold, jfloat peakDecay, jboolean flatness, jboolean swapIq,
    jboolean invertI, jboolean invertQ, jboolean conjugate, jfloat iTrim, jfloat qTrim,
    jint zoomDecimation, jfloat zoomOffset) {
    if (!handle || sampleRate <= 0 || fftSize <= 0) return JNI_FALSE;
    shackcq_panadapter_config config{};
    config.sample_rate = static_cast<uint32_t>(sampleRate);
    config.fft_size = static_cast<uint32_t>(fftSize);
    config.overlap_percent = static_cast<uint32_t>(overlap);
    config.window = static_cast<uint32_t>(window);
    config.display_floor_db = floorDb; config.display_top_db = topDb;
    config.attack = attack; config.release = release;
    config.average_frames = static_cast<uint32_t>(averageFrames);
    config.peak_hold = peakHold; config.peak_decay_db_per_second = peakDecay;
    config.generic_kx3_flatness = flatness; config.swap_iq = swapIq;
    config.invert_i = invertI; config.invert_q = invertQ; config.conjugate = conjugate;
    config.i_trim = iTrim; config.q_trim = qTrim;
    config.zoom_decimation = static_cast<uint32_t>(zoomDecimation);
    config.zoom_offset_hz = zoomOffset;
    return shackcq_panadapter_configure(panadapter(handle), &config) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_shackcq_mobile_NativePanadapter_push(JNIEnv *env, jobject, jlong handle,
                                                jshortArray samples, jint sampleCount,
                                                jboolean discontinuity) {
    if (!handle || samples == nullptr || sampleCount <= 0) return JNI_FALSE;
    const jsize available = env->GetArrayLength(samples);
    const jsize count = std::min(available, sampleCount);
    jshort *values = env->GetShortArrayElements(samples, nullptr);
    const int ready = shackcq_panadapter_push(panadapter(handle),
        reinterpret_cast<const uint8_t *>(values), static_cast<size_t>(count) * sizeof(jshort),
        2U, sizeof(jshort), 16U, discontinuity ? 1 : 0);
    env->ReleaseShortArrayElements(samples, values, JNI_ABORT);
    return ready ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_shackcq_mobile_NativePanadapter_pushFloat(JNIEnv *env, jobject, jlong handle,
                                                     jfloatArray data, jint count, jboolean discontinuity) {
    if (!handle || !data || count <= 0 || count > kMaximumJniInput || count > env->GetArrayLength(data)) return JNI_FALSE;
    jfloat *samples = env->GetFloatArrayElements(data, nullptr);
    if (!samples) return JNI_FALSE;
    const int ready = shackcq_panadapter_push_float_iq(panadapter(handle), samples, static_cast<size_t>(count), discontinuity == JNI_TRUE ? 1 : 0);
    env->ReleaseFloatArrayElements(data, samples, JNI_ABORT);
    return ready == 1 ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_app_shackcq_mobile_NativeTci_parseStatus(JNIEnv *env, jobject, jstring value) {
    const auto commands = shackcq::tci::parse_status(utf(env, value));
    jclass string_class = env->FindClass("java/lang/String");
    jobjectArray output = env->NewObjectArray(static_cast<jsize>(commands.size()), string_class, nullptr);
    for (std::size_t index = 0; index < commands.size(); ++index) {
        const std::string row = commands[index].name + '|' + commands[index].arguments;
        env->SetObjectArrayElement(output, static_cast<jsize>(index), env->NewStringUTF(row.c_str()));
    }
    return output;
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_app_shackcq_mobile_NativeTci_decodeBinary(JNIEnv *env, jobject, jbyteArray data, jintArray metadata) {
    if (!data || !metadata || env->GetArrayLength(metadata) < 7) return env->NewFloatArray(0);
    const jsize length = env->GetArrayLength(data);
    if (length <= 0 || length > kMaximumJniInput) return env->NewFloatArray(0);
    jbyte *bytes = env->GetByteArrayElements(data, nullptr);
    if (!bytes) return env->NewFloatArray(0);
    shackcq::tci::BinaryError error{};
    const auto frame = shackcq::tci::decode_binary(reinterpret_cast<const std::uint8_t *>(bytes),
        static_cast<std::size_t>(length), &error);
    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
    jint meta[7]{};
    if (!frame) {
        meta[6] = static_cast<jint>(error);
        env->SetIntArrayRegion(metadata, 0, 7, meta);
        return env->NewFloatArray(0);
    }
    meta[0] = static_cast<jint>(frame->header.receiver);
    meta[1] = static_cast<jint>(frame->header.sample_rate);
    meta[2] = static_cast<jint>(frame->header.format);
    meta[3] = static_cast<jint>(frame->header.data_type);
    meta[4] = static_cast<jint>(frame->header.channels);
    meta[5] = static_cast<jint>(frame->header.value_count);
    env->SetIntArrayRegion(metadata, 0, 7, meta);
    jfloatArray output = env->NewFloatArray(static_cast<jsize>(frame->values.size()));
    if (output && !frame->values.empty()) env->SetFloatArrayRegion(output, 0,
        static_cast<jsize>(frame->values.size()), frame->values.data());
    return output ? output : env->NewFloatArray(0);
}

extern "C" JNIEXPORT jstring JNICALL
Java_app_shackcq_mobile_NativeTci_buildCommand(JNIEnv *env, jobject, jint kind, jint receiver,
                                                 jint channel, jlong number, jstring text) {
    using namespace shackcq::tci;
    std::optional<std::string> command;
    switch (kind) {
    case 0: command = build_vfo(static_cast<std::uint32_t>(receiver), static_cast<std::uint32_t>(channel), static_cast<std::uint64_t>(number)); break;
    case 1: command = build_mode(static_cast<std::uint32_t>(receiver), utf(env, text)); break;
    case 2: command = build_iq_sample_rate(static_cast<std::uint32_t>(number)); break;
    case 3: command = build_iq_start(static_cast<std::uint32_t>(receiver)); break;
    case 4: command = build_iq_stop(static_cast<std::uint32_t>(receiver)); break;
    case 5: command = build_audio_start(static_cast<std::uint32_t>(receiver)); break;
    case 6: command = build_audio_stop(static_cast<std::uint32_t>(receiver)); break;
    case 7: command = build_rx_enable(static_cast<std::uint32_t>(receiver), number != 0); break;
    case 8: command = build_mute(static_cast<std::uint32_t>(receiver), number != 0); break;
    case 9: command = build_safe_stop(static_cast<std::uint32_t>(receiver)); break;
    case 10: command = build_if(static_cast<std::uint32_t>(receiver), static_cast<std::uint32_t>(channel), static_cast<std::int64_t>(number)); break;
    case 11: command = build_volume(static_cast<std::int32_t>(number)); break;
    case 12: command = build_split_enable(static_cast<std::uint32_t>(receiver), number != 0); break;
    case 13: command = build_trx(static_cast<std::uint32_t>(receiver), number != 0); break;
    case 14: command = build_tune(static_cast<std::uint32_t>(receiver), number != 0); break;
    case 15: command = build_drive(static_cast<std::uint32_t>(receiver), static_cast<std::uint32_t>(number)); break;
    case 16: command = build_tune_drive(static_cast<std::uint32_t>(receiver), static_cast<std::uint32_t>(number)); break;
    case 17: command = build_audio_sample_rate(static_cast<std::uint32_t>(number)); break;
    case 18: command = build_tx_sensors_enable(static_cast<std::uint32_t>(receiver), number != 0); break;
    case 19: command = build_xit_enable(static_cast<std::uint32_t>(receiver), number != 0); break;
    case 20: command = build_xit_offset(static_cast<std::uint32_t>(receiver), static_cast<std::int64_t>(number)); break;
    case 21: command = build_monitor_enable(static_cast<std::uint32_t>(receiver), number != 0); break;
    default: break;
    }
    return env->NewStringUTF(command ? command->c_str() : "");
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_app_shackcq_mobile_NativeTci_buildTxAudio(JNIEnv *env, jobject, jfloatArray mono,
    jint sourceRate, jint targetRate, jint receiver, jlong targetFrameOffset,
    jint requestedValues, jfloat level) {
    if (!mono || sourceRate <= 0 || targetRate <= 0 || receiver < 0 || targetFrameOffset < 0 ||
        requestedValues <= 0 || requestedValues > 16'384) return env->NewByteArray(0);
    const jsize count = env->GetArrayLength(mono);
    if (count <= 0 || count > kMaximumJniInput) return env->NewByteArray(0);
    jfloat *samples = env->GetFloatArrayElements(mono, nullptr);
    if (!samples) return env->NewByteArray(0);
    const auto frame = shackcq::tci::build_tx_audio(static_cast<std::uint32_t>(receiver),
        static_cast<std::uint32_t>(sourceRate), static_cast<std::uint32_t>(targetRate),
        samples, static_cast<std::size_t>(count), static_cast<std::uint64_t>(targetFrameOffset),
        static_cast<std::uint32_t>(requestedValues), level);
    env->ReleaseFloatArrayElements(mono, samples, JNI_ABORT);
    if (!frame || frame->size() > static_cast<std::size_t>(std::numeric_limits<jsize>::max()))
        return env->NewByteArray(0);
    jbyteArray output = env->NewByteArray(static_cast<jsize>(frame->size()));
    if (output) env->SetByteArrayRegion(output, 0, static_cast<jsize>(frame->size()),
        reinterpret_cast<const jbyte *>(frame->data()));
    return output ? output : env->NewByteArray(0);
}

extern "C" JNIEXPORT jint JNICALL
Java_app_shackcq_mobile_NativePanadapter_snapshot(JNIEnv *env, jobject, jlong handle,
    jlongArray meta, jfloatArray metrics, jfloatArray trace, jfloatArray waterfall,
    jfloatArray peakHold) {
    if (!handle || meta == nullptr || metrics == nullptr || trace == nullptr || waterfall == nullptr || peakHold == nullptr ||
        env->GetArrayLength(meta) < 9 || env->GetArrayLength(metrics) < 14) return 0;
    const jsize capacity = std::min({env->GetArrayLength(trace), env->GetArrayLength(waterfall),
                                    env->GetArrayLength(peakHold)});
    if (capacity <= 0 || capacity > kMaximumJniInput) return 0;
    jfloat *traceValues = env->GetFloatArrayElements(trace, nullptr);
    jfloat *waterfallValues = env->GetFloatArrayElements(waterfall, nullptr);
    jfloat *peakValues = env->GetFloatArrayElements(peakHold, nullptr);
    if (!traceValues || !waterfallValues || !peakValues) {
        if (traceValues) env->ReleaseFloatArrayElements(trace, traceValues, JNI_ABORT);
        if (waterfallValues) env->ReleaseFloatArrayElements(waterfall, waterfallValues, JNI_ABORT);
        if (peakValues) env->ReleaseFloatArrayElements(peakHold, peakValues, JNI_ABORT);
        return 0;
    }
    shackcq_panadapter_snapshot value{};
    const int count = shackcq_panadapter_copy_frame(panadapter(handle), &value, traceValues,
        waterfallValues, peakValues, static_cast<size_t>(capacity));
    env->ReleaseFloatArrayElements(trace, traceValues, 0);
    env->ReleaseFloatArrayElements(waterfall, waterfallValues, 0);
    env->ReleaseFloatArrayElements(peakHold, peakValues, 0);
    if (count <= 0) return 0;
    const jlong metaValues[9] = {
        static_cast<jlong>(value.sequence), static_cast<jlong>(value.input_frames),
        static_cast<jlong>(value.transforms), static_cast<jlong>(value.discontinuities),
        value.sample_rate, value.effective_sample_rate, value.fft_size, value.hop_size,
        value.zoom_decimation
    };
    const jfloat metricValues[14] = {
        value.zoom_offset_hz, value.enbw_bins, value.rbw_hz, value.peak_db, value.floor_db,
        value.i_rms_db, value.q_rms_db, value.iq_correlation, value.clipped_fraction,
        value.duplicate_correlation, value.raw_floor_db, value.stabilized_floor_db,
        value.valid_bin_fraction, static_cast<jfloat>(value.valid_bin_count)
    };
    env->SetLongArrayRegion(meta, 0, 9, metaValues);
    env->SetFloatArrayRegion(metrics, 0, 14, metricValues);
    return count;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_shackcq_mobile_NativePanadapter_setIqCorrection(JNIEnv *, jobject, jlong handle,
    jfloat aReal, jfloat aImag, jfloat bReal, jfloat bImag, jboolean enabled) {
    if (!handle) return JNI_FALSE;
    return shackcq_panadapter_set_iq_correction(panadapter(handle), aReal, aImag, bReal, bImag,
                                            enabled ? 1 : 0) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_app_shackcq_mobile_NativePanadapter_resetPeakHold(JNIEnv *, jobject, jlong handle) {
    if (!handle) return;
    shackcq_panadapter_reset_peak_hold(panadapter(handle));
}
