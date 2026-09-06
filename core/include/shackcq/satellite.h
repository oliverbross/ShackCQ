#ifndef SHACKCQ_SATELLITE_H
#define SHACKCQ_SATELLITE_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Stable versioned JSON is returned. format is "TLE" or "CSV" (CelesTrak OMM CSV row). */
int shackcq_satellite_inspect_json(char *output, size_t output_size,
                              const char *format, const char *name,
                              const char *element_one, const char *element_two);

int shackcq_satellite_propagate_json(char *output, size_t output_size,
                                const char *format, const char *name,
                                const char *element_one, const char *element_two,
                                int64_t epoch_utc, int64_t max_element_age_seconds,
                                double observer_latitude_deg, double observer_longitude_deg,
                                double observer_altitude_km);

int shackcq_satellite_passes_json(char *output, size_t output_size,
                             const char *format, const char *name,
                             const char *element_one, const char *element_two,
                             int64_t start_utc, int64_t end_utc,
                             int64_t max_element_age_seconds,
                             double observer_latitude_deg, double observer_longitude_deg,
                             double observer_altitude_km, double horizon_deg,
                             double minimum_peak_deg, int coarse_step_seconds,
                             int maximum_passes);

/* kind: 0 = ground track, 1 = observer-relative sky track. */
int shackcq_satellite_samples_json(char *output, size_t output_size,
                              const char *format, const char *name,
                              const char *element_one, const char *element_two,
                              int64_t start_utc, int64_t end_utc,
                              int64_t max_element_age_seconds,
                              double observer_latitude_deg, double observer_longitude_deg,
                              double observer_altitude_km, int step_seconds,
                              int maximum_samples, int kind);

/* Positive range rate means receding and therefore produces a lower received frequency. */
double shackcq_satellite_doppler_hz(double nominal_frequency_hz, double range_rate_km_s);

#ifdef __cplusplus
}
#endif

#endif
