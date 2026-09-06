// SPDX-License-Identifier: GPL-3.0-only
#pragma once
#include <stddef.h>
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif
typedef struct FlexContext shackcq_flex_context;
typedef struct DigiContext shackcq_digi_context;
shackcq_flex_context *shackcq_flex_context_create(void);
void shackcq_flex_context_destroy(shackcq_flex_context *context);
int32_t shackcq_flex_context_feed(shackcq_flex_context *context, const uint8_t *bytes, size_t count);
int32_t shackcq_flex_state_json(const shackcq_flex_context *context, char *output, size_t capacity);
int32_t shackcq_flex_client_identity(const char *program, char *output, size_t capacity);
int32_t shackcq_flex_subscriptions(char *output, size_t capacity);
int32_t shackcq_flex_keepalive(char *output, size_t capacity);
int32_t shackcq_flex_frequency(uint32_t slice, uint64_t hz, char *output, size_t capacity);
int32_t shackcq_flex_mode(uint32_t slice, const char *mode, char *output, size_t capacity);
int32_t shackcq_flex_filter(const char *letter, int32_t low, int32_t high, char *output, size_t capacity);
int32_t shackcq_flex_parse_discovery(const uint8_t *bytes, size_t count, char *output, size_t capacity);
shackcq_digi_context *shackcq_digi_context_create(uint32_t sample_rate, float cw_pitch_hz, bool rtty_reverse, float rtty_centre_hz);
void shackcq_digi_context_destroy(shackcq_digi_context *context);
int32_t shackcq_digi_feed_cw(shackcq_digi_context *context, const float *samples, size_t count, char *output, size_t capacity);
int32_t shackcq_digi_feed_rtty(shackcq_digi_context *context, const float *samples, size_t count, char *output, size_t capacity);
int32_t shackcq_digi_feed_sstv(shackcq_digi_context *context, const float *samples, size_t count, char *output, size_t capacity);
int32_t shackcq_digi_decode_slot(int32_t mode, const float *samples, size_t count, uint32_t sample_rate, char *output, size_t capacity);
int32_t shackcq_digi_spectrum(const float *samples, size_t count, uint32_t sample_rate, float low_hz, float high_hz, size_t bins, int32_t window, float *output, size_t capacity);
int32_t shackcq_digi_encode_slot(int32_t mode, const char *text, float base_hz, float *output, size_t capacity);
int32_t shackcq_digi_decode_psk31(const float *samples, size_t count, float carrier_hz, char *output, size_t capacity);
int32_t shackcq_digi_encode_psk31(const char *text, float carrier_hz, float *output, size_t capacity);
int32_t shackcq_digi_copy_sstv_image(const shackcq_digi_context *context, uint8_t *output, size_t capacity);
int32_t shackcq_digi_encode_cw(const char *text, uint32_t wpm, float pitch_hz, uint32_t sample_rate, float *output, size_t capacity);
int32_t shackcq_digi_encode_rtty(const char *text, uint32_t sample_rate, bool reverse, float *output, size_t capacity);
int32_t shackcq_digi_encode_sstv(int32_t mode, const uint8_t *rgb, uint32_t width, uint32_t height, uint32_t sample_rate, float *output, size_t capacity);
#ifdef __cplusplus
}
#endif
