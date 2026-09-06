// SPDX-License-Identifier: GPL-3.0-only
#include <jni.h>
#include <algorithm>
#include <chrono>
#include <cmath>
#include <complex>
#include <cstdint>
#include <vector>

namespace {
constexpr std::size_t kMaximumSamples = 4U * 1024U * 1024U;
constexpr float kPi = 3.14159265358979323846F;

struct RxDsp {
    float dc_x = 0.0F;
    float dc_y = 0.0F;
    float nr = 0.0F;
    float agc_gain = 1.0F;
    int hang = 0;
    float notch_x1 = 0.0F;
    float notch_x2 = 0.0F;
    float notch_y1 = 0.0F;
    float notch_y2 = 0.0F;
    float notch_frequency = 0.0F;
    std::vector<float> noise_floor = std::vector<float>(256, 0.0F);
};

RxDsp *dsp(jlong handle) {
    return reinterpret_cast<RxDsp *>(static_cast<intptr_t>(handle));
}

void fft(std::vector<std::complex<float>> &values, bool inverse) {
    const std::size_t size = values.size();
    for (std::size_t i = 1, j = 0; i < size; ++i) {
        std::size_t bit = size >> 1U;
        for (; j & bit; bit >>= 1U) j ^= bit;
        j ^= bit;
        if (i < j) std::swap(values[i], values[j]);
    }
    for (std::size_t length = 2; length <= size; length <<= 1U) {
        const float angle = (inverse ? 2.0F : -2.0F) * kPi / static_cast<float>(length);
        const std::complex<float> step(std::cos(angle), std::sin(angle));
        for (std::size_t base = 0; base < size; base += length) {
            std::complex<float> weight(1.0F, 0.0F);
            for (std::size_t offset = 0; offset < length / 2; ++offset) {
                const auto even = values[base + offset];
                const auto odd = values[base + offset + length / 2] * weight;
                values[base + offset] = even + odd;
                values[base + offset + length / 2] = even - odd;
                weight *= step;
            }
        }
    }
    if (inverse) for (auto &value : values) value /= static_cast<float>(size);
}

float dominant_tone(const jfloat *values, jsize count, int sample_rate, float input_energy) {
    const jsize measured = std::min<jsize>(count, 4096);
    float best_frequency = 0.0F;
    double best_power = 0.0;
    for (int frequency = 300; frequency <= 3000; frequency += 50) {
        const float coefficient = 2.0F * std::cos(2.0F * kPi * frequency / sample_rate);
        float q0 = 0.0F, q1 = 0.0F, q2 = 0.0F;
        for (jsize index = 0; index < measured; ++index) {
            q0 = coefficient * q1 - q2 + values[index];
            q2 = q1;
            q1 = q0;
        }
        const double power = q1 * q1 + q2 * q2 - coefficient * q1 * q2;
        if (power > best_power) {
            best_power = power;
            best_frequency = static_cast<float>(frequency);
        }
    }
    return best_power > static_cast<double>(input_energy) * measured * 8.0 ? best_frequency : 0.0F;
}

void spectral_reduce(RxDsp &state, jfloat *values, jsize count, float amount) {
    if (amount <= 0.001F) return;
    constexpr std::size_t block_size = 256;
    std::vector<std::complex<float>> bins(block_size);
    for (jsize base = 0; base < count; base += static_cast<jsize>(block_size)) {
        const jsize available = std::min<jsize>(static_cast<jsize>(block_size), count - base);
        for (std::size_t index = 0; index < block_size; ++index) {
            bins[index] = index < static_cast<std::size_t>(available) ? values[base + static_cast<jsize>(index)] : 0.0F;
        }
        fft(bins, false);
        for (std::size_t index = 0; index < block_size; ++index) {
            const float magnitude = std::abs(bins[index]);
            float &floor = state.noise_floor[index];
            if (floor == 0.0F) floor = magnitude;
            else floor += (magnitude < floor ? 0.08F : 0.001F) * (magnitude - floor);
            const float gain = std::clamp(1.0F - amount * 1.55F * floor / std::max(magnitude, 1.0e-7F),
                1.0F - amount * 0.88F, 1.0F);
            bins[index] *= gain;
        }
        fft(bins, true);
        for (jsize index = 0; index < available; ++index) values[base + index] = bins[static_cast<std::size_t>(index)].real();
    }
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_shackcq_mobile_NativeRxDsp_create(JNIEnv *, jobject) {
    return static_cast<jlong>(reinterpret_cast<intptr_t>(new RxDsp()));
}

extern "C" JNIEXPORT void JNICALL
Java_app_shackcq_mobile_NativeRxDsp_destroy(JNIEnv *, jobject, jlong handle) {
    if (handle) delete dsp(handle);
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_app_shackcq_mobile_NativeRxDsp_process(
    JNIEnv *env, jobject, jlong handle, jfloatArray data, jint sample_rate,
    jboolean blanker, jboolean notch, jfloat reduction, jboolean agc, jint hang_ms,
    jfloat squelch_db, jfloat output_gain) {
    jfloatArray metrics = env->NewFloatArray(8);
    if (!handle || !data || sample_rate < 8000 || sample_rate > 384000) return metrics;
    const jsize count = env->GetArrayLength(data);
    if (count <= 0 || static_cast<std::size_t>(count) > kMaximumSamples) return metrics;
    jfloat *values = env->GetFloatArrayElements(data, nullptr);
    if (!values) return metrics;
    RxDsp &state = *dsp(handle);
    const auto started = std::chrono::steady_clock::now();
    double input_energy = 0.0;
    for (jsize index = 0; index < count; ++index) {
        const float sample = std::clamp(values[index], -1.25F, 1.25F);
        input_energy += static_cast<double>(sample) * sample;
    }
    const float input_rms = std::sqrt(static_cast<float>(input_energy / count) + 1.0e-12F);
    const float input_db = 20.0F * std::log10(std::max(input_rms, 1.0e-6F));
    const bool squelched = input_db < squelch_db;
    const float threshold = std::max(0.08F, input_rms * 7.0F);
    state.notch_frequency = notch ? dominant_tone(values, count, sample_rate, static_cast<float>(input_energy)) : 0.0F;
    const float omega = 2.0F * kPi * state.notch_frequency / static_cast<float>(sample_rate);
    const float radius = 0.985F;
    const float b1 = -2.0F * std::cos(omega);
    const float a1 = -2.0F * radius * std::cos(omega);
    const float a2 = radius * radius;
    const float mix = std::clamp(reduction, 0.0F, 1.0F);
    const int hang_samples = std::clamp(hang_ms, 0, 2000) * sample_rate / 1000;
    int blanked = 0;
    for (jsize index = 0; index < count; ++index) {
        const float input = values[index];
        float value = input - state.dc_x + 0.995F * state.dc_y;
        state.dc_x = input;
        state.dc_y = value;
        if (blanker && std::fabs(value) > threshold) {
            value = 0.0F;
            ++blanked;
        }
        if (state.notch_frequency > 0.0F) {
            const float filtered = value + b1 * state.notch_x1 + state.notch_x2
                - a1 * state.notch_y1 - a2 * state.notch_y2;
            state.notch_x2 = state.notch_x1;
            state.notch_x1 = value;
            state.notch_y2 = state.notch_y1;
            state.notch_y1 = filtered;
            value = filtered;
        }
        values[index] = value;
    }
    spectral_reduce(state, values, count, mix);
    int clipped = 0;
    double output_energy = 0.0;
    for (jsize index = 0; index < count; ++index) {
        float value = values[index];
        if (agc) {
            const float envelope = std::max(std::fabs(value), 1.0e-5F);
            const float wanted = std::clamp(0.22F / envelope, 0.08F, 18.0F);
            if (wanted < state.agc_gain) {
                state.agc_gain += 0.12F * (wanted - state.agc_gain);
                state.hang = hang_samples;
            } else if (state.hang > 0) {
                --state.hang;
            } else {
                state.agc_gain += 0.0015F * (wanted - state.agc_gain);
            }
        } else {
            state.agc_gain = 1.0F;
            state.hang = 0;
        }
        value = squelched ? 0.0F : value * state.agc_gain * std::clamp(output_gain, 0.0F, 4.0F);
        if (std::fabs(value) > 0.98F) ++clipped;
        value = std::tanh(value);
        values[index] = value;
        output_energy += static_cast<double>(value) * value;
    }
    env->ReleaseFloatArrayElements(data, values, 0);
    const float processing_ms = std::chrono::duration<float, std::milli>(
        std::chrono::steady_clock::now() - started).count();
    const jfloat result[8] = {
        input_db,
        20.0F * std::log10(std::max(std::sqrt(static_cast<float>(output_energy / count)), 1.0e-6F)),
        squelched ? 1.0F : 0.0F,
        static_cast<float>(clipped) / static_cast<float>(count),
        std::max(0.0F, -20.0F * std::log10(std::max(state.agc_gain, 1.0e-5F))),
        state.notch_frequency,
        static_cast<float>(blanked),
        processing_ms,
    };
    env->SetFloatArrayRegion(metrics, 0, 8, result);
    return metrics;
}
