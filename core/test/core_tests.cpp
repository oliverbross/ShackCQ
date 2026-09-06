#include "shackcq/core.h"
#include "kx3/dx_analysis.hpp"
#include "kx3/spot.hpp"
#include "shackcq/satellite.h"
#include "SGP4.h"
#include "Tle.h"

#include <algorithm>
#include <cassert>
#include <array>
#include <cmath>
#include <cstring>
#include <complex>
#include <iostream>
#include <limits>
#include <string>
#include <vector>

int main() {
    shackcq_context *context = shackcq_context_create();
    assert(context != nullptr);
    auto state = shackcq_context_state(context);
    assert(state.connected == 0 && state.transmitting == 0);
    assert(state.vfo_a_hz == 0 && std::string(state.model) == "UNIDENTIFIED");

    const char *frames = "ID017;K30;OM A-F-------02;FA00014074000;MD2;IF00014075000     +000010 0002001001 ;TQ0;";
    assert(shackcq_context_feed(context, frames, std::strlen(frames)) == 7);
    state = shackcq_context_state(context);
    assert(state.connected == 1);
    assert(std::string(state.model) == "KX3" && std::string(state.mode) == "USB");
    assert(state.vfo_a_hz == 14075000 && state.transmitting == 0);
    assert(state.rit == 1 && state.xit == 0 && state.split == 1);
    assert(state.rit_xit_offset_hz == 0 && state.effective_rx_hz == 14075000);
    assert(state.effective_tx_hz == 14075000 && state.updated_monotonic_ms > 0);
    const char *rit_offset = "IF00014075000     -012310 0002001001 ;";
    assert(shackcq_context_feed(context, rit_offset, std::strlen(rit_offset)) == 1);
    state = shackcq_context_state(context);
    assert(state.rit_xit_offset_hz == -123 && state.effective_rx_hz == 14074877);

    shackcq_context *kx2_context = shackcq_context_create();
    const char *kx2_identity = "ID017;K30;OM A-F-------01;";
    assert(shackcq_context_feed(kx2_context, kx2_identity, std::strlen(kx2_identity)) == 3);
    assert(std::string(shackcq_context_state(kx2_context).model) == "KX2");
    shackcq_context_destroy(kx2_context);

    const char *instrument = "FB00007030000;SM18;SW14;PO37;AG190;RG210;BW0270;PC010;ML045;MG030;KS020;IS 1500;PA1;RA01;RT1;XT0;FR0;FT1;GT004;";
    assert(shackcq_context_feed(context, instrument, std::strlen(instrument)) == 19);
    state = shackcq_context_state(context);
    assert(state.vfo_b_hz == 7030000 && state.meter == 18);
    assert(state.swr_tenths == 14 && state.rf_output_tenths == 37);
    assert(state.af_gain == 190 && state.rf_gain == 210 && state.bandwidth_hz == 2700);
    assert(state.power_w == 10 && state.preamp == 1 && state.attenuator == 1);
    assert(state.monitor_level == 45 && state.mic_gain == 30 && state.keyer_speed == 20 && state.if_shift_hz == 1500);
    assert(state.rit == 1 && state.xit == 0 && state.split == 1);
    assert(state.agc_mode == 4 && state.rx_vfo == 0 && state.tx_vfo == 1);

    std::string display = "DS12345678";
    display.push_back(static_cast<char>(0x98));
    display.push_back(static_cast<char>(0x88));
    display.push_back(';');
    assert(shackcq_context_feed(context, display.data(), display.size()) == 1);
    state = shackcq_context_state(context);
    assert(state.preamp == 1 && state.attenuator == 1 && state.cwt == 1);

    shackcq_context_reset(context);
    state = shackcq_context_state(context);
    assert(state.connected == 0 && std::string(state.model) == "UNIDENTIFIED");

    std::string oversized(600, 'A');
    assert(shackcq_context_feed(context, oversized.data(), oversized.size()) == 0);
    const char *overflowing_numbers = "SM999999999999999999999999999999;";
    assert(shackcq_context_feed(context, overflowing_numbers, std::strlen(overflowing_numbers)) == 0);
    state = shackcq_context_state(context);
    assert(state.vfo_a_hz == 0 && state.effective_rx_hz == 0);
    assert(shackcq_startup_command_count() == 0);
    for (const char *query : {"K3;", "OM;", "ID;", "FA;", "MD;", "IF;", "TQ;", "ML;", "MG;", "KS;", "IS;"})
        assert(shackcq_classify_command(query) == SHACKCQ_COMMAND_READ_ONLY);
    for (const char *unsafe : {"TX;", "RX;", "SWT44;", "SWH28;", "KY CQ;"})
        assert(shackcq_classify_command(unsafe) == SHACKCQ_COMMAND_TRANSMIT);
    assert(shackcq_classify_command("FA00014074000;") == SHACKCQ_COMMAND_MUTATION);

    char first[32]{};
    char second[32]{};
    assert(shackcq_qso_identity(first, sizeof(first), "vk8abc", "2026-08-14T01:02:03Z", 14074000, "USB"));
    assert(shackcq_qso_identity(second, sizeof(second), " VK8ABC ", "2026-08-14T01:02:03Z", 14074000, "usb"));
    assert(std::string(first) == second);

    char adif[512]{};
    const int length = shackcq_adif_serialize(adif, sizeof(adif), first, "VK8ABC", "20260814", "010203",
                                         14074000, "USB", "59", "57");
    assert(length > 0);
    assert(std::string(adif).find("<CALL:6>VK8ABC") != std::string::npos);
    assert(std::string(adif).find("<APP_KX3TOUCH_UUID:19>rw-") != std::string::npos);
    assert(std::string(adif).find("<EOR>") != std::string::npos);

    assert(kx3::spot_band(70'200'000ULL) == "4m");
    assert(kx3::spot_band(144'300'000ULL) == "2m");
    assert(kx3::spot_band(432'200'000ULL) == "70cm");
    assert(kx3::spot_band(1'296'200'000ULL) == "23cm");
    assert(kx3::spot_band(10'489'860'000ULL) == "3cm");
    for (const auto frequency : {100'000'000ULL, 222'000'000ULL, 902'000'000ULL,
                                 2'300'000'000ULL, 5'700'000'000ULL})
        assert(kx3::spot_band(frequency) == "other");
    static_assert(kx3::kDxBandCount == 16U);
    assert(kx3::dx_live_filter_matches("6m", "FT8", false, 1U << 10U, 1U << 2U, 2U));
    assert(kx3::dx_live_filter_matches("3cm", "FT8", false, 1U << 15U, 1U << 2U, 2U));
    assert(!kx3::dx_live_filter_matches("3cm", "FT8", false, 1U << 14U, 1U << 2U, 2U));

    shackcq_feature_context *features = shackcq_feature_context_create();
    assert(features != nullptr);
    assert(shackcq_feature_load_cty_text(features,
        "Japan: 25: 45: AS: 36.00: -138.00: -9.0: JA: JA;\n") == 1);
    assert(shackcq_feature_set_watchlist(features, "JA1XYZ VK9XY") == 1);
    assert(shackcq_feature_set_solar(features, 145.0F, 7.0F, 2.0F, 1720000000) == 1);
    assert(shackcq_feature_ingest_cluster_line(features,
        "DX de VK3ABC:  14074.0  JA1XYZ  FT8 -10 dB", 1720000000) == 1);
    assert(shackcq_feature_ingest_cluster_line(features,
        "DX de VK3ABC:  144300.0  JA2ABC  FT8 -08 dB", 1720000000) == 1);
    assert(shackcq_feature_ingest_cluster_line(features,
        "DX de VK3ABC:  70200.0  JA3FOUR  FT8 -12 dB", 1720000000) == 1);
    assert(shackcq_feature_ingest_cluster_line(features,
        "DX de VK3ABC:  432200.0  JA4UHF  FM simplex", 1720000000) == 1);
    assert(shackcq_feature_ingest_cluster_line(features,
        "DX de VK3ABC:  1296200.0  JA5L  SSB", 1720000000) == 1);
    assert(shackcq_feature_ingest_cluster_line(features,
        "DX de VK3ABC:  10489860.0  JA6SAT  FT4 QO-100", 1720000000) == 1);
    assert(shackcq_feature_ingest_cluster_line(features,
        "DX de VK3ABC:  100000.0  JA7BAD  FM", 1720000000) == 0);
    assert(shackcq_feature_ingest_cluster_line(features,
        "DX de VK3ABC:  222000.0  JA8BAD  FM", 1720000000) == 0);
    char dx_json[32768]{};
    assert(shackcq_feature_dx_snapshot_json(features, dx_json, sizeof(dx_json), 1720000001) > 0);
    std::string dx(dx_json);
    const auto opportunity_for = [](const std::string& json, const std::string& callsign) {
        const auto start = json.find("\"callsign\":\"" + callsign + "\"");
        assert(start != std::string::npos);
        const auto end = json.find('}', start);
        assert(end != std::string::npos);
        return json.substr(start, end - start);
    };
    const auto score_of = [](const std::string& row) {
        const auto start = row.find("\"score\":");
        assert(start != std::string::npos);
        return std::stoi(row.substr(start + 8));
    };
    assert(dx.find("\"callsign\":\"JA1XYZ\"") != std::string::npos);
    assert(dx.find("\"watchlisted\":true") != std::string::npos);
    assert(dx.find("\"bandTimeline\":[") != std::string::npos);
    assert(dx.find("\"worldGrid\":[") != std::string::npos);
    assert(dx.find("]],\"opportunities\":[") != std::string::npos);
    assert(dx.find("\"liveSpots\":[") != std::string::npos);
    assert(dx.find("\"workedLog\":{\"loaded\":false,\"complete\":false") != std::string::npos);
    const auto bands_start = dx.find("\"bands\":[");
    const auto timeline_start = dx.find("\"bandTimeline\":[");
    const auto regions_start = dx.find("\"regions\":[");
    assert(bands_start != std::string::npos && timeline_start != std::string::npos &&
           regions_start != std::string::npos);
    const std::string bands_json = dx.substr(bands_start, timeline_start - bands_start);
    std::size_t band_rows{};
    for (std::size_t at = 0; (at = bands_json.find("\"band\":", at)) != std::string::npos;
         at += 7U) ++band_rows;
    assert(band_rows == 16U);
    std::size_t ordered_at{};
    for (const char *band : {"160m", "80m", "60m", "40m", "30m", "20m", "17m", "15m",
                             "12m", "10m", "6m", "4m", "2m", "70cm", "23cm", "3cm"}) {
        ordered_at = bands_json.find(std::string("\"band\":\"") + band + '"', ordered_at);
        assert(ordered_at != std::string::npos);
        ++ordered_at;
    }
    const std::string timeline_json = dx.substr(timeline_start, regions_start - timeline_start);
    assert(static_cast<std::size_t>(std::count(timeline_json.begin(), timeline_json.end(), '[')) == 17U);
    for (const auto& expected : {
             std::pair{"JA3FOUR", "\"frequencyHz\":70200000"},
             std::pair{"JA2ABC", "\"frequencyHz\":144300000"},
             std::pair{"JA4UHF", "\"frequencyHz\":432200000"},
             std::pair{"JA5L", "\"frequencyHz\":1296200000"},
             std::pair{"JA6SAT", "\"frequencyHz\":10489860000"}}) {
        const auto row = opportunity_for(dx, expected.first);
        assert(row.find(expected.second) != std::string::npos);
    }
    assert(opportunity_for(dx, "JA3FOUR").find("\"band\":\"4m\"") != std::string::npos);
    assert(opportunity_for(dx, "JA4UHF").find("\"mode\":\"FM\"") != std::string::npos);
    assert(opportunity_for(dx, "JA6SAT").find("\"band\":\"3cm\"") != std::string::npos);
    assert(opportunity_for(dx, "JA6SAT").find("\"mode\":\"FT4\"") != std::string::npos);
    auto je_row = opportunity_for(dx, "JA2ABC");
    assert(je_row.find("\"workedIndexComplete\":false") != std::string::npos);
    assert(je_row.find("NEW ENTITY IN LOGBOOK") == std::string::npos);

    assert(shackcq_feature_begin_worked_sync(features) == 1);
    assert(shackcq_feature_end_worked_sync(features) == 1);
    assert(shackcq_feature_dx_snapshot_json(features, dx_json, sizeof(dx_json), 1720000001) > 0);
    dx = dx_json;
    assert(dx.find("\"workedLog\":{\"loaded\":true,\"complete\":true,\"cells\":0") != std::string::npos);
    je_row = opportunity_for(dx, "JA2ABC");
    assert(je_row.find("\"workedCountry\":false") != std::string::npos);
    assert(je_row.find("\"reason\":\"NEW ENTITY IN LOGBOOK\"") != std::string::npos);
    const int unworked_score = score_of(je_row);

    assert(shackcq_feature_begin_worked_sync(features) == 1);
    assert(shackcq_feature_add_worked_qso(features, "JA2ABC", "Japan", "2m", "FT8", "", 1719999900, 0) == 1);
    assert(shackcq_feature_end_worked_sync(features) == 1);
    assert(shackcq_feature_dx_snapshot_json(features, dx_json, sizeof(dx_json), 1720000001) > 0);
    dx = dx_json;
    je_row = opportunity_for(dx, "JA2ABC");
    for (const char *flag : {"\"workedCountry\":true", "\"workedCall\":true",
                             "\"workedBand\":true", "\"workedMode\":true",
                             "\"workedBandMode\":true", "\"recentDupe\":true"})
        assert(je_row.find(flag) != std::string::npos);
    assert(je_row.find("NEW ENTITY IN LOGBOOK") == std::string::npos);
    assert(je_row.find("SOLAR SUPPORT") == std::string::npos);
    assert(score_of(je_row) == unworked_score - 22);

    char worked_json[1024]{};
    assert(shackcq_feature_worked_json(features, worked_json, sizeof(worked_json),
        "JA2ABC", "Japan", "2m", "FT8", "", 1720000001) > 0);
    assert(std::string(worked_json).find("\"callWorked\":true") != std::string::npos);

    assert(shackcq_feature_begin_worked_sync(features) == 1);
    assert(shackcq_feature_end_worked_sync(features) == 1);
    assert(shackcq_feature_dx_snapshot_json(features, dx_json, sizeof(dx_json), 1720000001) > 0);
    je_row = opportunity_for(dx_json, "JA2ABC");
    assert(je_row.find("\"workedCountry\":false") != std::string::npos);
    assert(je_row.find("NEW ENTITY IN LOGBOOK") != std::string::npos);

    assert(shackcq_feature_begin_worked_sync(features) == 1);
    assert(shackcq_feature_add_worked_qso(features, "INVALID", "", "20m", "FT8", "",
                                     1719999999, 0) == 0);
    assert(shackcq_feature_end_worked_sync(features) == 1);
    assert(shackcq_feature_dx_snapshot_json(features, dx_json, sizeof(dx_json), 1720000001) > 0);
    dx = dx_json;
    assert(dx.find("\"complete\":false") != std::string::npos);
    assert(dx.find("\"rejected\":1") != std::string::npos);
    assert(dx.find("\"truncated\":0") != std::string::npos);
    je_row = opportunity_for(dx, "JA2ABC");
    assert(je_row.find("\"workedIndexComplete\":false") != std::string::npos);
    assert(je_row.find("NEW ENTITY IN LOGBOOK") == std::string::npos);

    char propagation[2048]{};
    assert(shackcq_feature_propagation_json(propagation, sizeof(propagation),
        "QF56OC", "PM95VR", "20m", 1720000000, 155.0F, 2.0F, 1720000000, 20, 12) > 0);
    assert(std::string(propagation).find("\"valid\":true") != std::string::npos);

    std::array<uint8_t, 4096> pcm{};
    assert(shackcq_panadapter_push_pcm(features, pcm.data(), pcm.size(), 2, 2, 16) == 1);
    std::array<uint8_t, 1024> bins{};
    assert(shackcq_panadapter_copy_bins(features, bins.data(), bins.size()) == bins.size());
    std::array<float, 1024> db_bins{};
    assert(shackcq_panadapter_copy_db_bins(features, db_bins.data(), db_bins.size()) == db_bins.size());
    assert(std::all_of(db_bins.begin(), db_bins.end(), [](float value) {
        return std::isfinite(value) && value >= -140.0F && value <= 6.0F;
    }));

    std::array<int16_t, 2048> iq_tone{};
    constexpr float tone_amplitude = 0.5F;
    constexpr std::size_t tone_bin = 96;
    for (std::size_t frame = 0; frame < 1024; ++frame) {
        const float phase = 2.0F * 3.14159265358979323846F *
                            static_cast<float>(tone_bin * frame) / 1024.0F;
        iq_tone[frame * 2] = static_cast<int16_t>(std::cos(phase) * tone_amplitude * 32767.0F);
        iq_tone[frame * 2 + 1] = static_cast<int16_t>(std::sin(phase) * tone_amplitude * 32767.0F);
    }
    for (int frame = 0; frame < 4; ++frame) {
        assert(shackcq_panadapter_push_pcm(features, reinterpret_cast<const uint8_t *>(iq_tone.data()),
                                     iq_tone.size() * sizeof(int16_t), 2, 2, 16) == 1);
    }
    shackcq_panadapter_copy_db_bins(features, db_bins.data(), db_bins.size());
    const auto peak = static_cast<std::size_t>(std::distance(db_bins.begin(),
        std::max_element(db_bins.begin(), db_bins.end())));
    assert(peak == 512 + tone_bin);
    assert(db_bins[512 + tone_bin] - db_bins[512 - tone_bin] > 45.0F);

    shackcq_panadapter_context *pan = shackcq_panadapter_context_create();
    assert(pan != nullptr);
    shackcq_panadapter_config pan_config{};
    pan_config.sample_rate = 96000; pan_config.fft_size = 4096; pan_config.overlap_percent = 50;
    pan_config.window = 3; pan_config.display_floor_db = -140.0F; pan_config.display_top_db = 0.0F;
    pan_config.attack = 1.0F; pan_config.release = 1.0F; pan_config.average_frames = 1;
    pan_config.i_trim = 1.0F; pan_config.q_trim = 1.0F; pan_config.zoom_decimation = 1;
    assert(shackcq_panadapter_configure(pan, &pan_config) == 1);
    for (const uint32_t size : {1024U, 2048U, 4096U, 8192U}) {
        pan_config.fft_size = size;
        assert(shackcq_panadapter_configure(pan, &pan_config) == 1);
    }
    pan_config.fft_size = 4096;
    assert(shackcq_panadapter_configure(pan, &pan_config) == 1);
    std::vector<int16_t> production_tone(4096U * 2U);
    constexpr std::size_t production_bin = 256U;
    for (std::size_t frame_index = 0; frame_index < 4096U; ++frame_index) {
        const float phase = 2.0F * 3.14159265358979323846F *
            static_cast<float>(production_bin * frame_index) / 4096.0F;
        production_tone[frame_index * 2U] = static_cast<int16_t>(std::cos(phase) * 0.5F * 32767.0F);
        production_tone[frame_index * 2U + 1U] = static_cast<int16_t>(std::sin(phase) * 0.5F * 32767.0F);
    }
    assert(shackcq_panadapter_push(pan, reinterpret_cast<const uint8_t *>(production_tone.data()),
        production_tone.size() * sizeof(int16_t), 2, 2, 16, 0) == 1);
    shackcq_panadapter_snapshot pan_snapshot{};
    std::vector<float> production_trace(4096), production_waterfall(4096), production_peak(4096);
    assert(shackcq_panadapter_copy_frame(pan, &pan_snapshot, production_trace.data(), production_waterfall.data(),
        production_peak.data(), production_trace.size()) == 4096);
    const auto production_peak_bin = static_cast<std::size_t>(std::distance(production_trace.begin(),
        std::max_element(production_trace.begin(), production_trace.end())));
    assert(production_peak_bin == 2048U + production_bin);
    assert(std::abs(production_trace[production_peak_bin] - (-6.02F)) < 0.35F);
    assert(production_trace[2048U + production_bin] - production_trace[2048U - production_bin] > 55.0F);
    assert(std::abs(pan_snapshot.enbw_bins - 1.0F) < 0.001F);
    assert(std::abs(pan_snapshot.rbw_hz - 23.4375F) < 0.01F);
    assert(pan_snapshot.fft_size == 4096 && pan_snapshot.hop_size == 2048 && pan_snapshot.sequence == 1);
    assert(pan_snapshot.valid_stereo == 1 && pan_snapshot.clipped_fraction == 0.0F);

    // Direct TCI-style interleaved float32 I/Q shares the exact DSP path with PCM.
    std::vector<float> float_tone(4096U * 2U);
    for (std::size_t frame_index = 0; frame_index < 4096U; ++frame_index) {
        const float phase = 2.0F * 3.14159265358979323846F *
            static_cast<float>(production_bin * frame_index) / 4096.0F;
        float_tone[frame_index * 2U] = std::cos(phase) * 0.5F;
        float_tone[frame_index * 2U + 1U] = std::sin(phase) * 0.5F;
    }
    assert(shackcq_panadapter_configure(pan, &pan_config) == 1);
    assert(shackcq_panadapter_push_float_iq(pan, float_tone.data(), float_tone.size(), 0) == 1);
    shackcq_panadapter_copy_frame(pan, &pan_snapshot, production_trace.data(), production_waterfall.data(),
        production_peak.data(), production_trace.size());
    const auto float_peak_bin = static_cast<std::size_t>(std::distance(production_trace.begin(),
        std::max_element(production_trace.begin(), production_trace.end())));
    assert(float_peak_bin == production_peak_bin);
    assert(std::abs(production_trace[float_peak_bin] - (-6.02F)) < 0.35F);

    pan_config.swap_iq = 1;
    assert(shackcq_panadapter_configure(pan, &pan_config) == 1);
    assert(shackcq_panadapter_push_float_iq(pan, float_tone.data(), float_tone.size(), 0) == 1);
    shackcq_panadapter_copy_trace(pan, production_trace.data(), production_trace.size());
    assert(static_cast<std::size_t>(std::distance(production_trace.begin(),
        std::max_element(production_trace.begin(), production_trace.end()))) == 2048U - production_bin);
    pan_config.swap_iq = 0;
    pan_config.conjugate = 1;
    assert(shackcq_panadapter_configure(pan, &pan_config) == 1);
    assert(shackcq_panadapter_push_float_iq(pan, float_tone.data(), float_tone.size(), 0) == 1);
    shackcq_panadapter_copy_trace(pan, production_trace.data(), production_trace.size());
    assert(static_cast<std::size_t>(std::distance(production_trace.begin(),
        std::max_element(production_trace.begin(), production_trace.end()))) == 2048U - production_bin);
    pan_config.conjugate = 0;

    std::vector<float> non_finite_tone = float_tone;
    non_finite_tone.insert(non_finite_tone.end(), {0.1F, 0.2F});
    non_finite_tone[20] = std::numeric_limits<float>::quiet_NaN();
    pan_config.fit_auto_contrast = 1;
    assert(shackcq_panadapter_configure(pan, &pan_config) == 1);
    assert(shackcq_panadapter_push_float_iq(pan, non_finite_tone.data(), non_finite_tone.size(), 1) == 1);
    assert(shackcq_panadapter_snapshot_copy(pan, &pan_snapshot) == 1);
    assert(pan_snapshot.non_finite_samples == 1 && pan_snapshot.discontinuities >= 2);
    assert(std::isfinite(pan_snapshot.fitted_floor_db) && std::isfinite(pan_snapshot.fitted_top_db));
    assert(pan_snapshot.fitted_top_db - pan_snapshot.fitted_floor_db >= 30.0F);
    pan_config.fit_auto_contrast = 0;

    // FIT remains finite and robust for silence, stable noise, carriers, a one-sample spike,
    // an abrupt band change, and gradual floor drift.
    pan_config.fft_size = 1024; pan_config.fit_auto_contrast = 1; pan_config.sample_rate = 48000;
    assert(shackcq_panadapter_configure(pan, &pan_config) == 1);
    std::vector<float> fit_frame(2048, 0.0F);
    auto push_fit = [&](const std::vector<float>& values) {
        assert(shackcq_panadapter_push_float_iq(pan, values.data(), values.size(), 0) == 1);
        assert(shackcq_panadapter_snapshot_copy(pan, &pan_snapshot) == 1);
        assert(std::isfinite(pan_snapshot.fitted_floor_db));
        assert(std::isfinite(pan_snapshot.fitted_top_db));
        assert(pan_snapshot.fitted_top_db - pan_snapshot.fitted_floor_db >= 30.0F);
    };
    push_fit(fit_frame);
    std::uint32_t noise_state = 0x12345678U;
    for (float &sample : fit_frame) {
        noise_state = noise_state * 1664525U + 1013904223U;
        sample = (static_cast<float>((noise_state >> 8U) & 0xffffU) / 32768.0F - 1.0F) * 0.01F;
    }
    for (int iteration = 0; iteration < 8; ++iteration) push_fit(fit_frame);
    const float stable_fit_top = pan_snapshot.fitted_top_db;
    auto spike_frame = fit_frame; spike_frame[200] = 1.0F; spike_frame[201] = -1.0F;
    push_fit(spike_frame);
    assert(std::abs(pan_snapshot.fitted_top_db - stable_fit_top) < 8.0F);
    for (int carrier = 1; carrier <= 6; ++carrier) for (int frame = 0; frame < 1024; ++frame) {
        const float phase = 2.0F * 3.14159265358979323846F * static_cast<float>(carrier * 37 * frame) / 1024.0F;
        fit_frame[2 * frame] += std::cos(phase) * 0.025F;
        fit_frame[2 * frame + 1] += std::sin(phase) * 0.025F;
    }
    push_fit(fit_frame);
    std::rotate(fit_frame.begin(), fit_frame.begin() + 246, fit_frame.end());
    push_fit(fit_frame);
    for (int drift = 1; drift <= 6; ++drift) {
        auto drift_frame = fit_frame;
        const float scale = 1.0F + static_cast<float>(drift) * 0.08F;
        for (float &sample : drift_frame) sample *= scale;
        push_fit(drift_frame);
    }
    pan_config.sample_rate = 192000;
    assert(shackcq_panadapter_configure(pan, &pan_config) == 1);

    // Float diagnostics preserve clipping, DC, and duplicate-channel truth.
    pan_config.sample_rate = 96000; pan_config.fit_auto_contrast = 0;
    assert(shackcq_panadapter_configure(pan, &pan_config) == 1);
    std::vector<float> duplicate(2048);
    for (int frame = 0; frame < 1024; ++frame) duplicate[2 * frame] = duplicate[2 * frame + 1] = frame == 0 ? 1.2F : 0.2F;
    assert(shackcq_panadapter_push_float_iq(pan, duplicate.data(), duplicate.size(), 0) == 1);
    assert(shackcq_panadapter_snapshot_copy(pan, &pan_snapshot) == 1);
    assert(pan_snapshot.clipped_fraction > 0.0F);
    assert(pan_snapshot.duplicate_correlation > 0.99F);
    assert(pan_snapshot.i_rms_db > -80.0F && pan_snapshot.q_rms_db > -80.0F);

    pan_config.fft_size = 4096; pan_config.sample_rate = 96000;

    // A fixed, explicit widely-linear profile removes a known image; no live-frame covariance is used.
    std::vector<int16_t> imbalanced_tone(4096U * 2U);
    for (std::size_t frame_index = 0; frame_index < 4096U; ++frame_index) {
        const float phase = 2.0F * 3.14159265358979323846F *
            static_cast<float>(production_bin * frame_index) / 4096.0F;
        const std::complex<float> wanted = std::polar(0.45F, phase);
        const std::complex<float> value = wanted + 0.10F * std::conj(wanted);
        imbalanced_tone[frame_index * 2U] = static_cast<int16_t>(value.real() * 32767.0F);
        imbalanced_tone[frame_index * 2U + 1U] = static_cast<int16_t>(value.imag() * 32767.0F);
    }
    assert(shackcq_panadapter_configure(pan, &pan_config) == 1);
    assert(shackcq_panadapter_push(pan, reinterpret_cast<const uint8_t *>(imbalanced_tone.data()),
        imbalanced_tone.size() * sizeof(int16_t), 2, 2, 16, 0) == 1);
    shackcq_panadapter_copy_trace(pan, production_trace.data(), production_trace.size());
    const float rejection_before = production_trace[2048U + production_bin] - production_trace[2048U - production_bin];
    assert(rejection_before > 18.0F && rejection_before < 22.0F);
    assert(shackcq_panadapter_configure(pan, &pan_config) == 1);
    assert(shackcq_panadapter_set_iq_correction(pan, 1.0F, 0.0F, -0.10F, 0.0F, 1) == 1);
    assert(shackcq_panadapter_push(pan, reinterpret_cast<const uint8_t *>(imbalanced_tone.data()),
        imbalanced_tone.size() * sizeof(int16_t), 2, 2, 16, 0) == 1);
    shackcq_panadapter_copy_trace(pan, production_trace.data(), production_trace.size());
    assert(production_trace[2048U + production_bin] - production_trace[2048U - production_bin] > 55.0F);

    // Translate + anti-alias FIR + decimate is a real zoom path, not display interpolation.
    pan_config.zoom_decimation = 4; pan_config.zoom_offset_hz = 12000.0F;
    assert(shackcq_panadapter_configure(pan, &pan_config) == 1);
    constexpr std::size_t zoom_input_frames = 18000U;
    std::vector<int16_t> zoom_tone(zoom_input_frames * 2U);
    for (std::size_t frame_index = 0; frame_index < zoom_input_frames; ++frame_index) {
        const float phase = 2.0F * 3.14159265358979323846F * 13500.0F *
            static_cast<float>(frame_index) / 96000.0F;
        zoom_tone[frame_index * 2U] = static_cast<int16_t>(std::cos(phase) * 0.4F * 32767.0F);
        zoom_tone[frame_index * 2U + 1U] = static_cast<int16_t>(std::sin(phase) * 0.4F * 32767.0F);
    }
    assert(shackcq_panadapter_push(pan, reinterpret_cast<const uint8_t *>(zoom_tone.data()),
        zoom_tone.size() * sizeof(int16_t), 2, 2, 16, 0) == 1);
    shackcq_panadapter_copy_frame(pan, &pan_snapshot, production_trace.data(), production_waterfall.data(),
        production_peak.data(), production_trace.size());
    const auto zoom_peak_bin = static_cast<std::size_t>(std::distance(production_trace.begin(),
        std::max_element(production_trace.begin(), production_trace.end())));
    assert(std::abs(static_cast<long>(zoom_peak_bin) - static_cast<long>(2048U + 256U)) <= 1L);
    assert(pan_snapshot.effective_sample_rate == 24000 && pan_snapshot.zoom_decimation == 4);
    assert(std::abs(pan_snapshot.rbw_hz - 5.859375F) < 0.01F);
    shackcq_panadapter_context_destroy(pan);

    char normalized_url[128]{};
    assert(shackcq_wavelog_normalize_url(normalized_url, sizeof(normalized_url), "htps://log.example/") > 0);
    assert(std::string(normalized_url) == "https://log.example");
    assert(shackcq_sync_action(200, 0, 0) == 0);
    assert(shackcq_sync_action(401, 0, 0) == 2);
    char wsjtx[256]{};
    const std::array<uint8_t, 4> invalid_wsjtx{};
    assert(shackcq_wsjtx_parse_json(wsjtx, sizeof(wsjtx), invalid_wsjtx.data(), invalid_wsjtx.size()) > 0);
    assert(std::string(wsjtx).find("\"valid\":false") != std::string::npos);
    shackcq_feature_context_destroy(features);

    // Pinned upstream verification vector: Vanguard 1 at epoch and T+360 minutes.
    const std::string vanguard_one =
        "1 00005U 58002B   00179.78495062  .00000023  00000-0  28098-4 0  4753";
    const std::string vanguard_two =
        "2 00005  34.2682 348.7242 1859667 331.7664  19.3264 10.82419157413667";
    const libsgp4::Tle verification_tle(vanguard_one, vanguard_two);
    const libsgp4::SGP4 verification_model(verification_tle);
    const auto epoch_position = verification_model.FindPosition(0.0).Position();
    assert(std::abs(epoch_position.x - 7022.46529267) < 1e-3);
    assert(std::abs(epoch_position.y + 1400.08296756) < 1e-3);
    assert(std::abs(epoch_position.z - 0.03995155) < 1e-3);
    const auto later_position = verification_model.FindPosition(360.0).Position();
    assert(std::abs(later_position.x + 7154.03120202) < 1e-3);
    assert(std::abs(later_position.y + 3783.17682504) < 1e-3);
    assert(std::abs(later_position.z + 3536.19412294) < 1e-3);

    // C ABI observer output, pass boundaries, no-pass interval, invalid data, and Doppler sign.
    char satellite_json[65536]{};
    constexpr int64_t vanguard_epoch = 962131819;
    assert(shackcq_satellite_propagate_json(satellite_json, sizeof(satellite_json), "TLE", "VANGUARD 1",
        vanguard_one.c_str(), vanguard_two.c_str(), vanguard_epoch, 0, 48.15, 17.11, 0.2) > 0);
    const std::string observer_result(satellite_json);
    assert(observer_result.find("\"ok\":true") != std::string::npos);
    assert(observer_result.find("\"azimuth_deg\"") != std::string::npos);
    assert(observer_result.find("\"elevation_deg\"") != std::string::npos);

    std::memset(satellite_json, 0, sizeof(satellite_json));
    assert(shackcq_satellite_passes_json(satellite_json, sizeof(satellite_json), "TLE", "VANGUARD 1",
        vanguard_one.c_str(), vanguard_two.c_str(), vanguard_epoch, vanguard_epoch + 86400, 0,
        48.15, 17.11, 0.2, 0.0, 0.0, 30, 24) > 0);
    const std::string pass_result(satellite_json);
    assert(pass_result.find("\"aos\"") != std::string::npos);
    assert(pass_result.find("\"tca\"") != std::string::npos);
    assert(pass_result.find("\"los\"") != std::string::npos);

    std::memset(satellite_json, 0, sizeof(satellite_json));
    assert(shackcq_satellite_passes_json(satellite_json, sizeof(satellite_json), "TLE", "VANGUARD 1",
        vanguard_one.c_str(), vanguard_two.c_str(), vanguard_epoch, vanguard_epoch + 3600, 0,
        48.15, 17.11, 0.2, 0.0, 90.0, 30, 24) > 0);
    assert(std::string(satellite_json).find("\"passes\":[]") != std::string::npos);

    std::memset(satellite_json, 0, sizeof(satellite_json));
    assert(shackcq_satellite_propagate_json(satellite_json, sizeof(satellite_json), "TLE", "INVALID",
        "1 invalid", "2 invalid", vanguard_epoch, 0, 0.0, 0.0, 0.0) > 0);
    assert(std::string(satellite_json).find("\"ok\":false") != std::string::npos);
    const std::string celestrak_csv =
        "OSCAR 7 (AO-7),1974-089B,2026-08-19T00:23:27.496608,12.53699154,.00123316,101.9919,244.9197,13.2706,15.2415,0,U,123456,999,36839,-.5025725E-5,-.47E-6,0";
    std::memset(satellite_json, 0, sizeof(satellite_json));
    assert(shackcq_satellite_inspect_json(satellite_json, sizeof(satellite_json), "CSV", "AO-7",
        celestrak_csv.c_str(), "") > 0);
    assert(std::string(satellite_json).find("\"norad_id\":123456") != std::string::npos);
    assert(std::string(satellite_json).find("\"element_epoch\":1787099007") != std::string::npos);
    std::memset(satellite_json, 0, sizeof(satellite_json));
    assert(shackcq_satellite_propagate_json(satellite_json, sizeof(satellite_json), "CSV", "AO-7",
        celestrak_csv.c_str(), "", 1787099007, 0, 48.15, 17.11, 0.2) > 0);
    assert(std::string(satellite_json).find("\"ok\":true") != std::string::npos);
    assert(shackcq_satellite_doppler_hz(145800000.0, 1.0) < 145800000.0);
    assert(shackcq_satellite_doppler_hz(145800000.0, -1.0) > 145800000.0);

    assert(std::string(shackcq_core_version()) == "0.1.0");
    shackcq_context_destroy(context);
    std::cout << "ShackCQ core tests passed\n";
}
