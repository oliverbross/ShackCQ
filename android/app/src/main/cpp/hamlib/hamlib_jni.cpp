/*
 * ShackCQ Hamlib Android bridge
 * SPDX-License-Identifier: GPL-3.0-only
 */
#include <jni.h>

#include <algorithm>
#include <cctype>
#include <cerrno>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <fcntl.h>
#include <mutex>
#include <poll.h>
#include <sstream>
#include <string>
#include <sys/socket.h>
#include <unistd.h>
#include <vector>

#include "hamlib/rig.h"
#include "hamlib/riglist.h"
#include "hamlib/rotator.h"
#include "hamlib/rotlist.h"
#include "hamlib/port.h"

namespace {
constexpr const char *kSourceDigest =
    "ae1fcf2dbc80ea0786ea8f047b09399c3f7737d1930442f61a031708ed33e88f";
constexpr size_t kMaximumModels = 2048;
constexpr size_t kMaximumText = 256;
constexpr size_t kMaximumRanges = 128;
constexpr size_t kMaximumCapabilities = 64;
constexpr int kMaximumBridgeTransfer = 65536;

struct Session {
    std::mutex mutex;
    RIG *rig = nullptr;
    int bridge_hamlib = -1;
    int bridge_application = -1;
    bool open = false;
    bool read_only = true;
    bool cancelled = false;
};

struct RotatorSession {
    std::mutex mutex;
    ROT *rotator = nullptr;
    int bridge_hamlib = -1;
    int bridge_application = -1;
    bool open = false;
    bool read_only = true;
    bool cancelled = false;
};

Session *session(jlong handle) {
    return reinterpret_cast<Session *>(static_cast<intptr_t>(handle));
}

RotatorSession *rotator_session(jlong handle) {
    return reinterpret_cast<RotatorSession *>(static_cast<intptr_t>(handle));
}

void close_rotator_bridge(RotatorSession *value) {
    if (value->bridge_hamlib >= 0) close(value->bridge_hamlib);
    if (value->bridge_application >= 0) close(value->bridge_application);
    value->bridge_hamlib = -1;
    value->bridge_application = -1;
}

std::string text(JNIEnv *env, jstring value) {
    if (!value) return {};
    const char *chars = env->GetStringUTFChars(value, nullptr);
    std::string result(chars ? chars : "");
    if (chars) env->ReleaseStringUTFChars(value, chars);
    if (result.size() > kMaximumText) result.resize(kMaximumText);
    return result;
}

std::string clean(const char *value) {
    std::string result(value ? value : "");
    if (result.size() > kMaximumText) result.resize(kMaximumText);
    for (char &character : result) {
        const auto byte = static_cast<unsigned char>(character);
        if (byte < 0x20 || byte == 0x7f) character = ' ';
    }
    return result;
}

std::string quoted(const char *value) {
    const std::string source = clean(value);
    std::string result = "\"";
    result.reserve(source.size() + 2);
    for (const unsigned char character : source) {
        switch (character) {
        case '\\': result += "\\\\"; break;
        case '"': result += "\\\""; break;
        case '\n': result += "\\n"; break;
        case '\r': result += "\\r"; break;
        case '\t': result += "\\t"; break;
        default: result += static_cast<char>(character); break;
        }
    }
    result += '"';
    return result;
}

const char *port_name(rig_port_t port) {
    switch (port) {
    case RIG_PORT_SERIAL: return "SERIAL";
    case RIG_PORT_NETWORK: return "NETWORK";
    case RIG_PORT_UDP_NETWORK: return "UDP_NETWORK";
    case RIG_PORT_USB: return "USB";
    case RIG_PORT_DEVICE: return "DEVICE";
    case RIG_PORT_NONE: return "NONE";
    default: return "OTHER";
    }
}

void append_names(std::ostringstream &out, setting_t values,
                  const char *(*name)(setting_t)) {
    out << '[';
    size_t emitted = 0;
    for (int index = 0; index < RIG_SETTING_MAX && emitted < kMaximumCapabilities; ++index) {
        const setting_t value = rig_idx2setting(index);
        if (value && (values & value)) {
            const char *label = name(value);
            if (label && label[0]) {
                if (emitted++) out << ',';
                out << quoted(label);
            }
        }
    }
    out << ']';
}

void append_modes(std::ostringstream &out, rmode_t modes) {
    out << '[';
    size_t emitted = 0;
    for (unsigned bit = 0; bit < 64 && emitted < kMaximumCapabilities; ++bit) {
        const rmode_t mode = static_cast<rmode_t>(UINT64_C(1) << bit);
        if (modes & mode) {
            const char *label = rig_strrmode(mode);
            if (label && label[0]) {
                if (emitted++) out << ',';
                out << quoted(label);
            }
        }
    }
    out << ']';
}

void append_vfos(std::ostringstream &out, vfo_t vfos) {
    out << '[';
    size_t emitted = 0;
    for (unsigned bit = 0; bit < 31 && emitted < kMaximumCapabilities; ++bit) {
        const vfo_t vfo = static_cast<vfo_t>(1U << bit);
        if (vfos & vfo) {
            const char *label = rig_strvfo(vfo);
            if (label && label[0]) {
                if (emitted++) out << ',';
                out << quoted(label);
            }
        }
    }
    out << ']';
}

struct ModelCollection {
    std::vector<const rig_caps *> values;
};

struct RotatorModelCollection {
    std::vector<const rot_caps *> values;
};

int collect_model(const rig_caps *caps, rig_ptr_t opaque) {
    auto *models = static_cast<ModelCollection *>(opaque);
    if (caps && models->values.size() < kMaximumModels) models->values.push_back(caps);
    return 1;
}

int collect_rotator_model(const rot_caps *caps, rig_ptr_t opaque) {
    auto *models = static_cast<RotatorModelCollection *>(opaque);
    if (caps && models->values.size() < kMaximumModels) models->values.push_back(caps);
    return 1;
}

std::string rotator_models_json() {
    rot_load_all_backends();
    RotatorModelCollection models;
    rot_list_foreach(collect_rotator_model, &models);
    std::sort(models.values.begin(), models.values.end(), [](const rot_caps *left, const rot_caps *right) {
        return left->rot_model < right->rot_model;
    });
    std::ostringstream out;
    out << '[';
    for (size_t index = 0; index < models.values.size(); ++index) {
        const rot_caps *caps = models.values[index];
        if (index) out << ',';
        out << "{\"id\":" << caps->rot_model
            << ",\"manufacturer\":" << quoted(caps->mfg_name)
            << ",\"model\":" << quoted(caps->model_name)
            << ",\"macro\":" << quoted(caps->macro_name)
            << ",\"version\":" << quoted(caps->version)
            << ",\"status\":" << quoted(rig_strstatus(caps->status))
            << ",\"port\":" << quoted(port_name(caps->port_type))
            << ",\"minAz\":" << caps->min_az << ",\"maxAz\":" << caps->max_az
            << ",\"minEl\":" << caps->min_el << ",\"maxEl\":" << caps->max_el
            << ",\"getPosition\":" << (caps->get_position ? "true" : "false")
            << ",\"setPosition\":" << (caps->set_position ? "true" : "false")
            << ",\"stop\":" << (caps->stop ? "true" : "false")
            << ",\"park\":" << (caps->park ? "true" : "false") << '}';
    }
    out << ']';
    return out.str();
}

void append_ranges(std::ostringstream &out, const rig_caps *caps,
                   rmode_t &modes, vfo_t &vfos) {
    const freq_range_t *lists[] = {
        caps->rx_range_list1, caps->tx_range_list1,
        caps->rx_range_list2, caps->tx_range_list2,
        caps->rx_range_list3, caps->tx_range_list3,
        caps->rx_range_list4, caps->tx_range_list4,
        caps->rx_range_list5, caps->tx_range_list5,
    };
    out << '[';
    size_t emitted = 0;
    for (size_t list = 0; list < 10 && emitted < kMaximumRanges; ++list) {
        const bool transmit = (list % 2) == 1;
        for (size_t index = 0; index < HAMLIB_FRQRANGESIZ && emitted < kMaximumRanges; ++index) {
            const freq_range_t &range = lists[list][index];
            if (RIG_IS_FRNG_END(range)) break;
            modes |= range.modes;
            vfos |= range.vfo;
            if (emitted++) out << ',';
            out << "{\"tx\":" << (transmit ? "true" : "false")
                << ",\"startHz\":" << static_cast<uint64_t>(range.startf)
                << ",\"endHz\":" << static_cast<uint64_t>(range.endf)
                << ",\"lowMilliwatts\":" << range.low_power
                << ",\"highMilliwatts\":" << range.high_power
                << ",\"vfoMask\":" << static_cast<uint64_t>(range.vfo)
                << ",\"modeMask\":" << static_cast<uint64_t>(range.modes) << '}';
        }
    }
    out << ']';
}

std::string models_json() {
    rig_load_all_backends();
    ModelCollection models;
    rig_list_foreach(collect_model, &models);
    std::sort(models.values.begin(), models.values.end(), [](const rig_caps *left, const rig_caps *right) {
        return left->rig_model < right->rig_model;
    });
    std::ostringstream out;
    out << "{\"schema\":1,\"version\":\"4.7.2\",\"sourceDigest\":\"" << kSourceDigest
        << "\",\"backendCount\":37,\"modelCount\":" << models.values.size() << ",\"models\":[";
    for (size_t model_index = 0; model_index < models.values.size(); ++model_index) {
        const rig_caps *caps = models.values[model_index];
        if (model_index) out << ',';
        rmode_t modes = RIG_MODE_NONE;
        vfo_t vfos = RIG_VFO_NONE;
        std::ostringstream ranges;
        append_ranges(ranges, caps, modes, vfos);
        for (size_t filter = 0; filter < HAMLIB_FLTLSTSIZ; ++filter) {
            if (RIG_IS_FLT_END(caps->filters[filter])) break;
            modes |= caps->filters[filter].modes;
        }
        out << "{\"id\":" << caps->rig_model
            << ",\"manufacturer\":" << quoted(caps->mfg_name)
            << ",\"model\":" << quoted(caps->model_name)
            << ",\"backend\":\"backend-" << RIG_BACKEND_NUM(caps->rig_model) << "\""
            << ",\"backendId\":" << RIG_BACKEND_NUM(caps->rig_model)
            << ",\"driverVersion\":" << quoted(caps->version)
            << ",\"status\":" << quoted(rig_strstatus(caps->status))
            << ",\"portType\":\"" << port_name(caps->port_type) << "\""
            << ",\"serialRateMin\":" << caps->serial_rate_min
            << ",\"serialRateMax\":" << caps->serial_rate_max
            << ",\"serialDataBits\":" << caps->serial_data_bits
            << ",\"serialStopBits\":" << caps->serial_stop_bits
            << ",\"serialParity\":" << static_cast<int>(caps->serial_parity)
            << ",\"serialHandshake\":" << static_cast<int>(caps->serial_handshake)
            << ",\"timeoutMs\":" << std::clamp(caps->timeout, 0, 60000)
            << ",\"retry\":" << std::clamp(caps->retry, 0, 10)
            << ",\"pttType\":" << static_cast<int>(caps->ptt_type)
            << ",\"targetableVfo\":" << caps->targetable_vfo
            << ",\"maxRitHz\":" << caps->max_rit
            << ",\"maxXitHz\":" << caps->max_xit
            << ",\"maxIfShiftHz\":" << caps->max_ifshift
            << ",\"modes\":";
        append_modes(out, modes);
        out << ",\"vfos\":";
        append_vfos(out, vfos);
        out << ",\"ranges\":" << ranges.str() << ",\"filters\":[";
        size_t filters = 0;
        for (size_t filter = 0; filter < HAMLIB_FLTLSTSIZ && filters < kMaximumCapabilities; ++filter) {
            if (RIG_IS_FLT_END(caps->filters[filter])) break;
            if (filters++) out << ',';
            out << "{\"modeMask\":" << static_cast<uint64_t>(caps->filters[filter].modes)
                << ",\"widthHz\":" << caps->filters[filter].width << '}';
        }
        out << "],\"getLevels\":";
        append_names(out, caps->has_get_level, rig_strlevel);
        out << ",\"setLevels\":";
        append_names(out, caps->has_set_level, rig_strlevel);
        out << ",\"getFunctions\":";
        append_names(out, caps->has_get_func, rig_strfunc);
        out << ",\"setFunctions\":";
        append_names(out, caps->has_set_func, rig_strfunc);
        out << ",\"getParameters\":";
        append_names(out, caps->has_get_parm, rig_strparm);
        out << ",\"setParameters\":";
        append_names(out, caps->has_set_parm, rig_strparm);
        out << '}';
    }
    out << "]}";
    return out.str();
}

int checked(Session *value) {
    return value && value->rig ? RIG_OK : -RIG_EINVAL;
}

int writable(Session *value) {
    const int status = checked(value);
    if (status != RIG_OK) return status;
    return value->read_only ? -RIG_EINVAL : RIG_OK;
}

void close_bridge(Session *value) {
    if (value->bridge_hamlib >= 0) close(value->bridge_hamlib);
    if (value->bridge_application >= 0) close(value->bridge_application);
    value->bridge_hamlib = -1;
    value->bridge_application = -1;
}

std::string error_json(int status) {
    std::ostringstream out;
    out << "{\"ok\":" << (status == RIG_OK ? "true" : "false") << ",\"code\":" << status;
    if (status != RIG_OK) out << ",\"error\":" << quoted(rigerror(status));
    out << '}';
    return out.str();
}

void append_level(std::ostringstream &out, RIG *rig, setting_t setting, size_t &count) {
    if (!(rig->caps->has_get_level & setting)) return;
    value_t value{};
    if (rig_get_level(rig, RIG_VFO_CURR, setting, &value) != RIG_OK) return;
    if (count++) out << ',';
    out << quoted(rig_strlevel(setting)) << ':';
    if (RIG_LEVEL_IS_FLOAT(setting)) out << value.f;
    else out << value.i;
}

std::string snapshot_json(Session *value) {
    std::lock_guard<std::mutex> guard(value->mutex);
    if (!value->rig || !value->open) return "{\"ok\":false,\"code\":-1,\"error\":\"DISCONNECTED\"}";
    RIG *rig = value->rig;
    vfo_t vfo = RIG_VFO_CURR;
    freq_t frequency = 0;
    freq_t frequency_a = 0;
    freq_t frequency_b = 0;
    rmode_t mode = RIG_MODE_NONE;
    pbwidth_t width = 0;
    split_t split = RIG_SPLIT_OFF;
    vfo_t tx_vfo = RIG_VFO_NONE;
    shortfreq_t rit = 0;
    shortfreq_t xit = 0;
    ptt_t ptt = RIG_PTT_OFF;
    if (rig->caps->get_vfo) rig_get_vfo(rig, &vfo);
    if (rig->caps->get_freq) {
        rig_get_freq(rig, RIG_VFO_CURR, &frequency);
        rig_get_freq(rig, RIG_VFO_A, &frequency_a);
        rig_get_freq(rig, RIG_VFO_B, &frequency_b);
    }
    if (rig->caps->get_mode) rig_get_mode(rig, RIG_VFO_CURR, &mode, &width);
    if (rig->caps->get_split_vfo) rig_get_split_vfo(rig, RIG_VFO_CURR, &split, &tx_vfo);
    if (rig->caps->get_rit) rig_get_rit(rig, RIG_VFO_CURR, &rit);
    if (rig->caps->get_xit) rig_get_xit(rig, RIG_VFO_CURR, &xit);
    if (rig->caps->get_ptt) rig_get_ptt(rig, RIG_VFO_CURR, &ptt);
    std::ostringstream out;
    out << "{\"ok\":true,\"modelId\":" << rig->caps->rig_model
        << ",\"vfo\":" << quoted(rig_strvfo(vfo))
        << ",\"txVfo\":" << quoted(rig_strvfo(tx_vfo))
        << ",\"frequencyHz\":" << static_cast<uint64_t>(frequency)
        << ",\"frequencyAHz\":" << static_cast<uint64_t>(frequency_a)
        << ",\"frequencyBHz\":" << static_cast<uint64_t>(frequency_b)
        << ",\"mode\":" << quoted(rig_strrmode(mode))
        << ",\"passbandHz\":" << width
        << ",\"split\":" << (split == RIG_SPLIT_ON ? "true" : "false")
        << ",\"ritHz\":" << rit << ",\"xitHz\":" << xit
        << ",\"ptt\":" << (ptt != RIG_PTT_OFF ? "true" : "false")
        << ",\"levels\":{";
    size_t levels = 0;
    append_level(out, rig, RIG_LEVEL_AF, levels);
    append_level(out, rig, RIG_LEVEL_RF, levels);
    append_level(out, rig, RIG_LEVEL_RFPOWER, levels);
    append_level(out, rig, RIG_LEVEL_STRENGTH, levels);
    append_level(out, rig, RIG_LEVEL_SWR, levels);
    append_level(out, rig, RIG_LEVEL_ALC, levels);
    append_level(out, rig, RIG_LEVEL_RFPOWER_METER, levels);
    append_level(out, rig, RIG_LEVEL_RFPOWER_METER_WATTS, levels);
    out << "}}";
    return out.str();
}

jbyteArray bytes(JNIEnv *env, const unsigned char *data, int count) {
    jbyteArray result = env->NewByteArray(std::max(0, count));
    if (count > 0) env->SetByteArrayRegion(result, 0, count,
        reinterpret_cast<const jbyte *>(data));
    return result;
}
}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_app_shackcq_mobile_radio_hamlib_NativeHamlib_libraryInfoNative(JNIEnv *env, jobject) {
    std::ostringstream out;
    out << "{\"version\":" << quoted(rig_version()) << ",\"sourceDigest\":\"" << kSourceDigest
        << "\",\"licence\":" << quoted(rig_license()) << ",\"backendCount\":37}";
    return env->NewStringUTF(out.str().c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_app_shackcq_mobile_radio_hamlib_NativeHamlib_modelsNative(JNIEnv *env, jobject) {
    const std::string output = models_json();
    return env->NewStringUTF(output.c_str());
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_shackcq_mobile_radio_hamlib_NativeHamlib_sessionCreateNative(
        JNIEnv *, jobject, jint model_id) {
    rig_load_all_backends();
    if (model_id <= 0 || !rig_get_caps(model_id)) return 0;
    auto *value = new Session();
    value->rig = rig_init(model_id);
    if (!value->rig) {
        delete value;
        return 0;
    }
    return static_cast<jlong>(reinterpret_cast<intptr_t>(value));
}

extern "C" JNIEXPORT void JNICALL
Java_app_shackcq_mobile_radio_hamlib_NativeHamlib_sessionDestroyNative(
        JNIEnv *, jobject, jlong handle) {
    Session *value = session(handle);
    if (!value) return;
    {
        std::lock_guard<std::mutex> guard(value->mutex);
        value->cancelled = true;
        if (value->open) rig_close(value->rig);
        value->open = false;
        close_bridge(value);
        if (value->rig) rig_cleanup(value->rig);
        value->rig = nullptr;
    }
    delete value;
}

extern "C" JNIEXPORT jint JNICALL
Java_app_shackcq_mobile_radio_hamlib_NativeHamlib_sessionSetReadOnlyNative(
        JNIEnv *, jobject, jlong handle, jboolean read_only) {
    Session *value = session(handle);
    if (checked(value) != RIG_OK) return -RIG_EINVAL;
    std::lock_guard<std::mutex> guard(value->mutex);
    value->read_only = read_only == JNI_TRUE;
    return RIG_OK;
}

extern "C" JNIEXPORT jint JNICALL
Java_app_shackcq_mobile_radio_hamlib_NativeHamlib_sessionConfigureSerialNative(
        JNIEnv *, jobject, jlong handle, jint baud, jint data_bits, jint stop_bits,
        jint parity, jint handshake, jint timeout_ms, jint rts, jint dtr) {
    Session *value = session(handle);
    if (checked(value) != RIG_OK || baud < 300 || baud > 3000000 ||
            data_bits < 5 || data_bits > 8 || stop_bits < 1 || stop_bits > 2 ||
            timeout_ms < 50 || timeout_ms > 60000) return -RIG_EINVAL;
    std::lock_guard<std::mutex> guard(value->mutex);
    if (value->open) return -RIG_EINVAL;
    close_bridge(value);
    int descriptors[2] = {-1, -1};
    if (socketpair(AF_UNIX, SOCK_STREAM, 0, descriptors) != 0) return -RIG_EIO;
    value->bridge_hamlib = descriptors[0];
    value->bridge_application = descriptors[1];
    fcntl(value->bridge_hamlib, F_SETFD, FD_CLOEXEC);
    fcntl(value->bridge_application, F_SETFD, FD_CLOEXEC);
    hamlib_port_t *port = HAMLIB_RIGPORT(value->rig);
    port->type.rig = RIG_PORT_SERIAL;
    std::snprintf(port->pathname, sizeof(port->pathname), "shackcq-fd:%d", value->bridge_hamlib);
    port->parm.serial.rate = baud;
    port->parm.serial.data_bits = data_bits;
    port->parm.serial.stop_bits = stop_bits;
    port->parm.serial.parity = static_cast<serial_parity_e>(parity);
    port->parm.serial.handshake = static_cast<serial_handshake_e>(handshake);
    port->parm.serial.rts_state = static_cast<serial_control_state_e>(rts);
    port->parm.serial.dtr_state = static_cast<serial_control_state_e>(dtr);
    port->timeout = timeout_ms;
    value->cancelled = false;
    return RIG_OK;
}

extern "C" JNIEXPORT jint JNICALL
Java_app_shackcq_mobile_radio_hamlib_NativeHamlib_sessionConfigureNetworkNative(
        JNIEnv *env, jobject, jlong handle, jstring host_value, jint port_value, jint timeout_ms) {
    Session *value = session(handle);
    const std::string host = text(env, host_value);
    if (checked(value) != RIG_OK || host.empty() || host.size() > 253 ||
            port_value < 1 || port_value > 65535 || timeout_ms < 50 || timeout_ms > 60000 ||
            std::any_of(host.begin(), host.end(), [](unsigned char c) {
                return !(std::isalnum(c) || c == '.' || c == '-' || c == ':' || c == '[' || c == ']');
            })) return -RIG_EINVAL;
    std::lock_guard<std::mutex> guard(value->mutex);
    if (value->open) return -RIG_EINVAL;
    close_bridge(value);
    hamlib_port_t *port = HAMLIB_RIGPORT(value->rig);
    port->type.rig = RIG_PORT_NETWORK;
    std::snprintf(port->pathname, sizeof(port->pathname), "%s:%d", host.c_str(), port_value);
    port->timeout = timeout_ms;
    value->cancelled = false;
    return RIG_OK;
}

extern "C" JNIEXPORT jint JNICALL
Java_app_shackcq_mobile_radio_hamlib_NativeHamlib_sessionOpenNative(
        JNIEnv *, jobject, jlong handle) {
    Session *value = session(handle);
    if (checked(value) != RIG_OK) return -RIG_EINVAL;
    std::lock_guard<std::mutex> guard(value->mutex);
    if (value->open) return RIG_OK;
    const int status = rig_open(value->rig);
    value->open = status == RIG_OK;
    return status;
}

extern "C" JNIEXPORT jint JNICALL
Java_app_shackcq_mobile_radio_hamlib_NativeHamlib_sessionCloseNative(
        JNIEnv *, jobject, jlong handle) {
    Session *value = session(handle);
    if (checked(value) != RIG_OK) return -RIG_EINVAL;
    std::lock_guard<std::mutex> guard(value->mutex);
    value->cancelled = true;
    int status = RIG_OK;
    if (value->open) status = rig_close(value->rig);
    value->open = false;
    close_bridge(value);
    return status;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_app_shackcq_mobile_radio_hamlib_NativeHamlib_bridgeReadNative(
        JNIEnv *env, jobject, jlong handle, jint maximum, jint timeout_ms) {
    Session *value = session(handle);
    if (!value || maximum < 1 || maximum > kMaximumBridgeTransfer ||
            timeout_ms < 0 || timeout_ms > 60000) return env->NewByteArray(0);
    int descriptor;
    {
        std::lock_guard<std::mutex> guard(value->mutex);
        descriptor = value->bridge_application;
        if (descriptor < 0 || value->cancelled) return env->NewByteArray(0);
    }
    pollfd poll_value{descriptor, POLLIN, 0};
    if (poll(&poll_value, 1, timeout_ms) <= 0 || !(poll_value.revents & POLLIN))
        return env->NewByteArray(0);
    std::vector<unsigned char> buffer(static_cast<size_t>(maximum));
    const ssize_t count = read(descriptor, buffer.data(), buffer.size());
    return bytes(env, buffer.data(), count > 0 ? static_cast<int>(count) : 0);
}

extern "C" JNIEXPORT jint JNICALL
Java_app_shackcq_mobile_radio_hamlib_NativeHamlib_bridgeWriteNative(
        JNIEnv *env, jobject, jlong handle, jbyteArray data) {
    Session *value = session(handle);
    if (!value || !data) return -RIG_EINVAL;
    const jsize count = env->GetArrayLength(data);
    if (count < 1 || count > kMaximumBridgeTransfer) return -RIG_EINVAL;
    int descriptor;
    {
        std::lock_guard<std::mutex> guard(value->mutex);
        descriptor = value->bridge_application;
        if (descriptor < 0 || value->cancelled) return -RIG_EIO;
    }
    jbyte *input = env->GetByteArrayElements(data, nullptr);
    const ssize_t written = write(descriptor, input, static_cast<size_t>(count));
    env->ReleaseByteArrayElements(data, input, JNI_ABORT);
    return written >= 0 ? static_cast<int>(written) : -RIG_EIO;
}

extern "C" JNIEXPORT jstring JNICALL
Java_app_shackcq_mobile_radio_hamlib_NativeHamlib_sessionSnapshotNative(
        JNIEnv *env, jobject, jlong handle) {
    Session *value = session(handle);
    const std::string output = value ? snapshot_json(value)
        : "{\"ok\":false,\"code\":-1,\"error\":\"INVALID_SESSION\"}";
    return env->NewStringUTF(output.c_str());
}

extern "C" JNIEXPORT jint JNICALL
Java_app_shackcq_mobile_radio_hamlib_NativeHamlib_setFrequencyNative(
        JNIEnv *env, jobject, jlong handle, jstring vfo_value, jlong frequency) {
    Session *value = session(handle);
    if (writable(value) != RIG_OK || frequency <= 0 || frequency > INT64_C(10000000000000))
        return -RIG_EINVAL;
    const std::string vfo_text = text(env, vfo_value);
    const vfo_t vfo = vfo_text.empty() ? RIG_VFO_CURR : rig_parse_vfo(vfo_text.c_str());
    if (vfo == RIG_VFO_NONE) return -RIG_EINVAL;
    std::lock_guard<std::mutex> guard(value->mutex);
    return value->open ? rig_set_freq(value->rig, vfo, static_cast<freq_t>(frequency)) : -RIG_EIO;
}

extern "C" JNIEXPORT jint JNICALL
Java_app_shackcq_mobile_radio_hamlib_NativeHamlib_setVfoNative(
        JNIEnv *env, jobject, jlong handle, jstring vfo_value) {
    Session *value = session(handle);
    if (writable(value) != RIG_OK) return -RIG_EINVAL;
    const std::string vfo_text = text(env, vfo_value);
    const vfo_t vfo = rig_parse_vfo(vfo_text.c_str());
    if (vfo == RIG_VFO_NONE) return -RIG_EINVAL;
    std::lock_guard<std::mutex> guard(value->mutex);
    return value->open ? rig_set_vfo(value->rig, vfo) : -RIG_EIO;
}

extern "C" JNIEXPORT jint JNICALL
Java_app_shackcq_mobile_radio_hamlib_NativeHamlib_setModeNative(
        JNIEnv *env, jobject, jlong handle, jstring vfo_value, jstring mode_value,
        jint passband_hz) {
    Session *value = session(handle);
    if (writable(value) != RIG_OK || passband_hz < 0 || passband_hz > 10000000)
        return -RIG_EINVAL;
    const std::string vfo_text = text(env, vfo_value);
    const std::string mode_text = text(env, mode_value);
    const vfo_t vfo = vfo_text.empty() ? RIG_VFO_CURR : rig_parse_vfo(vfo_text.c_str());
    const rmode_t mode = rig_parse_mode(mode_text.c_str());
    if (vfo == RIG_VFO_NONE || mode == RIG_MODE_NONE) return -RIG_EINVAL;
    std::lock_guard<std::mutex> guard(value->mutex);
    return value->open ? rig_set_mode(value->rig, vfo, mode, passband_hz) : -RIG_EIO;
}

extern "C" JNIEXPORT jint JNICALL
Java_app_shackcq_mobile_radio_hamlib_NativeHamlib_setSplitNative(
        JNIEnv *env, jobject, jlong handle, jboolean enabled, jstring tx_vfo_value) {
    Session *value = session(handle);
    if (writable(value) != RIG_OK) return -RIG_EINVAL;
    const std::string tx_vfo_text = text(env, tx_vfo_value);
    const vfo_t tx_vfo = tx_vfo_text.empty() ? RIG_VFO_B : rig_parse_vfo(tx_vfo_text.c_str());
    if (tx_vfo == RIG_VFO_NONE) return -RIG_EINVAL;
    std::lock_guard<std::mutex> guard(value->mutex);
    return value->open ? rig_set_split_vfo(value->rig, RIG_VFO_CURR,
        enabled == JNI_TRUE ? RIG_SPLIT_ON : RIG_SPLIT_OFF, tx_vfo) : -RIG_EIO;
}

extern "C" JNIEXPORT jint JNICALL
Java_app_shackcq_mobile_radio_hamlib_NativeHamlib_setRitNative(
        JNIEnv *, jobject, jlong handle, jint offset_hz) {
    Session *value = session(handle);
    if (writable(value) != RIG_OK) return -RIG_EINVAL;
    std::lock_guard<std::mutex> guard(value->mutex);
    return value->open ? rig_set_rit(value->rig, RIG_VFO_CURR, offset_hz) : -RIG_EIO;
}

extern "C" JNIEXPORT jint JNICALL
Java_app_shackcq_mobile_radio_hamlib_NativeHamlib_setXitNative(
        JNIEnv *, jobject, jlong handle, jint offset_hz) {
    Session *value = session(handle);
    if (writable(value) != RIG_OK) return -RIG_EINVAL;
    std::lock_guard<std::mutex> guard(value->mutex);
    return value->open ? rig_set_xit(value->rig, RIG_VFO_CURR, offset_hz) : -RIG_EIO;
}

extern "C" JNIEXPORT jint JNICALL
Java_app_shackcq_mobile_radio_hamlib_NativeHamlib_setLevelNative(
        JNIEnv *env, jobject, jlong handle, jstring level_value, jdouble numeric_value) {
    Session *value = session(handle);
    if (writable(value) != RIG_OK || !std::isfinite(numeric_value) ||
            std::abs(numeric_value) > 1.0e9) return -RIG_EINVAL;
    const std::string level_text = text(env, level_value);
    const setting_t level = rig_parse_level(level_text.c_str());
    if (!level || !(value->rig->caps->has_set_level & level)) return -RIG_ENAVAIL;
    value_t input{};
    if (RIG_LEVEL_IS_FLOAT(level)) input.f = static_cast<float>(numeric_value);
    else input.i = static_cast<int>(numeric_value);
    std::lock_guard<std::mutex> guard(value->mutex);
    return value->open ? rig_set_level(value->rig, RIG_VFO_CURR, level, input) : -RIG_EIO;
}

extern "C" JNIEXPORT jint JNICALL
Java_app_shackcq_mobile_radio_hamlib_NativeHamlib_setFunctionNative(
        JNIEnv *env, jobject, jlong handle, jstring function_value, jboolean enabled) {
    Session *value = session(handle);
    if (writable(value) != RIG_OK) return -RIG_EINVAL;
    const std::string function_text = text(env, function_value);
    const setting_t function = rig_parse_func(function_text.c_str());
    if (!function || !(value->rig->caps->has_set_func & function)) return -RIG_ENAVAIL;
    std::lock_guard<std::mutex> guard(value->mutex);
    return value->open ? rig_set_func(value->rig, RIG_VFO_CURR, function,
        enabled == JNI_TRUE ? 1 : 0) : -RIG_EIO;
}

extern "C" JNIEXPORT jint JNICALL
Java_app_shackcq_mobile_radio_hamlib_NativeHamlib_setParameterNative(
        JNIEnv *env, jobject, jlong handle, jstring parameter_value, jdouble numeric_value) {
    Session *value = session(handle);
    if (writable(value) != RIG_OK || !std::isfinite(numeric_value) ||
            std::abs(numeric_value) > 1.0e9) return -RIG_EINVAL;
    const std::string parameter_text = text(env, parameter_value);
    const setting_t parameter = rig_parse_parm(parameter_text.c_str());
    if (!parameter || !(value->rig->caps->has_set_parm & parameter)) return -RIG_ENAVAIL;
    value_t input{};
    if (RIG_PARM_IS_FLOAT(parameter)) input.f = static_cast<float>(numeric_value);
    else input.i = static_cast<int>(numeric_value);
    std::lock_guard<std::mutex> guard(value->mutex);
    return value->open ? rig_set_parm(value->rig, parameter, input) : -RIG_EIO;
}

extern "C" JNIEXPORT jint JNICALL
Java_app_shackcq_mobile_radio_hamlib_NativeHamlib_setPttNative(
        JNIEnv *, jobject, jlong handle, jboolean enabled) {
    Session *value = session(handle);
    if (writable(value) != RIG_OK) return -RIG_EINVAL;
    std::lock_guard<std::mutex> guard(value->mutex);
    return value->open ? rig_set_ptt(value->rig, RIG_VFO_CURR,
        enabled == JNI_TRUE ? RIG_PTT_ON : RIG_PTT_OFF) : -RIG_EIO;
}

extern "C" JNIEXPORT jint JNICALL
Java_app_shackcq_mobile_radio_hamlib_NativeHamlib_tuneNative(
        JNIEnv *, jobject, jlong handle) {
    Session *value = session(handle);
    if (writable(value) != RIG_OK) return -RIG_EINVAL;
    std::lock_guard<std::mutex> guard(value->mutex);
    if (!value->open || !(value->rig->caps->vfo_ops & RIG_OP_TUNE)) return -RIG_ENAVAIL;
    return rig_vfo_op(value->rig, RIG_VFO_CURR, RIG_OP_TUNE);
}

extern "C" JNIEXPORT jstring JNICALL
Java_app_shackcq_mobile_radio_hamlib_NativeHamlib_rotatorModelsNative(JNIEnv *env, jobject) {
    const std::string output = rotator_models_json();
    return env->NewStringUTF(output.c_str());
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_shackcq_mobile_radio_hamlib_NativeHamlib_rotatorSessionCreateNative(
        JNIEnv *, jobject, jint model_id) {
    rot_load_all_backends();
    if (model_id <= 0 || !rot_get_caps(model_id)) return 0;
    auto *value = new RotatorSession();
    value->rotator = rot_init(model_id);
    if (!value->rotator) {
        delete value;
        return 0;
    }
    return static_cast<jlong>(reinterpret_cast<intptr_t>(value));
}

extern "C" JNIEXPORT void JNICALL
Java_app_shackcq_mobile_radio_hamlib_NativeHamlib_rotatorSessionDestroyNative(
        JNIEnv *, jobject, jlong handle) {
    RotatorSession *value = rotator_session(handle);
    if (!value) return;
    {
        std::lock_guard<std::mutex> guard(value->mutex);
        value->cancelled = true;
        if (value->open) rot_close(value->rotator);
        value->open = false;
        close_rotator_bridge(value);
        if (value->rotator) rot_cleanup(value->rotator);
        value->rotator = nullptr;
    }
    delete value;
}

extern "C" JNIEXPORT jint JNICALL
Java_app_shackcq_mobile_radio_hamlib_NativeHamlib_rotatorSessionSetReadOnlyNative(
        JNIEnv *, jobject, jlong handle, jboolean read_only) {
    RotatorSession *value = rotator_session(handle);
    if (!value || !value->rotator) return -RIG_EINVAL;
    std::lock_guard<std::mutex> guard(value->mutex);
    value->read_only = read_only == JNI_TRUE;
    return RIG_OK;
}

extern "C" JNIEXPORT jint JNICALL
Java_app_shackcq_mobile_radio_hamlib_NativeHamlib_rotatorSessionConfigureSerialNative(
        JNIEnv *, jobject, jlong handle, jint baud, jint data_bits, jint stop_bits,
        jint parity, jint handshake, jint timeout_ms, jint rts, jint dtr) {
    RotatorSession *value = rotator_session(handle);
    if (!value || !value->rotator || value->open || baud < 300 || baud > 3000000 ||
            data_bits < 5 || data_bits > 8 || stop_bits < 1 || stop_bits > 2 ||
            timeout_ms < 50 || timeout_ms > 60000) return -RIG_EINVAL;
    std::lock_guard<std::mutex> guard(value->mutex);
    close_rotator_bridge(value);
    int descriptors[2] = {-1, -1};
    if (socketpair(AF_UNIX, SOCK_STREAM, 0, descriptors) != 0) return -RIG_EIO;
    value->bridge_hamlib = descriptors[0];
    value->bridge_application = descriptors[1];
    fcntl(value->bridge_hamlib, F_SETFD, FD_CLOEXEC);
    fcntl(value->bridge_application, F_SETFD, FD_CLOEXEC);
    hamlib_port_t *port = HAMLIB_ROTPORT(value->rotator);
    port->type.rig = RIG_PORT_SERIAL;
    std::snprintf(port->pathname, sizeof(port->pathname), "shackcq-rot-fd:%d", value->bridge_hamlib);
    port->parm.serial.rate = baud;
    port->parm.serial.data_bits = data_bits;
    port->parm.serial.stop_bits = stop_bits;
    port->parm.serial.parity = static_cast<serial_parity_e>(parity);
    port->parm.serial.handshake = static_cast<serial_handshake_e>(handshake);
    port->parm.serial.rts_state = static_cast<serial_control_state_e>(rts);
    port->parm.serial.dtr_state = static_cast<serial_control_state_e>(dtr);
    port->timeout = timeout_ms;
    value->cancelled = false;
    return RIG_OK;
}

extern "C" JNIEXPORT jint JNICALL
Java_app_shackcq_mobile_radio_hamlib_NativeHamlib_rotatorSessionConfigureNetworkNative(
        JNIEnv *env, jobject, jlong handle, jstring host_value, jint port_value, jint timeout_ms) {
    RotatorSession *value = rotator_session(handle);
    const std::string host = text(env, host_value);
    if (!value || !value->rotator || value->open || host.empty() || host.size() > 253 ||
            port_value < 1 || port_value > 65535 || timeout_ms < 50 || timeout_ms > 60000 ||
            std::any_of(host.begin(), host.end(), [](unsigned char c) {
                return !(std::isalnum(c) || c == '.' || c == '-' || c == ':' || c == '[' || c == ']');
            })) return -RIG_EINVAL;
    std::lock_guard<std::mutex> guard(value->mutex);
    close_rotator_bridge(value);
    hamlib_port_t *port = HAMLIB_ROTPORT(value->rotator);
    port->type.rig = RIG_PORT_NETWORK;
    std::snprintf(port->pathname, sizeof(port->pathname), "%s:%d", host.c_str(), port_value);
    port->timeout = timeout_ms;
    value->cancelled = false;
    return RIG_OK;
}

extern "C" JNIEXPORT jint JNICALL
Java_app_shackcq_mobile_radio_hamlib_NativeHamlib_rotatorSessionOpenNative(
        JNIEnv *, jobject, jlong handle) {
    RotatorSession *value = rotator_session(handle);
    if (!value || !value->rotator) return -RIG_EINVAL;
    std::lock_guard<std::mutex> guard(value->mutex);
    if (value->open) return RIG_OK;
    const int status = rot_open(value->rotator);
    value->open = status == RIG_OK;
    return status;
}

extern "C" JNIEXPORT jint JNICALL
Java_app_shackcq_mobile_radio_hamlib_NativeHamlib_rotatorSessionCloseNative(
        JNIEnv *, jobject, jlong handle) {
    RotatorSession *value = rotator_session(handle);
    if (!value || !value->rotator) return -RIG_EINVAL;
    std::lock_guard<std::mutex> guard(value->mutex);
    value->cancelled = true;
    const int status = value->open ? rot_close(value->rotator) : RIG_OK;
    value->open = false;
    close_rotator_bridge(value);
    return status;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_app_shackcq_mobile_radio_hamlib_NativeHamlib_rotatorBridgeReadNative(
        JNIEnv *env, jobject, jlong handle, jint maximum, jint timeout_ms) {
    RotatorSession *value = rotator_session(handle);
    if (!value || maximum < 1 || maximum > kMaximumBridgeTransfer || timeout_ms < 0 || timeout_ms > 60000)
        return env->NewByteArray(0);
    int descriptor;
    {
        std::lock_guard<std::mutex> guard(value->mutex);
        descriptor = value->bridge_application;
        if (descriptor < 0 || value->cancelled) return env->NewByteArray(0);
    }
    pollfd poll_value{descriptor, POLLIN, 0};
    if (poll(&poll_value, 1, timeout_ms) <= 0 || !(poll_value.revents & POLLIN)) return env->NewByteArray(0);
    std::vector<unsigned char> buffer(static_cast<size_t>(maximum));
    const ssize_t count = read(descriptor, buffer.data(), buffer.size());
    return bytes(env, buffer.data(), count > 0 ? static_cast<int>(count) : 0);
}

extern "C" JNIEXPORT jint JNICALL
Java_app_shackcq_mobile_radio_hamlib_NativeHamlib_rotatorBridgeWriteNative(
        JNIEnv *env, jobject, jlong handle, jbyteArray data) {
    RotatorSession *value = rotator_session(handle);
    if (!value || !data) return -RIG_EINVAL;
    const jsize count = env->GetArrayLength(data);
    if (count < 1 || count > kMaximumBridgeTransfer) return -RIG_EINVAL;
    int descriptor;
    {
        std::lock_guard<std::mutex> guard(value->mutex);
        descriptor = value->bridge_application;
        if (descriptor < 0 || value->cancelled) return -RIG_EIO;
    }
    jbyte *input = env->GetByteArrayElements(data, nullptr);
    const ssize_t written = write(descriptor, input, static_cast<size_t>(count));
    env->ReleaseByteArrayElements(data, input, JNI_ABORT);
    return written >= 0 ? static_cast<int>(written) : -RIG_EIO;
}

extern "C" JNIEXPORT jstring JNICALL
Java_app_shackcq_mobile_radio_hamlib_NativeHamlib_rotatorPollNative(
        JNIEnv *env, jobject, jlong handle) {
    RotatorSession *value = rotator_session(handle);
    if (!value || !value->rotator) return env->NewStringUTF("{\"ok\":false,\"code\":-1}");
    std::lock_guard<std::mutex> guard(value->mutex);
    azimuth_t azimuth = 0;
    elevation_t elevation = 0;
    const int status = value->open ? rot_get_position(value->rotator, &azimuth, &elevation) : -RIG_EIO;
    std::ostringstream out;
    out << "{\"ok\":" << (status == RIG_OK ? "true" : "false") << ",\"code\":" << status
        << ",\"azimuth\":" << azimuth << ",\"elevation\":" << elevation << '}';
    return env->NewStringUTF(out.str().c_str());
}

extern "C" JNIEXPORT jint JNICALL
Java_app_shackcq_mobile_radio_hamlib_NativeHamlib_rotatorSetPositionNative(
        JNIEnv *, jobject, jlong handle, jdouble azimuth, jdouble elevation) {
    RotatorSession *value = rotator_session(handle);
    if (!value || !value->rotator || value->read_only || !std::isfinite(azimuth) || !std::isfinite(elevation)) return -RIG_EINVAL;
    std::lock_guard<std::mutex> guard(value->mutex);
    return value->open ? rot_set_position(value->rotator, static_cast<azimuth_t>(azimuth), static_cast<elevation_t>(elevation)) : -RIG_EIO;
}

extern "C" JNIEXPORT jint JNICALL
Java_app_shackcq_mobile_radio_hamlib_NativeHamlib_rotatorStopNative(
        JNIEnv *, jobject, jlong handle) {
    RotatorSession *value = rotator_session(handle);
    if (!value || !value->rotator) return -RIG_EINVAL;
    std::lock_guard<std::mutex> guard(value->mutex);
    return value->open ? rot_stop(value->rotator) : -RIG_EIO;
}

extern "C" JNIEXPORT jint JNICALL
Java_app_shackcq_mobile_radio_hamlib_NativeHamlib_rotatorParkNative(
        JNIEnv *, jobject, jlong handle) {
    RotatorSession *value = rotator_session(handle);
    if (!value || !value->rotator || value->read_only) return -RIG_EINVAL;
    std::lock_guard<std::mutex> guard(value->mutex);
    return value->open ? rot_park(value->rotator) : -RIG_EIO;
}

extern "C" JNIEXPORT jstring JNICALL
Java_app_shackcq_mobile_radio_hamlib_NativeHamlib_errorNative(
        JNIEnv *env, jobject, jint status) {
    const std::string output = error_json(status);
    return env->NewStringUTF(output.c_str());
}
