// SPDX-License-Identifier: GPL-3.0-only
#include "shackcq/local_receiver.hpp"

#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <limits>
#include <iomanip>
#include <sstream>

namespace shackcq {
namespace {
constexpr float kPi = 3.14159265358979323846F;
constexpr std::size_t kMaximumInputSamples = 4U * 1024U * 1024U;
constexpr std::array<float, 50> kCtcssTones{
    67.0F, 69.3F, 71.9F, 74.4F, 77.0F, 79.7F, 82.5F, 85.4F, 88.5F, 91.5F,
    94.8F, 97.4F, 100.0F, 103.5F, 107.2F, 110.9F, 114.8F, 118.8F, 123.0F, 127.3F,
    131.8F, 136.5F, 141.3F, 146.2F, 151.4F, 156.7F, 159.8F, 162.2F, 165.5F, 167.9F,
    171.3F, 173.8F, 177.3F, 179.9F, 183.5F, 186.2F, 189.9F, 192.8F, 196.6F, 199.5F,
    203.5F, 206.5F, 210.7F, 218.1F, 225.7F, 229.1F, 233.6F, 241.8F, 250.3F, 254.1F,
};
constexpr std::array<std::uint16_t, 104> kDcsCodes{
    23, 25, 26, 31, 32, 36, 43, 47, 51, 53, 54, 65, 71, 72, 73, 74,
    114, 115, 116, 122, 125, 131, 132, 134, 143, 145, 152, 155, 156, 162, 165, 172,
    174, 205, 212, 223, 225, 226, 243, 244, 245, 246, 251, 252, 255, 261, 263, 265,
    266, 271, 274, 306, 311, 315, 325, 331, 332, 343, 346, 351, 356, 364, 365, 371,
    411, 412, 413, 423, 431, 432, 445, 446, 452, 454, 455, 462, 464, 465, 466, 503,
    506, 516, 523, 526, 532, 546, 565, 606, 612, 624, 627, 631, 632, 654, 662, 664,
    703, 712, 723, 731, 732, 734, 743, 754,
};

float db(float value) { return 20.0F * std::log10(std::max(value, 1.0e-6F)); }

std::uint32_t golay_word(std::uint16_t octal_code) {
    const std::uint16_t digits = static_cast<std::uint16_t>((octal_code / 100U) * 64U + ((octal_code / 10U) % 10U) * 8U + octal_code % 10U);
    const std::uint16_t data = static_cast<std::uint16_t>((digits & 0x1ffU) | 0x800U);
    std::uint32_t work = static_cast<std::uint32_t>(data) << 11U;
    constexpr std::uint32_t polynomial = 0xAE3U;
    for (int bit = 22; bit >= 11; --bit) if ((work & (1U << bit)) != 0U) work ^= polynomial << (bit - 11);
    return (static_cast<std::uint32_t>(data) << 11U) | (work & 0x7ffU);
}

unsigned distance(std::uint32_t a, std::uint32_t b) {
    std::uint32_t value = a ^ b;
    unsigned count = 0;
    while (value != 0U) { value &= value - 1U; ++count; }
    return count;
}

bool supported(LocalReceiverMode mode, std::uint32_t rate) {
    if (rate < 48000U || rate > 384000U) return false;
    return mode != LocalReceiverMode::Wfm || rate >= 192000U;
}
}

LocalReceiverDsp::LocalReceiverDsp() { configure(config_); }

bool LocalReceiverDsp::configure(const LocalReceiverConfig &value) {
    if (!supported(value.mode, value.input_sample_rate) || value.output_sample_rate != 48000U ||
        !std::isfinite(value.offset_hz) || std::fabs(value.offset_hz) > value.input_sample_rate * 0.48F ||
        value.filter_low_hz < 0.0F || value.filter_high_hz <= value.filter_low_hz ||
        value.filter_high_hz > value.input_sample_rate * (value.mode == LocalReceiverMode::Wfm ? 0.495F : 0.46F) || value.cw_pitch_hz < 100.0F ||
        value.cw_pitch_hz > 2000.0F || (value.fm_deemphasis_us != 50.0F && value.fm_deemphasis_us != 75.0F)) return false;
    const bool generation_change = value.input_sample_rate != config_.input_sample_rate || value.mode != config_.mode ||
        value.filter_low_hz != config_.filter_low_hz || value.filter_high_hz != config_.filter_high_hz;
    config_ = value;
    design_filter();
    if (generation_change) reset();
    return true;
}

void LocalReceiverDsp::design_filter() {
    constexpr int length = 129;
    taps_.assign(length, {});
    const float rate = static_cast<float>(config_.input_sample_rate);
    float low = -config_.filter_high_hz;
    float high = config_.filter_high_hz;
    if (config_.mode == LocalReceiverMode::Usb || config_.mode == LocalReceiverMode::Digu) {
        low = config_.filter_low_hz; high = config_.filter_high_hz;
    } else if (config_.mode == LocalReceiverMode::Lsb || config_.mode == LocalReceiverMode::Digl) {
        low = -config_.filter_high_hz; high = -config_.filter_low_hz;
    } else if (config_.mode == LocalReceiverMode::Cw) {
        const float half = (config_.filter_high_hz - config_.filter_low_hz) * 0.5F;
        low = config_.cw_pitch_hz - half; high = config_.cw_pitch_hz + half;
    } else if (config_.mode == LocalReceiverMode::Wfm) {
        low = -95000.0F; high = 95000.0F;
    } else if (config_.mode == LocalReceiverMode::Nfm) {
        low = -config_.filter_high_hz * 0.5F; high = config_.filter_high_hz * 0.5F;
    }
    const int middle = length / 2;
    for (int index = 0; index < length; ++index) {
        const int n = index - middle;
        std::complex<float> coefficient;
        if (n == 0) coefficient = {(high - low) / rate, 0.0F};
        else {
            const std::complex<float> upper = std::polar(1.0F, 2.0F * kPi * high * n / rate);
            const std::complex<float> lower = std::polar(1.0F, 2.0F * kPi * low * n / rate);
            coefficient = (upper - lower) / std::complex<float>(0.0F, 2.0F * kPi * static_cast<float>(n));
        }
        const float window = 0.54F - 0.46F * std::cos(2.0F * kPi * index / static_cast<float>(length - 1));
        taps_[static_cast<std::size_t>(index)] = coefficient * window;
    }
    delay_.assign(taps_.size(), {});
    delay_index_ = 0;
}

void LocalReceiverDsp::reset() {
    std::fill(delay_.begin(), delay_.end(), std::complex<float>{});
    delay_index_ = 0; nco_phase_ = 0.0; output_phase_ = 0.0; previous_ = {1.0F, 0.0F};
    dc_ = 0.0F; deemphasis_ = 0.0F; sam_phase_ = 0.0F; sam_frequency_ = 0.0F;
    sam_locked_samples_ = 0; sam_unlocked_samples_ = 0; tone_window_.clear(); tone_window_seconds_ = 0.0;
    dcs_clock_ = 0.0; dcs_integrator_ = 0.0; dcs_samples_ = 0; dcs_shift_ = 0; dcs_bits_ = 0;
    pilot_i_ = pilot_q_ = stereo_sum_ = stereo_diff_ = 0.0F; pilot_phase_ = 0.0;
    metrics_ = LocalReceiverMetrics{}; rds_good_blocks_ = rds_bad_blocks_ = 0;
    rds_ps_.assign(8, ' '); rds_text_.assign(64, ' ');
    rds_symbol_clock_ = 0.0; rds_integrator_ = 0.0; rds_previous_symbol_ = false; rds_shift_ = 0; rds_bits_ = 0;
    rds_group_.fill(0); rds_group_index_ = 0;
}

std::complex<float> LocalReceiverDsp::filter(std::complex<float> value) {
    delay_[delay_index_] = value;
    std::complex<float> output{};
    std::size_t sample = delay_index_;
    for (const auto tap : taps_) { output += delay_[sample] * tap; sample = sample == 0 ? delay_.size() - 1 : sample - 1; }
    delay_index_ = (delay_index_ + 1) % delay_.size();
    return output;
}

float LocalReceiverDsp::decode_ctcss(const std::vector<float> &audio, float &confidence) const {
    if (audio.size() < 2400U) { confidence = 0.0F; return 0.0F; }
    double total = 0.0, best = 0.0, second = 0.0;
    float selected = 0.0F;
    for (const float sample : audio) total += static_cast<double>(sample) * sample;
    for (const float frequency : kCtcssTones) {
        const float coefficient = 2.0F * std::cos(2.0F * kPi * frequency / config_.output_sample_rate);
        float q0 = 0.0F, q1 = 0.0F, q2 = 0.0F;
        for (const float sample : audio) { q0 = coefficient * q1 - q2 + sample; q2 = q1; q1 = q0; }
        const double power = q1 * q1 + q2 * q2 - coefficient * q1 * q2;
        if (power > best) { second = best; best = power; selected = frequency; } else if (power > second) second = power;
    }
    confidence = static_cast<float>(std::clamp((best - second) / std::max(best, 1.0e-9), 0.0, 1.0));
    return best > total * audio.size() * 0.035 && confidence > 0.18F ? selected : 0.0F;
}

void LocalReceiverDsp::decode_dcs(float sample) {
    dcs_integrator_ += sample; ++dcs_samples_;
    dcs_clock_ += 134.4 / static_cast<double>(config_.output_sample_rate);
    if (dcs_clock_ < 1.0) return;
    dcs_clock_ -= 1.0;
    const bool bit = dcs_integrator_ >= 0.0;
    dcs_integrator_ = 0.0; dcs_samples_ = 0;
    dcs_shift_ = ((dcs_shift_ << 1U) | (bit ? 1U : 0U)) & 0x7fffffU;
    if (dcs_bits_ < 23U) { ++dcs_bits_; return; }
    unsigned best = 24U; std::uint16_t code = 0; bool inverted = false;
    for (const auto candidate : kDcsCodes) {
        const auto word = golay_word(candidate);
        const unsigned normal = distance(dcs_shift_, word);
        const unsigned reverse = distance(dcs_shift_, (~word) & 0x7fffffU);
        if (normal < best) { best = normal; code = candidate; inverted = false; }
        if (reverse < best) { best = reverse; code = candidate; inverted = true; }
    }
    if (best <= 2U) {
        const float confidence = 1.0F - static_cast<float>(best) / 3.0F;
        if (metrics_.dcs_code == code && metrics_.dcs_inverted == inverted) metrics_.dcs_confidence = std::min(1.0F, metrics_.dcs_confidence + 0.2F);
        else if (confidence > metrics_.dcs_confidence) { metrics_.dcs_code = code; metrics_.dcs_inverted = inverted; metrics_.dcs_confidence = confidence * 0.6F; }
    } else metrics_.dcs_confidence *= 0.995F;
    if (metrics_.dcs_confidence < 0.2F) metrics_.dcs_code = 0;
}

bool LocalReceiverDsp::consume_dcs_word(std::uint16_t code, bool inverted, unsigned corrected_bits) {
    if (std::find(kDcsCodes.begin(), kDcsCodes.end(), code) == kDcsCodes.end() || corrected_bits > 2U) return false;
    metrics_.dcs_code = code; metrics_.dcs_inverted = inverted;
    metrics_.dcs_confidence = 1.0F - static_cast<float>(corrected_bits) / 3.0F;
    return true;
}

bool LocalReceiverDsp::consume_rds_block(std::uint32_t block) {
    constexpr std::array<std::uint16_t, 5> syndromes{0x0FCU, 0x198U, 0x168U, 0x350U, 0x1B4U};
    std::uint32_t remainder = block & 0x3ffffffU;
    constexpr std::uint32_t polynomial = 0x5B9U;
    for (int bit = 25; bit >= 10; --bit) if ((remainder & (1U << bit)) != 0U) remainder ^= polynomial << (bit - 10);
    const std::uint16_t syndrome = static_cast<std::uint16_t>(remainder & 0x3ffU);
    const unsigned expected = rds_group_index_ == 3U ? 4U : rds_group_index_;
    const bool valid = syndrome == syndromes[expected] || (rds_group_index_ == 2U && syndrome == syndromes[3]);
    if (!valid) { ++rds_bad_blocks_; rds_group_index_ = 0; return false; }
    rds_group_[rds_group_index_++] = static_cast<std::uint16_t>(block >> 10U);
    ++rds_good_blocks_;
    if (rds_group_index_ == 4U) {
        rds_group_index_ = 0;
        return consume_rds_group(rds_group_[0], rds_group_[1], rds_group_[2], rds_group_[3]);
    }
    return true;
}

void LocalReceiverDsp::decode_rds(float multiplex) {
    if (config_.input_sample_rate < 192000U || metrics_.wfm_pilot < 0.05F) return;
    rds_integrator_ += multiplex * std::cos(static_cast<float>(3.0 * pilot_phase_));
    rds_symbol_clock_ += 1187.5 / static_cast<double>(config_.input_sample_rate);
    if (rds_symbol_clock_ < 1.0) return;
    rds_symbol_clock_ -= 1.0;
    const bool symbol = rds_integrator_ >= 0.0;
    const bool bit = symbol != rds_previous_symbol_;
    rds_previous_symbol_ = symbol; rds_integrator_ = 0.0;
    rds_shift_ = ((rds_shift_ << 1U) | (bit ? 1U : 0U)) & 0x3ffffffU;
    if (rds_bits_ < 26U) { ++rds_bits_; return; }
    if (consume_rds_block(rds_shift_)) rds_bits_ = 0;
}

float LocalReceiverDsp::demodulate(std::complex<float> value, float &right) {
    right = 0.0F;
    const auto mode = config_.mode;
    if (mode == LocalReceiverMode::Spectrum) return 0.0F;
    if (mode == LocalReceiverMode::Am) {
        const float envelope = std::abs(value);
        dc_ += 0.002F * (envelope - dc_);
        metrics_.carrier_level = dc_;
        metrics_.modulation_depth = std::min(1.0F, std::fabs(envelope - dc_) / std::max(dc_, 1.0e-5F));
        return envelope - dc_;
    }
    if (mode == LocalReceiverMode::Sam) {
        const std::complex<float> oscillator = std::polar(1.0F, -sam_phase_);
        const auto locked = value * oscillator;
        const float error = std::atan2(locked.imag(), std::max(std::fabs(locked.real()), 1.0e-6F));
        sam_frequency_ = std::clamp(sam_frequency_ + error * 0.00004F, -0.02F, 0.02F);
        sam_phase_ += sam_frequency_ + error * 0.008F;
        if (std::fabs(error) < 0.35F) { ++sam_locked_samples_; sam_unlocked_samples_ = 0; }
        else { sam_locked_samples_ = 0; ++sam_unlocked_samples_; }
        metrics_.sam_error_hz = sam_frequency_ * config_.input_sample_rate / (2.0F * kPi);
        metrics_.sam_state = sam_locked_samples_ > config_.input_sample_rate / 4U ? SamLockState::Locked :
            (sam_unlocked_samples_ > config_.input_sample_rate ? SamLockState::Fallback : SamLockState::Acquiring);
        const float envelope = std::abs(value);
        dc_ += 0.002F * (envelope - dc_);
        return metrics_.sam_state == SamLockState::Fallback ? envelope - dc_ : locked.real();
    }
    if (mode == LocalReceiverMode::Nfm || mode == LocalReceiverMode::Wfm) {
        const float phase = std::arg(value * std::conj(previous_));
        previous_ = value;
        float audio = phase / kPi;
        if (mode == LocalReceiverMode::Wfm) {
            pilot_phase_ += 2.0 * kPi * 19000.0 / config_.input_sample_rate;
            if (pilot_phase_ > 2.0 * kPi) pilot_phase_ -= 2.0 * kPi;
            pilot_i_ = pilot_i_ * 0.999F + audio * std::cos(static_cast<float>(pilot_phase_)) * 0.001F;
            pilot_q_ = pilot_q_ * 0.999F + audio * std::sin(static_cast<float>(pilot_phase_)) * 0.001F;
            metrics_.wfm_pilot = std::min(1.0F, std::hypot(pilot_i_, pilot_q_) * 20.0F);
            decode_rds(audio);
            const float difference = audio * 2.0F * std::cos(static_cast<float>(2.0 * pilot_phase_));
            stereo_sum_ += 0.08F * (audio - stereo_sum_);
            stereo_diff_ += 0.08F * (difference - stereo_diff_);
            const float blend = std::clamp((metrics_.wfm_pilot - 0.08F) * 4.0F, 0.0F, 1.0F);
            const float left = stereo_sum_ + stereo_diff_ * blend;
            right = stereo_sum_ - stereo_diff_ * blend;
            metrics_.stereo_separation_db = blend > 0.01F ? 12.0F + 24.0F * blend : 0.0F;
            audio = left;
        }
        const float tau = config_.fm_deemphasis_us * 1.0e-6F;
        const float alpha = 1.0F - std::exp(-1.0F / (config_.input_sample_rate * tau));
        deemphasis_ += alpha * (audio - deemphasis_);
        return deemphasis_;
    }
    return value.real() * 2.0F;
}

LocalReceiverResult LocalReceiverDsp::process(const float *iq, std::size_t sample_count) {
    LocalReceiverResult result;
    result.channels = config_.mode == LocalReceiverMode::Wfm ? 2U : 1U;
    if (iq == nullptr || sample_count == 0U || sample_count > kMaximumInputSamples || sample_count % 2U != 0U) { result.metrics = metrics_; return result; }
    const std::size_t frames = sample_count / 2U;
    result.audio.reserve(frames * config_.output_sample_rate / config_.input_sample_rate * result.channels + 4U);
    double energy = 0.0;
    const double step = -2.0 * kPi * config_.offset_hz / config_.input_sample_rate;
    for (std::size_t index = 0; index < frames; ++index) {
        const std::complex<float> input(std::isfinite(iq[index * 2U]) ? iq[index * 2U] : 0.0F,
            std::isfinite(iq[index * 2U + 1U]) ? iq[index * 2U + 1U] : 0.0F);
        energy += std::norm(input);
        const auto translated = input * std::polar(1.0F, static_cast<float>(nco_phase_));
        nco_phase_ += step;
        if (nco_phase_ > kPi) nco_phase_ -= 2.0 * kPi;
        if (nco_phase_ < -kPi) nco_phase_ += 2.0 * kPi;
        const auto channel = filter(translated);
        output_phase_ += config_.output_sample_rate;
        if (output_phase_ < config_.input_sample_rate) continue;
        output_phase_ -= config_.input_sample_rate;
        float right = 0.0F;
        const float left = demodulate(channel, right);
        if (config_.mode != LocalReceiverMode::Spectrum) {
            result.audio.push_back(std::clamp(left, -1.5F, 1.5F));
            if (result.channels == 2U) result.audio.push_back(std::clamp(right, -1.5F, 1.5F));
        }
    }
    metrics_.input_frames += frames;
    metrics_.output_frames += result.audio.size() / result.channels;
    metrics_.signal_db = db(std::sqrt(static_cast<float>(energy / std::max<std::size_t>(frames, 1U))));
    if ((config_.mode == LocalReceiverMode::Nfm || config_.mode == LocalReceiverMode::Wfm) && result.channels == 1U) {
        tone_window_.insert(tone_window_.end(), result.audio.begin(), result.audio.end());
        decode_dcs(0.0F);
        for (const float sample : result.audio) decode_dcs(sample);
        tone_window_seconds_ += static_cast<double>(result.audio.size()) / config_.output_sample_rate;
        if (tone_window_seconds_ >= 0.25) {
            if (tone_window_.size() > config_.output_sample_rate / 2U) tone_window_.erase(tone_window_.begin(), tone_window_.end() - config_.output_sample_rate / 2U);
            metrics_.ctcss_hz = decode_ctcss(tone_window_, metrics_.ctcss_confidence);
            tone_window_.clear(); tone_window_seconds_ = 0.0;
        }
    }
    metrics_.rds_ps = rds_ps_; metrics_.rds_text = rds_text_;
    metrics_.rds_error_rate = static_cast<float>(rds_bad_blocks_) / std::max<std::uint64_t>(1U, rds_good_blocks_ + rds_bad_blocks_);
    result.metrics = metrics_;
    return result;
}

bool LocalReceiverDsp::consume_rds_group(std::uint16_t a, std::uint16_t b, std::uint16_t c, std::uint16_t d) {
    metrics_.rds_pi = a;
    const std::uint8_t group = static_cast<std::uint8_t>((b >> 12U) & 0x0fU);
    metrics_.rds_pty = static_cast<std::uint8_t>((b >> 5U) & 0x1fU);
    metrics_.rds_tp = (b & 0x0400U) != 0U;
    if (group == 0U) {
        metrics_.rds_ta = (b & 0x0010U) != 0U;
        const std::size_t segment = static_cast<std::size_t>(b & 0x03U) * 2U;
        rds_ps_[segment] = static_cast<char>((d >> 8U) & 0xffU); rds_ps_[segment + 1U] = static_cast<char>(d & 0xffU);
        const std::uint8_t af1 = static_cast<std::uint8_t>(c >> 8U), af2 = static_cast<std::uint8_t>(c & 0xffU);
        for (const auto af : {af1, af2}) if (af >= 1U && af <= 204U) {
            const std::uint32_t khz = 87500U + static_cast<std::uint32_t>(af) * 100U;
            if (std::find(metrics_.rds_af_khz.begin(), metrics_.rds_af_khz.end(), khz) == metrics_.rds_af_khz.end()) metrics_.rds_af_khz.push_back(khz);
        }
    } else if (group == 2U) {
        const std::size_t segment = static_cast<std::size_t>(b & 0x0fU) * 4U;
        if (segment + 3U < rds_text_.size()) {
            rds_text_[segment] = static_cast<char>((c >> 8U) & 0xffU); rds_text_[segment + 1U] = static_cast<char>(c & 0xffU);
            rds_text_[segment + 2U] = static_cast<char>((d >> 8U) & 0xffU); rds_text_[segment + 3U] = static_cast<char>(d & 0xffU);
        }
    } else if (group == 4U && (b & 0x0800U) == 0U) {
        const std::uint32_t mjd = (static_cast<std::uint32_t>(b & 0x0003U) << 15U) | (c >> 1U);
        const unsigned hour = static_cast<unsigned>(((c & 0x0001U) << 4U) | (d >> 12U));
        const unsigned minute = static_cast<unsigned>((d >> 6U) & 0x003fU);
        const unsigned offset_half_hours = static_cast<unsigned>(d & 0x001fU);
        if (mjd >= 15079U && hour < 24U && minute < 60U && offset_half_hours <= 24U) {
            std::ostringstream clock;
            clock << "MJD " << mjd << ' ' << std::setfill('0') << std::setw(2) << hour << ':' << std::setw(2) << minute
                  << " UTC" << ((d & 0x0020U) != 0U ? '-' : '+') << offset_half_hours / 2U
                  << (offset_half_hours % 2U == 0U ? ":00" : ":30");
            metrics_.rds_clock = clock.str();
        }
    }
    ++rds_good_blocks_; metrics_.rds_valid = true; metrics_.rds_ps = rds_ps_; metrics_.rds_text = rds_text_;
    return true;
}

} // namespace shackcq
