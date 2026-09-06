// SPDX-License-Identifier: GPL-3.0-only
#pragma once

#include <complex>
#include <array>
#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace shackcq {

enum class LocalReceiverMode : std::uint32_t {
    Usb, Lsb, Cw, Digu, Digl, Dsb, Am, Sam, Nfm, Wfm, Spectrum,
};

enum class SamLockState : std::uint32_t { Acquiring, Locked, Fallback };

struct LocalReceiverConfig {
    std::uint32_t input_sample_rate = 96000;
    std::uint32_t output_sample_rate = 48000;
    float offset_hz = 0.0F;
    LocalReceiverMode mode = LocalReceiverMode::Usb;
    float filter_low_hz = 300.0F;
    float filter_high_hz = 2700.0F;
    float cw_pitch_hz = 600.0F;
    float squelch_db = -110.0F;
    float fm_deemphasis_us = 75.0F;
};

struct LocalReceiverMetrics {
    float signal_db = -120.0F;
    float carrier_level = 0.0F;
    float modulation_depth = 0.0F;
    SamLockState sam_state = SamLockState::Acquiring;
    float sam_error_hz = 0.0F;
    float ctcss_hz = 0.0F;
    float ctcss_confidence = 0.0F;
    std::uint16_t dcs_code = 0;
    bool dcs_inverted = false;
    float dcs_confidence = 0.0F;
    float wfm_pilot = 0.0F;
    float stereo_separation_db = 0.0F;
    std::uint16_t rds_pi = 0;
    std::uint8_t rds_pty = 0;
    bool rds_tp = false;
    bool rds_ta = false;
    float rds_error_rate = 1.0F;
    bool rds_valid = false;
    std::string rds_ps;
    std::string rds_text;
    std::string rds_clock;
    std::vector<std::uint32_t> rds_af_khz;
    std::uint64_t input_frames = 0;
    std::uint64_t output_frames = 0;
};

struct LocalReceiverResult {
    std::vector<float> audio;
    std::uint32_t channels = 1;
    LocalReceiverMetrics metrics;
};

class LocalReceiverDsp {
public:
    LocalReceiverDsp();
    bool configure(const LocalReceiverConfig &config);
    LocalReceiverResult process(const float *interleaved_iq, std::size_t sample_count);
    void reset();
    const LocalReceiverConfig &config() const noexcept { return config_; }
    const LocalReceiverMetrics &metrics() const noexcept { return metrics_; }

    // Validated-block entry point used by the deterministic lab and golden vectors.
    bool consume_rds_group(std::uint16_t a, std::uint16_t b, std::uint16_t c, std::uint16_t d);
    bool consume_dcs_word(std::uint16_t code, bool inverted, unsigned corrected_bits = 0);

private:
    LocalReceiverConfig config_{};
    LocalReceiverMetrics metrics_{};
    std::vector<std::complex<float>> taps_;
    std::vector<std::complex<float>> delay_;
    std::size_t delay_index_ = 0;
    double nco_phase_ = 0.0;
    double output_phase_ = 0.0;
    std::complex<float> previous_{1.0F, 0.0F};
    float dc_ = 0.0F;
    float deemphasis_ = 0.0F;
    float sam_phase_ = 0.0F;
    float sam_frequency_ = 0.0F;
    std::uint32_t sam_locked_samples_ = 0;
    std::uint32_t sam_unlocked_samples_ = 0;
    double tone_window_seconds_ = 0.0;
    std::vector<float> tone_window_;
    double dcs_clock_ = 0.0;
    double dcs_integrator_ = 0.0;
    std::uint32_t dcs_samples_ = 0;
    std::uint32_t dcs_shift_ = 0;
    std::uint32_t dcs_bits_ = 0;
    float pilot_i_ = 0.0F;
    float pilot_q_ = 0.0F;
    double pilot_phase_ = 0.0;
    float stereo_sum_ = 0.0F;
    float stereo_diff_ = 0.0F;
    std::uint64_t rds_good_blocks_ = 0;
    std::uint64_t rds_bad_blocks_ = 0;
    std::string rds_ps_ = std::string(8, ' ');
    std::string rds_text_ = std::string(64, ' ');
    double rds_symbol_clock_ = 0.0;
    double rds_integrator_ = 0.0;
    bool rds_previous_symbol_ = false;
    std::uint32_t rds_shift_ = 0;
    std::uint32_t rds_bits_ = 0;
    std::array<std::uint16_t, 4> rds_group_{};
    std::uint32_t rds_group_index_ = 0;

    void design_filter();
    std::complex<float> filter(std::complex<float> value);
    float decode_ctcss(const std::vector<float> &audio, float &confidence) const;
    void decode_dcs(float sample);
    void decode_rds(float multiplex);
    bool consume_rds_block(std::uint32_t block);
    float demodulate(std::complex<float> value, float &right);
};

} // namespace shackcq
