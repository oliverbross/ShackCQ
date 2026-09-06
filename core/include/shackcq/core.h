#ifndef SHACKCQ_CORE_H
#define SHACKCQ_CORE_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct shackcq_context shackcq_context;
typedef struct shackcq_feature_context shackcq_feature_context;
typedef struct shackcq_panadapter_context shackcq_panadapter_context;

typedef enum shackcq_command_class {
    SHACKCQ_COMMAND_UNKNOWN = 0,
    SHACKCQ_COMMAND_READ_ONLY = 1,
    SHACKCQ_COMMAND_MUTATION = 2,
    SHACKCQ_COMMAND_TRANSMIT = 3
} shackcq_command_class;

typedef struct shackcq_radio_state {
    char identity[24];
    char model[16];
    char mode[12];
    uint64_t vfo_a_hz;
    uint64_t vfo_b_hz;
    int connected;
    int transmitting;
    int meter;
    int swr_tenths;
    int rf_output_tenths;
    int af_gain;
    int rf_gain;
    int bandwidth_hz;
    int power_w;
    int preamp;
    int attenuator;
    int rit;
    int xit;
    int rx_vfo;
    int tx_vfo;
    int split;
    int agc_mode;
    int cwt;
    int monitor_level;
    int mic_gain;
    int keyer_speed;
    int if_shift_hz;
    uint64_t revision;
    int rit_xit_offset_hz;
    uint64_t effective_rx_hz;
    uint64_t effective_tx_hz;
    int data_submode;
    uint64_t updated_monotonic_ms;
} shackcq_radio_state;

typedef struct shackcq_panadapter_config {
    uint32_t sample_rate;
    uint32_t fft_size;
    uint32_t overlap_percent;
    uint32_t window;
    float display_floor_db;
    float display_top_db;
    float attack;
    float release;
    uint32_t average_frames;
    int peak_hold;
    float peak_decay_db_per_second;
    int generic_kx3_flatness;
    int swap_iq;
    int invert_i;
    int invert_q;
    int conjugate;
    float i_trim;
    float q_trim;
    uint32_t zoom_decimation;
    float zoom_offset_hz;
    int fit_auto_contrast;
} shackcq_panadapter_config;

typedef struct shackcq_panadapter_snapshot {
    uint64_t sequence;
    uint64_t input_frames;
    uint64_t transforms;
    uint64_t discontinuities;
    uint32_t sample_rate;
    uint32_t effective_sample_rate;
    uint32_t fft_size;
    uint32_t hop_size;
    uint32_t zoom_decimation;
    float zoom_offset_hz;
    float enbw_bins;
    float rbw_hz;
    float peak_db;
    float floor_db;
    float raw_floor_db;
    float stabilized_floor_db;
    float valid_bin_fraction;
    uint32_t valid_bin_count;
    float i_rms_db;
    float q_rms_db;
    float iq_correlation;
    float clipped_fraction;
    float duplicate_correlation;
    int valid_stereo;
    uint64_t non_finite_samples;
    float fitted_floor_db;
    float fitted_top_db;
} shackcq_panadapter_snapshot;

shackcq_context *shackcq_context_create(void);
void shackcq_context_destroy(shackcq_context *context);
void shackcq_context_reset(shackcq_context *context);
int shackcq_context_feed(shackcq_context *context, const char *bytes, size_t length);
shackcq_radio_state shackcq_context_state(const shackcq_context *context);

shackcq_command_class shackcq_classify_command(const char *command);
size_t shackcq_startup_command_count(void);
int shackcq_qso_identity(char *output, size_t output_size, const char *callsign,
                    const char *utc_iso8601, uint64_t frequency_hz, const char *mode);
int shackcq_adif_serialize(char *output, size_t output_size, const char *identity,
                      const char *callsign, const char *date_yyyymmdd,
                      const char *time_hhmmss, uint64_t frequency_hz,
                      const char *mode, const char *rst_sent, const char *rst_received);

shackcq_feature_context *shackcq_feature_context_create(void);
void shackcq_feature_context_destroy(shackcq_feature_context *context);
int shackcq_feature_load_cty_text(shackcq_feature_context *context, const char *cty_text);
int shackcq_feature_set_watchlist(shackcq_feature_context *context, const char *watchlist_text);
int shackcq_feature_set_solar(shackcq_feature_context *context, float solar_flux, float a_index,
                         float kp_index, int64_t observed_epoch);
int shackcq_feature_ingest_cluster_line(shackcq_feature_context *context, const char *line,
                                   int64_t received_epoch);
int shackcq_feature_dx_snapshot_json(const shackcq_feature_context *context, char *output,
                                size_t output_size, int64_t now_epoch);
int shackcq_feature_begin_worked_sync(shackcq_feature_context *context);
int shackcq_feature_add_worked_qso(shackcq_feature_context *context, const char *callsign,
                              const char *entity, const char *band, const char *mode,
                              const char *submode, int64_t epoch, int from_wavelog);
int shackcq_feature_end_worked_sync(shackcq_feature_context *context);
int shackcq_feature_worked_json(const shackcq_feature_context *context, char *output,
                           size_t output_size, const char *callsign, const char *entity,
                           const char *band, const char *mode, const char *submode,
                           int64_t now_epoch);
int shackcq_feature_propagation_json(char *output, size_t output_size,
                                const char *station_grid, const char *target_grid,
                                const char *band, int64_t epoch, float solar_flux,
                                float kp_index, int64_t solar_epoch,
                                unsigned observations, unsigned favorable_observations);

int shackcq_panadapter_push_pcm(shackcq_feature_context *context, const uint8_t *bytes, size_t length,
                           unsigned channels, unsigned subframe_bytes, unsigned bits);
size_t shackcq_panadapter_copy_bins(const shackcq_feature_context *context, uint8_t *output,
                               size_t output_size);
size_t shackcq_panadapter_copy_db_bins(const shackcq_feature_context *context, float *output,
                                  size_t output_count);
float shackcq_panadapter_peak_db(const shackcq_feature_context *context);
float shackcq_panadapter_i_rms_db(const shackcq_feature_context *context);
float shackcq_panadapter_q_rms_db(const shackcq_feature_context *context);
float shackcq_panadapter_iq_correlation(const shackcq_feature_context *context);

shackcq_panadapter_context *shackcq_panadapter_context_create(void);
void shackcq_panadapter_context_destroy(shackcq_panadapter_context *context);
int shackcq_panadapter_configure(shackcq_panadapter_context *context, const shackcq_panadapter_config *config);
int shackcq_panadapter_push(shackcq_panadapter_context *context, const uint8_t *bytes, size_t length,
                       unsigned channels, unsigned subframe_bytes, unsigned bits,
                       int discontinuity);
int shackcq_panadapter_push_float_iq(shackcq_panadapter_context *context, const float *interleaved_iq,
                                size_t value_count, int discontinuity);
size_t shackcq_panadapter_copy_trace(const shackcq_panadapter_context *context, float *output,
                                size_t output_count);
size_t shackcq_panadapter_copy_waterfall(const shackcq_panadapter_context *context, float *output,
                                    size_t output_count);
size_t shackcq_panadapter_copy_peak_hold(const shackcq_panadapter_context *context, float *output,
                                    size_t output_count);
int shackcq_panadapter_snapshot_copy(const shackcq_panadapter_context *context,
                                shackcq_panadapter_snapshot *output);
int shackcq_panadapter_copy_frame(const shackcq_panadapter_context *context,
                             shackcq_panadapter_snapshot *snapshot,
                             float *trace, float *waterfall, float *peak_hold,
                             size_t output_count);
int shackcq_panadapter_set_iq_correction(shackcq_panadapter_context *context,
                                    float a_real, float a_imag, float b_real, float b_imag,
                                    int enabled);
void shackcq_panadapter_reset_peak_hold(shackcq_panadapter_context *context);

int shackcq_sync_action(int status_code, int network_error, int response_ambiguous);
uint32_t shackcq_sync_retry_delay(uint32_t attempt, uint32_t jitter_seed,
                             uint32_t retry_after, int has_retry_after);
int shackcq_wavelog_normalize_url(char *output, size_t output_size, const char *url);
int shackcq_wavelog_payload(char *output, size_t output_size, const char *api_key,
                       const char *station_profile_id, const char *adif);
int shackcq_wsjtx_parse_json(char *output, size_t output_size,
                        const uint8_t *datagram, size_t datagram_size);

const char *shackcq_core_version(void);

#ifdef __cplusplus
}
#endif

#endif
