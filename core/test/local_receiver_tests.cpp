// SPDX-License-Identifier: GPL-3.0-only
#include "shackcq/local_receiver.hpp"

#include <algorithm>
#include <cassert>
#include <cmath>
#include <complex>
#include <iostream>
#include <vector>

namespace {
constexpr float kPi = 3.14159265358979323846F;

std::vector<float> tone(std::uint32_t rate, float hz, float seconds, float amplitude = 0.5F) {
    const std::size_t frames = static_cast<std::size_t>(rate * seconds);
    std::vector<float> values(frames * 2U);
    for (std::size_t index = 0; index < frames; ++index) {
        const float phase = 2.0F * kPi * hz * index / rate;
        values[index * 2U] = amplitude * std::cos(phase);
        values[index * 2U + 1U] = amplitude * std::sin(phase);
    }
    return values;
}

float rms(const std::vector<float> &values) {
    double energy = 0.0; for (const float value : values) energy += value * value;
    return std::sqrt(static_cast<float>(energy / std::max<std::size_t>(1U, values.size())));
}

std::vector<float> fm_tone(std::uint32_t rate, float audio_hz, float deviation_hz, float seconds) {
    const std::size_t frames = static_cast<std::size_t>(rate * seconds);
    std::vector<float> values(frames * 2U);
    double phase = 0.0;
    for (std::size_t index = 0; index < frames; ++index) {
        phase += 2.0 * kPi * deviation_hz * std::sin(2.0 * kPi * audio_hz * index / rate) / rate;
        values[index * 2U] = 0.5F * std::cos(static_cast<float>(phase));
        values[index * 2U + 1U] = 0.5F * std::sin(static_cast<float>(phase));
    }
    return values;
}
}

int main() {
    shackcq::LocalReceiverDsp receiver;
    shackcq::LocalReceiverConfig config;
    config.input_sample_rate = 96000; config.mode = shackcq::LocalReceiverMode::Usb;
    assert(receiver.configure(config));
    const auto usb = receiver.process(tone(96000, 1200.0F, 0.2F).data(), 96000U / 5U * 2U);
    receiver.reset(); config.mode = shackcq::LocalReceiverMode::Lsb; assert(receiver.configure(config));
    const auto rejected = receiver.process(tone(96000, 1200.0F, 0.2F).data(), 96000U / 5U * 2U);
    assert(rms(usb.audio) > rms(rejected.audio) * 3.0F);

    config.mode = shackcq::LocalReceiverMode::Cw; config.filter_low_hz = 500; config.filter_high_hz = 700; config.cw_pitch_hz = 600;
    assert(receiver.configure(config));
    assert(!receiver.process(tone(96000, 600.0F, 0.2F).data(), 96000U / 5U * 2U).audio.empty());

    config.mode = shackcq::LocalReceiverMode::Am; config.filter_low_hz = 50; config.filter_high_hz = 6000;
    assert(receiver.configure(config));
    auto am = tone(96000, 0.0F, 0.3F, 0.6F);
    for (std::size_t i = 0; i < am.size() / 2U; ++i) { const float envelope = 1.0F + 0.4F * std::sin(2.0F * kPi * 1000.0F * i / 96000.0F); am[i*2U] *= envelope; }
    const auto am_result = receiver.process(am.data(), am.size());
    assert(am_result.metrics.carrier_level > 0.05F && am_result.metrics.modulation_depth > 0.01F);

    config.mode = shackcq::LocalReceiverMode::Sam; assert(receiver.configure(config));
    for (int pass = 0; pass < 6; ++pass) receiver.process(am.data(), am.size());
    const auto sam = receiver.process(am.data(), am.size());
    assert(sam.metrics.sam_state != shackcq::SamLockState::Fallback);

    config.mode = shackcq::LocalReceiverMode::Nfm; config.filter_low_hz = 0; config.filter_high_hz = 20000;
    assert(receiver.configure(config));
    auto fm = tone(96000, 5000.0F, 0.4F);
    const auto nfm = receiver.process(fm.data(), fm.size());
    assert(!nfm.audio.empty() && std::isfinite(nfm.metrics.signal_db));
    receiver.reset();
    const auto ctcss_iq = fm_tone(96000, 88.5F, 600.0F, 0.6F);
    const auto ctcss = receiver.process(ctcss_iq.data(), ctcss_iq.size());
    assert(std::abs(ctcss.metrics.ctcss_hz - 88.5F) < 0.2F && ctcss.metrics.ctcss_confidence > 0.18F);
    constexpr std::array<float, 50> all_ctcss{
        67.0F, 69.3F, 71.9F, 74.4F, 77.0F, 79.7F, 82.5F, 85.4F, 88.5F, 91.5F,
        94.8F, 97.4F, 100.0F, 103.5F, 107.2F, 110.9F, 114.8F, 118.8F, 123.0F, 127.3F,
        131.8F, 136.5F, 141.3F, 146.2F, 151.4F, 156.7F, 159.8F, 162.2F, 165.5F, 167.9F,
        171.3F, 173.8F, 177.3F, 179.9F, 183.5F, 186.2F, 189.9F, 192.8F, 196.6F, 199.5F,
        203.5F, 206.5F, 210.7F, 218.1F, 225.7F, 229.1F, 233.6F, 241.8F, 250.3F, 254.1F,
    };
    for (const float expected : all_ctcss) {
        receiver.reset();
        const auto input = fm_tone(96000, expected, 600.0F, 0.3F);
        const auto decoded = receiver.process(input.data(), input.size()).metrics;
        assert(std::abs(decoded.ctcss_hz - expected) < 0.2F && decoded.ctcss_confidence > 0.18F);
    }
    assert(receiver.consume_dcs_word(23, false));
    assert(receiver.process(ctcss_iq.data(), ctcss_iq.size()).metrics.dcs_code == 23);
    assert(receiver.consume_dcs_word(23, true));
    const auto inverted_dcs = receiver.process(ctcss_iq.data(), ctcss_iq.size()).metrics;
    assert(inverted_dcs.dcs_code == 23 && inverted_dcs.dcs_inverted);
    constexpr std::array<std::uint16_t, 104> all_dcs{
        23, 25, 26, 31, 32, 36, 43, 47, 51, 53, 54, 65, 71, 72, 73, 74,
        114, 115, 116, 122, 125, 131, 132, 134, 143, 145, 152, 155, 156, 162, 165, 172,
        174, 205, 212, 223, 225, 226, 243, 244, 245, 246, 251, 252, 255, 261, 263, 265,
        266, 271, 274, 306, 311, 315, 325, 331, 332, 343, 346, 351, 356, 364, 365, 371,
        411, 412, 413, 423, 431, 432, 445, 446, 452, 454, 455, 462, 464, 465, 466, 503,
        506, 516, 523, 526, 532, 546, 565, 606, 612, 624, 627, 631, 632, 654, 662, 664,
        703, 712, 723, 731, 732, 734, 743, 754,
    };
    for (const auto code : all_dcs) {
        assert(receiver.consume_dcs_word(code, false));
        assert(receiver.metrics().dcs_code == code && !receiver.metrics().dcs_inverted);
        assert(receiver.consume_dcs_word(code, true));
        assert(receiver.metrics().dcs_code == code && receiver.metrics().dcs_inverted);
    }
    assert(!receiver.consume_dcs_word(999, false));

    config.input_sample_rate = 192000; config.mode = shackcq::LocalReceiverMode::Wfm; config.filter_high_hz = 95000;
    assert(receiver.configure(config));
    const auto wfm_iq = tone(192000, 19000.0F, 0.3F);
    const auto wfm = receiver.process(wfm_iq.data(), wfm_iq.size());
    assert(wfm.channels == 2U && !wfm.audio.empty());
    assert(receiver.consume_rds_group(0x1234, 0x0410, 0x0102, 0x5445));
    assert(receiver.consume_rds_group(0x1234, 0x0411, 0x0304, 0x5354));
    assert(receiver.consume_rds_group(0x1234, 0x0412, 0x0506, 0x2046));
    assert(receiver.consume_rds_group(0x1234, 0x0413, 0x0708, 0x4D20));
    const auto rds = receiver.process(wfm_iq.data(), wfm_iq.size());
    assert(rds.metrics.rds_valid && rds.metrics.rds_pi == 0x1234 && rds.metrics.rds_tp && rds.metrics.rds_ta &&
        rds.metrics.rds_ps.find("TEST FM") != std::string::npos);
    assert(receiver.consume_rds_group(0x1234, 0x2000, 0x5241, 0x4449));
    assert(receiver.consume_rds_group(0x1234, 0x2001, 0x4F54, 0x4558));
    assert(receiver.process(wfm_iq.data(), wfm_iq.size()).metrics.rds_text.find("RADIOTEX") != std::string::npos);
    assert(receiver.consume_rds_group(0x1234, 0x4000, 0xEA60, 0xC780));
    assert(!receiver.process(wfm_iq.data(), wfm_iq.size()).metrics.rds_clock.empty());

    config.input_sample_rate = 96000; config.mode = shackcq::LocalReceiverMode::Spectrum; config.filter_low_hz = 0; config.filter_high_hz = 3000;
    assert(receiver.configure(config));
    assert(receiver.process(fm.data(), fm.size()).audio.empty());
    config.offset_hz = 50000; assert(!receiver.configure(config));

    // Deterministic churn profiles cover bounded add/remove-equivalent resets, mode and source-rate changes.
    config.offset_hz = 0; config.filter_low_hz = 300; config.filter_high_hz = 2700;
    for (int cycle = 0; cycle < 1000; ++cycle) receiver.reset();
    const std::array<shackcq::LocalReceiverMode, 10> modes{
        shackcq::LocalReceiverMode::Usb, shackcq::LocalReceiverMode::Lsb, shackcq::LocalReceiverMode::Cw,
        shackcq::LocalReceiverMode::Digu, shackcq::LocalReceiverMode::Digl, shackcq::LocalReceiverMode::Dsb,
        shackcq::LocalReceiverMode::Am, shackcq::LocalReceiverMode::Sam, shackcq::LocalReceiverMode::Nfm,
        shackcq::LocalReceiverMode::Spectrum,
    };
    for (int cycle = 0; cycle < 500; ++cycle) {
        config.input_sample_rate = std::array<std::uint32_t, 3>{48000, 96000, 192000}[cycle % 3];
        config.mode = modes[cycle % modes.size()];
        const auto defaults = config.mode == shackcq::LocalReceiverMode::Nfm ? std::pair<float,float>{0,12500} : std::pair<float,float>{300,2700};
        config.filter_low_hz = defaults.first; config.filter_high_hz = defaults.second;
        assert(receiver.configure(config));
    }
    std::cout << "ShackCQ local receiver tests passed\n";
}
