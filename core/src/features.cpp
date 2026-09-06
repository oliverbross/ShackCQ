#include "shackcq/core.h"

#include "kx3/adif.hpp"
#include "kx3/cty.hpp"
#include "kx3/dx_analysis.hpp"
#include "kx3/operator_intel.hpp"
#include "kx3/panadapter_dsp.hpp"
#include "kx3/spot.hpp"
#include "kx3/sync_queue.hpp"
#include "kx3/wsjtx_protocol.hpp"

#include <algorithm>
#include <cstring>
#include <iomanip>
#include <mutex>
#include <sstream>
#include <string>
#include <string_view>

namespace {
std::string json_escape(std::string_view value) {
    std::string output;
    output.reserve(value.size() + 8);
    for (const unsigned char byte : value) {
        switch (byte) {
            case '"': output += "\\\""; break;
            case '\\': output += "\\\\"; break;
            case '\b': output += "\\b"; break;
            case '\f': output += "\\f"; break;
            case '\n': output += "\\n"; break;
            case '\r': output += "\\r"; break;
            case '\t': output += "\\t"; break;
            default:
                if (byte < 0x20U) {
                    char escaped[7]{};
                    std::snprintf(escaped, sizeof(escaped), "\\u%04x", byte);
                    output += escaped;
                } else output.push_back(static_cast<char>(byte));
        }
    }
    return output;
}

int write_output(char *output, size_t output_size, const std::string& value) {
    if (output == nullptr || output_size == 0 || value.size() + 1 > output_size) return 0;
    std::memcpy(output, value.c_str(), value.size() + 1);
    return static_cast<int>(value.size());
}

std::string quoted(std::string_view value) { return "\"" + json_escape(value) + "\""; }
const char* boolean(bool value) { return value ? "true" : "false"; }

const char* light_name(kx3::intel::LightState value) {
    switch (value) {
        case kx3::intel::LightState::Day: return "day";
        case kx3::intel::LightState::Greyline: return "greyline";
        case kx3::intel::LightState::Night: return "night";
        default: return "unknown";
    }
}

const char* freshness_name(kx3::intel::SolarFreshness value) {
    switch (value) {
        case kx3::intel::SolarFreshness::Fresh: return "fresh";
        case kx3::intel::SolarFreshness::Stale: return "stale";
        default: return "missing";
    }
}

const char* confidence_name(kx3::intel::SampleConfidence value) {
    switch (value) {
        case kx3::intel::SampleConfidence::Low: return "low";
        case kx3::intel::SampleConfidence::Medium: return "medium";
        case kx3::intel::SampleConfidence::High: return "high";
        default: return "insufficient";
    }
}
}  // namespace

struct shackcq_feature_context {
    kx3::CtyResolver cty;
    kx3::DxInsightEngine dx;
    kx3::intel::WorkedIndex worked;
    kx3::PanadapterDsp panadapter;
    bool worked_loaded{};
    bool worked_synchronizing{};
    unsigned worked_records{};
    unsigned worked_accepted{};

    shackcq_feature_context() : worked(kx3::intel::kDefaultWorkedCells) {
        kx3::PanadapterConfig legacy{};
        legacy.sample_rate = 48000;
        legacy.fft_size = kx3::kPanFftSize;
        panadapter.configure(legacy);
    }
};

struct shackcq_panadapter_context {
    mutable std::mutex mutex;
    kx3::PanadapterDsp dsp;
};

namespace {
kx3::PanadapterConfig native_pan_config(const shackcq_panadapter_config& value) {
    kx3::PanadapterConfig config{};
    config.sample_rate = value.sample_rate;
    config.fft_size = value.fft_size;
    config.overlap_percent = value.overlap_percent;
    config.window = static_cast<kx3::PanWindow>(std::min(value.window, 4U));
    config.display_floor_db = value.display_floor_db;
    config.display_top_db = value.display_top_db;
    config.attack = value.attack;
    config.release = value.release;
    config.average_frames = value.average_frames;
    config.peak_hold = value.peak_hold != 0;
    config.peak_decay_db_per_second = value.peak_decay_db_per_second;
    config.generic_kx3_flatness = value.generic_kx3_flatness != 0;
    config.swap_iq = value.swap_iq != 0;
    config.invert_i = value.invert_i != 0;
    config.invert_q = value.invert_q != 0;
    config.conjugate = value.conjugate != 0;
    config.i_trim = value.i_trim;
    config.q_trim = value.q_trim;
    config.zoom_decimation = value.zoom_decimation;
    config.zoom_offset_hz = value.zoom_offset_hz;
    config.fit_auto_contrast = value.fit_auto_contrast != 0;
    return config;
}
}

shackcq_feature_context *shackcq_feature_context_create(void) {
    return new shackcq_feature_context{};
}

void shackcq_feature_context_destroy(shackcq_feature_context *context) { delete context; }

int shackcq_feature_load_cty_text(shackcq_feature_context *context, const char *cty_text) {
    return context != nullptr && cty_text != nullptr && context->cty.load_text(cty_text) ? 1 : 0;
}

int shackcq_feature_set_watchlist(shackcq_feature_context *context, const char *watchlist_text) {
    if (context == nullptr || watchlist_text == nullptr) return 0;
    context->dx.set_watchlist(kx3::parse_dx_watchlist(watchlist_text));
    return 1;
}

int shackcq_feature_set_solar(shackcq_feature_context *context, float solar_flux, float a_index,
                         float kp_index, int64_t observed_epoch) {
    if (context == nullptr || observed_epoch <= 0) return 0;
    context->dx.update_solar({solar_flux, a_index, kp_index, observed_epoch, true});
    return 1;
}

int shackcq_feature_ingest_cluster_line(shackcq_feature_context *context, const char *line,
                                   int64_t received_epoch) {
    if (context == nullptr || line == nullptr) return 0;
    const auto parsed = kx3::parse_cluster_spot(line, received_epoch);
    if (!parsed.has_value()) return 0;
    const auto entity = context->cty.resolve(parsed->callsign);
    kx3::DxSpot spot{};
    spot.frequency_hz = parsed->frequency_hz;
    spot.received_epoch = parsed->received_epoch;
    spot.callsign = parsed->callsign;
    spot.spotter = parsed->spotter;
    spot.comment = parsed->comment;
    spot.band = parsed->band;
    spot.mode = parsed->mode;
    spot.country = entity.country;
    spot.continent = entity.continent;
    spot.cq_zone = entity.cq_zone;
    spot.itu_zone = entity.itu_zone;
    spot.latitude = entity.latitude;
    spot.longitude = entity.longitude;
    return context->dx.ingest(std::move(spot)) ? 1 : 0;
}

int shackcq_feature_dx_snapshot_json(const shackcq_feature_context *context, char *output,
                                size_t output_size, int64_t now_epoch) {
    if (context == nullptr) return 0;
    const bool worked_complete = context->worked_loaded && context->worked.complete() &&
                                 context->worked.rejected_count() == 0U;
    const auto snapshot = context->dx.evaluate(now_epoch, [&](const kx3::DxSpot& spot) {
        const auto classified = context->worked.classify(spot.callsign, spot.country, spot.band,
                                                         spot.mode, "", now_epoch);
        kx3::DxWorkedState state{};
        state.entity_any = classified.entity_any;
        state.entity_band = classified.entity_band;
        state.entity_mode = classified.entity_mode;
        state.entity_band_mode = classified.entity_band_mode;
        state.call_any = classified.call_any;
        state.call_band = classified.call_band;
        state.call_mode = classified.call_mode;
        state.call_band_mode = classified.call_band_mode;
        state.recent_dupe = classified.recent_dupe_band_mode;
        state.index_loaded = context->worked_loaded;
        state.index_complete = worked_complete;
        return state;
    });
    std::ostringstream json;
    json << "{\"spots5m\":" << snapshot.spots_5m
         << ",\"spots60m\":" << snapshot.spots_60m
         << ",\"learnedSpots\":" << snapshot.learned_spots
         << ",\"duplicateSpots\":" << snapshot.duplicate_spots
         << ",\"watchlistHits\":" << snapshot.watchlist_hits
         << ",\"surgingBands\":" << snapshot.surging_bands
         << ",\"newestSpotEpoch\":" << snapshot.newest_spot_epoch
         << ",\"workedLog\":{\"loaded\":" << boolean(context->worked_loaded)
         << ",\"complete\":" << boolean(worked_complete)
         << ",\"cells\":" << context->worked.size()
         << ",\"records\":" << context->worked_records
         << ",\"accepted\":" << context->worked_accepted
         << ",\"rejected\":" << context->worked.rejected_count()
         << ",\"truncated\":" << context->worked.truncated_count() << '}'
         << ",\"solar\":{\"valid\":" << boolean(snapshot.solar.valid)
         << ",\"flux\":" << snapshot.solar.solar_flux
         << ",\"aIndex\":" << snapshot.solar.a_index
         << ",\"kpIndex\":" << snapshot.solar.kp_index << "},\"bands\":[";
    bool first = true;
    for (const auto& band : snapshot.bands) {
        if (band.band.empty()) continue;
        if (!first) json << ',';
        first = false;
        json << "{\"band\":" << quoted(band.band) << ",\"spots5m\":" << band.spots_5m
             << ",\"spots60m\":" << band.spots_60m << ",\"uniqueCalls\":" << band.unique_calls_60m
             << ",\"surgePercent\":" << band.surge_percent << ",\"surge\":" << boolean(band.surge) << '}';
    }
    json << "],\"bandTimeline\":[";
    for (std::size_t band = 0; band < snapshot.band_timeline.size(); ++band) {
        if (band != 0) json << ',';
        json << '[';
        for (std::size_t bucket = 0; bucket < snapshot.band_timeline[band].size(); ++bucket) {
            if (bucket != 0) json << ',';
            json << snapshot.band_timeline[band][bucket];
        }
        json << ']';
    }
    json << "],\"regions\":[";
    first = true;
    for (const auto& region : snapshot.regions) {
        if (region.region.empty()) continue;
        if (!first) json << ',';
        first = false;
        json << "{\"region\":" << quoted(region.region)
             << ",\"spots15m\":" << region.spots_15m
             << ",\"spots60m\":" << region.spots_60m
             << ",\"uniqueCalls\":" << region.unique_calls_60m
             << ",\"activityPercent\":" << region.activity_percent
             << ",\"anomaly\":" << boolean(region.anomaly) << '}';
    }
    json << "],\"worldGrid\":[";
    for (std::size_t row = 0; row < snapshot.world_grid.size(); ++row) {
        if (row != 0) json << ',';
        json << '[';
        for (std::size_t column = 0; column < snapshot.world_grid[row].size(); ++column) {
            if (column != 0) json << ',';
            json << snapshot.world_grid[row][column];
        }
        json << ']';
    }
    json << ']';
    auto write_opportunities = [&](const char *name, const auto& rows) {
        json << ",\"" << name << "\":[";
        bool row_first = true;
        for (const auto& row : rows) {
            if (!row_first) json << ',';
            row_first = false;
            json << "{\"callsign\":" << quoted(row.spot.callsign)
                 << ",\"spotter\":" << quoted(row.spot.spotter)
                 << ",\"frequencyHz\":" << row.spot.frequency_hz
                 << ",\"receivedEpoch\":" << row.spot.received_epoch
                 << ",\"band\":" << quoted(row.spot.band)
                 << ",\"mode\":" << quoted(row.spot.mode)
                 << ",\"country\":" << quoted(row.spot.country)
                 << ",\"continent\":" << quoted(row.spot.continent)
                 << ",\"cqZone\":" << row.spot.cq_zone
                 << ",\"ituZone\":" << row.spot.itu_zone
                 << ",\"latitude\":" << row.spot.latitude
                 << ",\"longitude\":" << row.spot.longitude
                 << ",\"comment\":" << quoted(row.spot.comment)
                 << ",\"score\":" << row.score << ",\"confidence\":" << row.confidence
                 << ",\"samples\":" << row.samples
                 << ",\"watchlisted\":" << boolean(row.watchlisted)
                 << ",\"workedCountry\":" << boolean(row.worked_country)
                 << ",\"workedCall\":" << boolean(row.worked_call)
                 << ",\"workedBand\":" << boolean(row.worked_band)
                 << ",\"workedMode\":" << boolean(row.worked_mode)
                  << ",\"workedBandMode\":" << boolean(row.worked_band_mode)
                  << ",\"recentDupe\":" << boolean(row.recent_dupe)
                  << ",\"workedIndexComplete\":" << boolean(row.worked_index_complete)
                  << ",\"distanceKm\":" << row.distance_km
                 << ",\"bearingDegrees\":" << row.bearing_degrees
                 << ",\"pathState\":" << quoted(row.path_state)
                 << ",\"reason\":" << quoted(row.reason) << '}';
        }
        json << ']';
    };
    write_opportunities("opportunities", snapshot.opportunities);
    write_opportunities("liveSpots", snapshot.live_spots);
    write_opportunities("watchActivity", snapshot.watch_activity);
    json << '}';
    return write_output(output, output_size, json.str());
}

int shackcq_feature_begin_worked_sync(shackcq_feature_context *context) {
    if (context == nullptr) return 0;
    context->worked.clear();
    context->worked_loaded = false;
    context->worked_synchronizing = true;
    context->worked_records = 0;
    context->worked_accepted = 0;
    return 1;
}

int shackcq_feature_add_worked_qso(shackcq_feature_context *context, const char *callsign,
                              const char *entity, const char *band, const char *mode,
                              const char *submode, int64_t epoch, int from_wavelog) {
    if (context == nullptr || callsign == nullptr) return 0;
    ++context->worked_records;
    kx3::intel::WorkedRecord record{};
    record.call = callsign;
    record.entity = entity == nullptr ? "" : entity;
    record.band = band == nullptr ? "" : band;
    record.mode = mode == nullptr ? "" : mode;
    record.submode = submode == nullptr ? "" : submode;
    record.epoch = epoch;
    record.source = from_wavelog ? kx3::intel::LogSource::Wavelog : kx3::intel::LogSource::Local;
    if (!context->worked.add(std::move(record))) return 0;
    ++context->worked_accepted;
    return 1;
}

int shackcq_feature_end_worked_sync(shackcq_feature_context *context) {
    if (context == nullptr || !context->worked_synchronizing) return 0;
    context->worked_synchronizing = false;
    context->worked_loaded = true;
    return 1;
}

int shackcq_feature_worked_json(const shackcq_feature_context *context, char *output,
                           size_t output_size, const char *callsign, const char *entity,
                           const char *band, const char *mode, const char *submode,
                           int64_t now_epoch) {
    if (context == nullptr || callsign == nullptr) return 0;
    const auto row = context->worked.classify(callsign, entity == nullptr ? "" : entity,
        band == nullptr ? "" : band, mode == nullptr ? "" : mode,
        submode == nullptr ? "" : submode, now_epoch);
    std::ostringstream json;
    json << "{\"callsign\":" << quoted(row.canonical_call)
         << ",\"entity\":" << quoted(row.canonical_entity)
         << ",\"band\":" << quoted(row.canonical_band)
         << ",\"mode\":" << quoted(row.canonical_mode)
         << ",\"callWorked\":" << boolean(row.call_any)
         << ",\"callBandMode\":" << boolean(row.call_band_mode)
         << ",\"entityWorked\":" << boolean(row.entity_any)
         << ",\"entityBandMode\":" << boolean(row.entity_band_mode)
         << ",\"recentDupe\":" << boolean(row.recent_dupe_band_mode)
         << ",\"foundLocal\":" << boolean(row.found_local)
         << ",\"foundWavelog\":" << boolean(row.found_wavelog)
         << ",\"matchingQsos\":" << row.matching_qsos
         << ",\"lastQsoEpoch\":" << row.last_qso_epoch
         << ",\"indexLoaded\":" << boolean(context->worked_loaded)
         << ",\"indexComplete\":" << boolean(context->worked_loaded && row.index_complete &&
                                                   context->worked.rejected_count() == 0U) << '}';
    return write_output(output, output_size, json.str());
}

int shackcq_feature_propagation_json(char *output, size_t output_size,
                                const char *station_grid, const char *target_grid,
                                const char *band, int64_t epoch, float solar_flux,
                                float kp_index, int64_t solar_epoch,
                                unsigned observations, unsigned favorable_observations) {
    if (station_grid == nullptr || target_grid == nullptr || band == nullptr) return 0;
    kx3::intel::PropagationContextInput input{};
    input.station_grid = station_grid;
    input.target_grid = target_grid;
    input.band = band;
    input.epoch = epoch;
    input.solar = {solar_epoch > 0, solar_flux, kp_index, solar_epoch};
    input.local_samples = {observations, favorable_observations};
    const auto row = kx3::intel::explain_propagation(input);
    std::ostringstream json;
    json << std::fixed << std::setprecision(2)
         << "{\"valid\":" << boolean(row.valid)
         << ",\"distanceKm\":" << row.distance_km
         << ",\"bearingDegrees\":" << row.initial_bearing_deg
         << ",\"stationLight\":" << quoted(light_name(row.station_light))
         << ",\"targetLight\":" << quoted(light_name(row.target_light))
         << ",\"solarFreshness\":" << quoted(freshness_name(row.solar_freshness))
         << ",\"sampleConfidence\":" << quoted(confidence_name(row.sample_confidence))
         << ",\"localSuccessPercent\":" << row.local_success_percent
         << ",\"contextScore\":" << row.context_score
         << ",\"observations\":" << row.observations
         << ",\"label\":" << quoted(row.label) << ",\"reasons\":[";
    for (size_t index = 0; index < row.reasons.size(); ++index) {
        if (index != 0) json << ',';
        json << quoted(row.reasons[index]);
    }
    json << "]}";
    return write_output(output, output_size, json.str());
}

int shackcq_panadapter_push_pcm(shackcq_feature_context *context, const uint8_t *bytes, size_t length,
                           unsigned channels, unsigned subframe_bytes, unsigned bits) {
    return context != nullptr && context->panadapter.push_pcm(bytes, length, channels, subframe_bytes, bits) ? 1 : 0;
}

size_t shackcq_panadapter_copy_bins(const shackcq_feature_context *context, uint8_t *output,
                               size_t output_size) {
    if (context == nullptr || output == nullptr) return 0;
    const auto& bins = context->panadapter.bins();
    const size_t count = std::min(output_size, bins.size());
    std::copy_n(bins.begin(), count, output);
    return count;
}

size_t shackcq_panadapter_copy_db_bins(const shackcq_feature_context *context, float *output,
                                  size_t output_count) {
    if (context == nullptr || output == nullptr) return 0;
    const auto& bins = context->panadapter.db_bins();
    const size_t count = std::min(output_count, bins.size());
    std::copy_n(bins.begin(), count, output);
    return count;
}

float shackcq_panadapter_peak_db(const shackcq_feature_context *context) {
    return context == nullptr ? -120.0F : context->panadapter.peak_db();
}
float shackcq_panadapter_i_rms_db(const shackcq_feature_context *context) {
    return context == nullptr ? -120.0F : context->panadapter.i_rms_db();
}
float shackcq_panadapter_q_rms_db(const shackcq_feature_context *context) {
    return context == nullptr ? -120.0F : context->panadapter.q_rms_db();
}
float shackcq_panadapter_iq_correlation(const shackcq_feature_context *context) {
    return context == nullptr ? 0.0F : context->panadapter.iq_correlation();
}

shackcq_panadapter_context *shackcq_panadapter_context_create(void) { return new shackcq_panadapter_context{}; }
void shackcq_panadapter_context_destroy(shackcq_panadapter_context *context) { delete context; }

int shackcq_panadapter_configure(shackcq_panadapter_context *context, const shackcq_panadapter_config *config) {
    if (context == nullptr || config == nullptr) return 0;
    std::lock_guard<std::mutex> lock(context->mutex);
    return context->dsp.configure(native_pan_config(*config)) ? 1 : 0;
}

int shackcq_panadapter_push(shackcq_panadapter_context *context, const uint8_t *bytes, size_t length,
                       unsigned channels, unsigned subframe_bytes, unsigned bits,
                       int discontinuity) {
    if (context == nullptr) return 0;
    std::lock_guard<std::mutex> lock(context->mutex);
    return context->dsp.push_pcm(bytes, length, channels, subframe_bytes, bits,
                                 discontinuity != 0) ? 1 : 0;
}

int shackcq_panadapter_push_float_iq(shackcq_panadapter_context *context, const float *interleaved_iq,
                                size_t value_count, int discontinuity) {
    if (context == nullptr) return 0;
    std::lock_guard<std::mutex> lock(context->mutex);
    return context->dsp.push_iq_f32(interleaved_iq, value_count, discontinuity != 0) ? 1 : 0;
}

namespace {
size_t copy_pan_values(const std::vector<float>& values, float *output, size_t output_count) {
    if (output == nullptr) return 0;
    const size_t count = std::min(output_count, values.size());
    std::copy_n(values.begin(), count, output);
    return count;
}
}

size_t shackcq_panadapter_copy_trace(const shackcq_panadapter_context *context, float *output,
                                size_t output_count) {
    if (context == nullptr) return 0;
    std::lock_guard<std::mutex> lock(context->mutex);
    return copy_pan_values(context->dsp.db_bins(), output, output_count);
}
size_t shackcq_panadapter_copy_waterfall(const shackcq_panadapter_context *context, float *output,
                                    size_t output_count) {
    if (context == nullptr) return 0;
    std::lock_guard<std::mutex> lock(context->mutex);
    return copy_pan_values(context->dsp.waterfall_db(), output, output_count);
}
size_t shackcq_panadapter_copy_peak_hold(const shackcq_panadapter_context *context, float *output,
                                    size_t output_count) {
    if (context == nullptr) return 0;
    std::lock_guard<std::mutex> lock(context->mutex);
    return copy_pan_values(context->dsp.peak_hold_db(), output, output_count);
}

int shackcq_panadapter_snapshot_copy(const shackcq_panadapter_context *context,
                                shackcq_panadapter_snapshot *output) {
    if (context == nullptr || output == nullptr) return 0;
    std::lock_guard<std::mutex> lock(context->mutex);
    const auto& source = context->dsp.snapshot();
    output->sequence = source.sequence; output->input_frames = source.input_frames;
    output->transforms = source.transforms; output->discontinuities = source.discontinuities;
    output->non_finite_samples = source.non_finite_samples;
    output->sample_rate = source.sample_rate; output->effective_sample_rate = source.effective_sample_rate;
    output->fft_size = static_cast<uint32_t>(source.fft_size); output->hop_size = static_cast<uint32_t>(source.hop_size);
    output->zoom_decimation = source.zoom_decimation; output->zoom_offset_hz = source.zoom_offset_hz;
    output->enbw_bins = source.enbw_bins; output->rbw_hz = source.rbw_hz;
    output->peak_db = source.peak_db; output->floor_db = source.floor_db;
    output->raw_floor_db = source.raw_floor_db; output->stabilized_floor_db = source.stabilized_floor_db;
    output->fitted_floor_db = source.fitted_floor_db; output->fitted_top_db = source.fitted_top_db;
    output->valid_bin_fraction = source.valid_bin_fraction; output->valid_bin_count = source.valid_bin_count;
    output->i_rms_db = source.i_rms_db; output->q_rms_db = source.q_rms_db;
    output->iq_correlation = source.iq_correlation; output->clipped_fraction = source.clipped_fraction;
    output->duplicate_correlation = source.duplicate_correlation;
    output->valid_stereo = source.valid_stereo ? 1 : 0;
    return 1;
}

int shackcq_panadapter_copy_frame(const shackcq_panadapter_context *context,
                             shackcq_panadapter_snapshot *output,
                             float *trace, float *waterfall, float *peak_hold,
                             size_t output_count) {
    if (context == nullptr || output == nullptr || trace == nullptr ||
        waterfall == nullptr || peak_hold == nullptr) return 0;
    std::lock_guard<std::mutex> lock(context->mutex);
    const auto& source = context->dsp.snapshot();
    const size_t count = std::min(output_count, context->dsp.db_bins().size());
    if (count == 0U) return 0;
    output->sequence = source.sequence; output->input_frames = source.input_frames;
    output->transforms = source.transforms; output->discontinuities = source.discontinuities;
    output->non_finite_samples = source.non_finite_samples;
    output->sample_rate = source.sample_rate; output->effective_sample_rate = source.effective_sample_rate;
    output->fft_size = static_cast<uint32_t>(source.fft_size); output->hop_size = static_cast<uint32_t>(source.hop_size);
    output->zoom_decimation = source.zoom_decimation; output->zoom_offset_hz = source.zoom_offset_hz;
    output->enbw_bins = source.enbw_bins; output->rbw_hz = source.rbw_hz;
    output->peak_db = source.peak_db; output->floor_db = source.floor_db;
    output->raw_floor_db = source.raw_floor_db; output->stabilized_floor_db = source.stabilized_floor_db;
    output->fitted_floor_db = source.fitted_floor_db; output->fitted_top_db = source.fitted_top_db;
    output->valid_bin_fraction = source.valid_bin_fraction; output->valid_bin_count = source.valid_bin_count;
    output->i_rms_db = source.i_rms_db; output->q_rms_db = source.q_rms_db;
    output->iq_correlation = source.iq_correlation; output->clipped_fraction = source.clipped_fraction;
    output->duplicate_correlation = source.duplicate_correlation; output->valid_stereo = source.valid_stereo ? 1 : 0;
    std::copy_n(context->dsp.db_bins().begin(), count, trace);
    std::copy_n(context->dsp.waterfall_db().begin(), count, waterfall);
    std::copy_n(context->dsp.peak_hold_db().begin(), count, peak_hold);
    return static_cast<int>(count);
}

int shackcq_panadapter_set_iq_correction(shackcq_panadapter_context *context,
                                    float a_real, float a_imag, float b_real, float b_imag,
                                    int enabled) {
    if (context == nullptr) return 0;
    std::lock_guard<std::mutex> lock(context->mutex);
    context->dsp.set_iq_correction({a_real, a_imag}, {b_real, b_imag}, enabled != 0);
    return 1;
}

void shackcq_panadapter_reset_peak_hold(shackcq_panadapter_context *context) {
    if (context == nullptr) return;
    std::lock_guard<std::mutex> lock(context->mutex);
    context->dsp.reset_peak_hold();
}

int shackcq_sync_action(int status_code, int network_error, int response_ambiguous) {
    return static_cast<int>(kx3::classify_http_result(status_code, network_error != 0,
                                                      response_ambiguous != 0));
}

uint32_t shackcq_sync_retry_delay(uint32_t attempt, uint32_t jitter_seed,
                             uint32_t retry_after, int has_retry_after) {
    return kx3::retry_delay_seconds(attempt, jitter_seed,
        has_retry_after ? std::optional<uint32_t>(retry_after) : std::nullopt);
}

int shackcq_wavelog_normalize_url(char *output, size_t output_size, const char *url) {
    return url == nullptr ? 0 : write_output(output, output_size, kx3::normalize_wavelog_url(url));
}

int shackcq_wavelog_payload(char *output, size_t output_size, const char *api_key,
                       const char *station_profile_id, const char *adif) {
    if (api_key == nullptr || station_profile_id == nullptr || adif == nullptr) return 0;
    return write_output(output, output_size, kx3::wavelog_payload(api_key, station_profile_id, adif));
}

int shackcq_wsjtx_parse_json(char *output, size_t output_size,
                        const uint8_t *datagram, size_t datagram_size) {
    kx3::wsjtx::ParseError error{};
    const auto message = kx3::wsjtx::parse_datagram(datagram, datagram_size, &error);
    if (!message.has_value()) {
        return write_output(output, output_size,
            std::string("{\"valid\":false,\"error\":") + quoted(kx3::wsjtx::parse_error_text(error)) + '}');
    }
    std::ostringstream json;
    json << "{\"valid\":true,\"stationId\":" << quoted(message->header.id);
    if (const auto* status = std::get_if<kx3::wsjtx::Status>(&message->payload)) {
        json << ",\"type\":\"status\",\"frequencyHz\":" << status->dial_frequency_hz
             << ",\"mode\":" << quoted(status->mode) << ",\"dxCall\":" << quoted(status->dx_call)
             << ",\"transmitting\":" << boolean(status->transmitting)
             << ",\"decoding\":" << boolean(status->decoding);
    } else if (const auto* decode = std::get_if<kx3::wsjtx::Decode>(&message->payload)) {
        json << ",\"type\":\"decode\",\"snrDb\":" << decode->snr_db
             << ",\"deltaFrequencyHz\":" << decode->delta_frequency_hz
             << ",\"mode\":" << quoted(decode->mode) << ",\"message\":" << quoted(decode->message)
             << ",\"lowConfidence\":" << boolean(decode->low_confidence);
    } else if (const auto* logged = std::get_if<kx3::wsjtx::LoggedAdif>(&message->payload)) {
        json << ",\"type\":\"loggedAdif\",\"callsign\":" << quoted(logged->call)
             << ",\"band\":" << quoted(logged->band) << ",\"mode\":" << quoted(logged->mode)
             << ",\"submode\":" << quoted(logged->submode) << ",\"adif\":" << quoted(logged->raw);
    }
    json << '}';
    return write_output(output, output_size, json.str());
}
