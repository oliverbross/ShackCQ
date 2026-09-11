// SPDX-License-Identifier: GPL-3.0-only
#include <QCoreApplication>
#include <QFile>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdio>

#ifdef SHACKCQ_HAVE_HAMLIB
#include <hamlib/rig.h>
#endif

namespace {
constexpr qsizetype MaxLineBytes = 64 * 1024;

void reply(const QString &requestId, quint64 epoch, bool ok,
           const QString &code, const QJsonObject &values = {}) {
  QJsonObject response = values;
  response.insert("requestId", requestId);
  response.insert("epoch", QJsonValue::fromVariant(epoch));
  response.insert("ok", ok);
  response.insert("code", code);
  const QByteArray bytes =
      QJsonDocument(response).toJson(QJsonDocument::Compact) + '\n';
  (void)std::fwrite(bytes.constData(), 1, size_t(bytes.size()), stdout);
  (void)std::fflush(stdout);
}

#ifdef SHACKCQ_HAVE_HAMLIB
rmode_t parseMode(const QString &value) {
  if (value.compare(QStringLiteral("DATA"), Qt::CaseInsensitive) == 0)
    return RIG_MODE_PKTUSB;
  if (value.compare(QStringLiteral("CW"), Qt::CaseInsensitive) == 0)
    return RIG_MODE_CW;
  return rig_parse_mode(value.toLatin1().constData());
}

QString modeName(rmode_t value) {
  if (value == RIG_MODE_PKTUSB || value == RIG_MODE_PKTLSB)
    return QStringLiteral("DATA");
  if (value == RIG_MODE_CWR)
    return QStringLiteral("CW");
  return QString::fromLatin1(rig_strrmode(value));
}

QString agcName(int value) {
  switch (value) {
  case RIG_AGC_OFF: return QStringLiteral("OFF");
  case RIG_AGC_SUPERFAST: return QStringLiteral("SUPERFAST");
  case RIG_AGC_FAST: return QStringLiteral("FAST");
  case RIG_AGC_MEDIUM: return QStringLiteral("MEDIUM");
  case RIG_AGC_SLOW: return QStringLiteral("SLOW");
  case RIG_AGC_USER: return QStringLiteral("USER");
  case RIG_AGC_AUTO: return QStringLiteral("AUTO");
  default: return QString::number(value);
  }
}

int parseAgc(const QString &value) {
  const QString mode = value.trimmed().toUpper();
  if (mode == QStringLiteral("OFF")) return RIG_AGC_OFF;
  if (mode == QStringLiteral("SUPERFAST")) return RIG_AGC_SUPERFAST;
  if (mode == QStringLiteral("FAST")) return RIG_AGC_FAST;
  if (mode == QStringLiteral("MEDIUM")) return RIG_AGC_MEDIUM;
  if (mode == QStringLiteral("SLOW")) return RIG_AGC_SLOW;
  if (mode == QStringLiteral("USER")) return RIG_AGC_USER;
  if (mode == QStringLiteral("AUTO")) return RIG_AGC_AUTO;
  return -1;
}

QJsonObject describe(const rig_caps *caps, int modelId) {
  QJsonArray ranges, filters, modes, setters, meters, agcModes;
  if (caps) {
    auto appendRanges = [&ranges](const freq_range_t *source) {
      for (int index = 0; index < HAMLIB_FRQRANGESIZ; ++index) {
        const auto &range = source[index];
        if (RIG_IS_FRNG_END(range))
          break;
        if (range.startf > 0 && range.endf >= range.startf)
          ranges.append(QJsonObject{{"min", double(range.startf)},
                                    {"max", double(range.endf)}});
      }
    };
    appendRanges(caps->rx_range_list1);
    appendRanges(caps->rx_range_list2);
    appendRanges(caps->rx_range_list3);
    appendRanges(caps->rx_range_list4);
    appendRanges(caps->rx_range_list5);

    struct CloudMode {
      rmode_t hamlib;
      const char *name;
    };
    static constexpr std::array<CloudMode, 6> knownModes{{
        {RIG_MODE_CW | RIG_MODE_CWR, "CW"},
        {RIG_MODE_USB, "USB"},
        {RIG_MODE_LSB, "LSB"},
        {RIG_MODE_AM, "AM"},
        {RIG_MODE_FM, "FM"},
        {RIG_MODE_PKTUSB | RIG_MODE_PKTLSB, "DATA"},
    }};
    for (int index = 0; index < HAMLIB_FLTLSTSIZ; ++index) {
      const auto &filter = caps->filters[index];
      if (RIG_IS_FLT_END(filter))
        break;
      if (filter.width > 0 && !filters.contains(int(filter.width)))
        filters.append(int(filter.width));
      for (const auto &candidate : knownModes) {
        const QString name = QString::fromLatin1(candidate.name);
        if ((filter.modes & candidate.hamlib) && !modes.contains(name))
          modes.append(name);
      }
    }
    if (caps->set_freq && caps->get_freq)
      setters.append("radio.set.frequency");
    if (caps->set_mode && caps->get_mode) {
      setters.append("radio.set.mode");
      setters.append("radio.set.filter");
      if (setters.contains("radio.set.frequency"))
        setters.append("preset.recall");
    }
    const auto addLevelSetter = [&](setting_t level, const char *action) {
      if (caps->get_level && caps->set_level &&
          (caps->has_get_level & level) && (caps->has_set_level & level))
        setters.append(QString::fromLatin1(action));
    };
    addLevelSetter(RIG_LEVEL_RF, "radio.set.rfGain");
    addLevelSetter(RIG_LEVEL_AF, "radio.set.afGain");
    addLevelSetter(RIG_LEVEL_SQL, "radio.set.squelch");
    addLevelSetter(RIG_LEVEL_AGC, "radio.set.agc");
    if (setters.contains(QStringLiteral("radio.set.agc"))) {
      for (const char *mode : {"OFF", "SUPERFAST", "FAST", "MEDIUM", "SLOW", "USER", "AUTO"})
        agcModes.append(QString::fromLatin1(mode));
    }
    const auto addFunctionSetter = [&](setting_t function, const char *action) {
      if (caps->get_func && caps->set_func &&
          (caps->has_get_func & function) && (caps->has_set_func & function))
        setters.append(QString::fromLatin1(action));
    };
    addFunctionSetter(RIG_FUNC_NB, "radio.set.noiseBlanker");
    addFunctionSetter(RIG_FUNC_ANF, "radio.set.notch");
    addFunctionSetter(RIG_FUNC_NR, "radio.set.noiseReduction");
    if (caps->get_rit && caps->set_rit)
      setters.append(QStringLiteral("radio.set.rit"));
    if (caps->get_level) {
      if (caps->has_get_level & RIG_LEVEL_STRENGTH)
        meters.append("signal");
      if (caps->has_get_level & RIG_LEVEL_SWR)
        meters.append("swr");
      if (caps->has_get_level & RIG_LEVEL_ALC)
        meters.append("alc");
    }
  }
  return {{"model", caps && caps->model_name
                        ? QString::fromUtf8(caps->model_name)
                        : QString::number(modelId)},
          {"manufacturer", caps && caps->mfg_name
                               ? QString::fromUtf8(caps->mfg_name)
                               : QStringLiteral("Hamlib")},
          {"frequencyRangesHz", ranges},
          {"modes", modes},
          {"filtersHz", filters},
          {"setters", setters},
          {"agcModes", agcModes},
          {"meters", meters},
          {"pttSupported", bool(caps && caps->set_ptt && caps->get_ptt)}};
}

QJsonObject observe(RIG *rig, bool full = true) {
  freq_t frequency = 0;
  rmode_t mode = RIG_MODE_NONE;
  pbwidth_t width = 0;
  ptt_t ptt = RIG_PTT_OFF;
  const bool frequencyOk =
      rig_get_freq(rig, RIG_VFO_CURR, &frequency) == RIG_OK;
  const bool modeOk =
      rig_get_mode(rig, RIG_VFO_CURR, &mode, &width) == RIG_OK;
  const bool pttOk = rig_get_ptt(rig, RIG_VFO_CURR, &ptt) == RIG_OK;
  QJsonObject result{{"frequencyHz", frequencyOk ? QJsonValue(double(frequency))
                                                   : QJsonValue::Null},
                     {"mode", modeOk ? QJsonValue(modeName(mode)) : QJsonValue::Null},
                     {"filterHz", modeOk ? QJsonValue(int(width)) : QJsonValue::Null},
                     {"transmitting", pttOk ? QJsonValue(ptt != RIG_PTT_OFF)
                                             : QJsonValue::Null}};
  if (!full)
    return result;
  QJsonObject meters;
  auto readLevel = [rig, &meters](setting_t level, const QString &name,
                                  bool floatingPoint) {
    if (!rig_has_get_level(rig, level))
      return;
    value_t value{};
    if (rig_get_level(rig, RIG_VFO_CURR, level, &value) == RIG_OK)
      meters.insert(name, floatingPoint ? QJsonValue(double(value.f))
                                        : QJsonValue(value.i));
  };
  readLevel(RIG_LEVEL_STRENGTH, QStringLiteral("signal"), false);
  readLevel(RIG_LEVEL_SWR, QStringLiteral("swr"), true);
  readLevel(RIG_LEVEL_ALC, QStringLiteral("alc"), true);
  result.insert(QStringLiteral("meters"), meters);
  const auto readPercent = [&](setting_t level, const char *name) {
    if (!rig_has_get_level(rig, level)) return;
    value_t value{};
    if (rig_get_level(rig, RIG_VFO_CURR, level, &value) == RIG_OK)
      result.insert(QString::fromLatin1(name),
                    qBound(0, int(std::lround(value.f * 100.0)), 100));
  };
  readPercent(RIG_LEVEL_RF, "rfGain");
  readPercent(RIG_LEVEL_AF, "afGain");
  readPercent(RIG_LEVEL_SQL, "squelch");
  if (rig_has_get_level(rig, RIG_LEVEL_AGC)) {
    value_t value{};
    if (rig_get_level(rig, RIG_VFO_CURR, RIG_LEVEL_AGC, &value) == RIG_OK)
      result.insert(QStringLiteral("agc"), agcName(value.i));
  }
  const auto readFunction = [&](setting_t function, const char *name) {
    if (!rig_has_get_func(rig, function)) return;
    int enabled = 0;
    if (rig_get_func(rig, RIG_VFO_CURR, function, &enabled) == RIG_OK)
      result.insert(QString::fromLatin1(name), enabled != 0);
  };
  readFunction(RIG_FUNC_NB, "noiseBlanker");
  readFunction(RIG_FUNC_ANF, "notch");
  readFunction(RIG_FUNC_NR, "noiseReduction");
  if (rig->caps && rig->caps->get_rit) {
    shortfreq_t rit = 0;
    if (rig_get_rit(rig, RIG_VFO_CURR, &rit) == RIG_OK) {
      result.insert(QStringLiteral("ritHz"), int(rit));
      result.insert(QStringLiteral("ritEnabled"), rit != 0);
    }
  }
  return result;
}

QJsonObject mutationReadback(RIG *rig, const QString &action) {
  if (action == QStringLiteral("radio.set.frequency")) {
    freq_t frequency = 0;
    return {{"frequencyHz",
             rig_get_freq(rig, RIG_VFO_CURR, &frequency) == RIG_OK
                 ? QJsonValue(double(frequency))
                 : QJsonValue::Null}};
  }
  if (action == QStringLiteral("radio.set.mode") ||
      action == QStringLiteral("radio.set.filter")) {
    rmode_t mode = RIG_MODE_NONE;
    pbwidth_t width = 0;
    const bool ok =
        rig_get_mode(rig, RIG_VFO_CURR, &mode, &width) == RIG_OK;
    return {{"mode", ok ? QJsonValue(modeName(mode)) : QJsonValue::Null},
            {"filterHz", ok ? QJsonValue(int(width)) : QJsonValue::Null}};
  }
  if (action == QStringLiteral("radio.set.rfGain") ||
      action == QStringLiteral("radio.set.afGain") ||
      action == QStringLiteral("radio.set.squelch") ||
      action == QStringLiteral("radio.set.agc")) {
    const setting_t level =
        action == QStringLiteral("radio.set.rfGain")
            ? RIG_LEVEL_RF
            : action == QStringLiteral("radio.set.afGain")
                  ? RIG_LEVEL_AF
                  : action == QStringLiteral("radio.set.squelch")
                        ? RIG_LEVEL_SQL
                        : RIG_LEVEL_AGC;
    value_t value{};
    if (rig_get_level(rig, RIG_VFO_CURR, level, &value) != RIG_OK)
      return {};
    if (action == QStringLiteral("radio.set.agc"))
      return {{"agc", agcName(value.i)}};
    const char *name = action == QStringLiteral("radio.set.rfGain")
                           ? "rfGain"
                           : action == QStringLiteral("radio.set.afGain")
                                 ? "afGain"
                                 : "squelch";
    return {{QString::fromLatin1(name),
             qBound(0, int(std::lround(value.f * 100.0)), 100)}};
  }
  if (action == QStringLiteral("radio.set.noiseBlanker") ||
      action == QStringLiteral("radio.set.notch") ||
      action == QStringLiteral("radio.set.noiseReduction")) {
    const setting_t function =
        action == QStringLiteral("radio.set.noiseBlanker")
            ? RIG_FUNC_NB
            : action == QStringLiteral("radio.set.notch") ? RIG_FUNC_ANF
                                                            : RIG_FUNC_NR;
    int enabled = 0;
    if (rig_get_func(rig, RIG_VFO_CURR, function, &enabled) != RIG_OK)
      return {};
    const char *name = action == QStringLiteral("radio.set.noiseBlanker")
                           ? "noiseBlanker"
                           : action == QStringLiteral("radio.set.notch")
                                 ? "notch"
                                 : "noiseReduction";
    return {{QString::fromLatin1(name), enabled != 0}};
  }
  if (action == QStringLiteral("radio.set.rit")) {
    shortfreq_t rit = 0;
    if (rig_get_rit(rig, RIG_VFO_CURR, &rit) != RIG_OK)
      return {};
    return {{"ritHz", int(rit)}, {"ritEnabled", rit != 0}};
  }
  return {};
}

bool forceRx(RIG *rig, bool *transmitting = nullptr) {
  ptt_t observed = RIG_PTT_ON;
  bool ok = rig && rig_get_ptt(rig, RIG_VFO_CURR, &observed) == RIG_OK;
  if (ok && observed != RIG_PTT_OFF) {
    ok = rig_set_ptt(rig, RIG_VFO_CURR, RIG_PTT_OFF) == RIG_OK &&
         rig_get_ptt(rig, RIG_VFO_CURR, &observed) == RIG_OK &&
         observed == RIG_PTT_OFF;
  }
  if (transmitting)
    *transmitting = observed != RIG_PTT_OFF;
  return ok && observed == RIG_PTT_OFF;
}
#endif
} // namespace

int main(int argc, char **argv) {
  QCoreApplication application(argc, argv);
  Q_UNUSED(application);
#ifdef SHACKCQ_HAVE_HAMLIB
  RIG *rig = nullptr;
  rig_set_debug(RIG_DEBUG_NONE);
  rig_load_all_backends();
#endif
  QFile input;
  if (!input.open(stdin, QIODevice::ReadOnly))
    return 64;
  while (true) {
      const QByteArray line = input.readLine(MaxLineBytes + 1);
      if (line.isEmpty())
        break;
      QJsonParseError parse;
      const QJsonObject frame =
          QJsonDocument::fromJson(line, &parse).object();
      const QString requestId = frame.value("requestId").toString();
      const quint64 epoch = frame.value("epoch").toVariant().toULongLong();
      const QString operation = frame.value("operation").toString();
      const QJsonObject parameters = frame.value("parameters").toObject();
      if (line.isEmpty() || line.size() > MaxLineBytes ||
          parse.error != QJsonParseError::NoError || requestId.isEmpty() ||
          epoch == 0 || operation.isEmpty()) {
        return 64;
      }
      if (operation == "close") {
        break;
      }
#ifndef SHACKCQ_HAVE_HAMLIB
      reply(requestId, epoch, false, "HAMLIB_NOT_LINKED");
#else
      if (operation == "open") {
        if (rig) {
          rig_close(rig);
          rig_cleanup(rig);
          rig = nullptr;
        }
        const int modelId = parameters.value("modelId").toInt();
        const QString route = parameters.value("route").toString();
        const int baudRate = parameters.value("baudRate").toInt();
        rig = rig_init(modelId);
        if (!rig || route.isEmpty()) {
          if (rig) rig_cleanup(rig);
          rig = nullptr;
          reply(requestId, epoch, false, "OPEN_REJECTED");
          continue;
        }
        auto configure = [rig](const char *name, const QString &value) {
          const token_t token = rig_token_lookup(rig, name);
          return token != RIG_CONF_END &&
                 rig_set_conf(rig, token, value.toUtf8().constData()) == RIG_OK;
        };
        if (!configure("rig_pathname", route)) {
          rig_cleanup(rig);
          rig = nullptr;
          reply(requestId, epoch, false, "ROUTE_REJECTED");
          continue;
        }
        if (baudRate > 0)
          (void)configure("serial_speed", QString::number(baudRate));
        // Keep every individual backend wait bounded. The parent watchdog is
        // authoritative and kills this process if a backend ignores it.
        (void)configure("timeout", QStringLiteral("500"));
        (void)configure("retry", QStringLiteral("0"));
        (void)configure("timeout_retry", QStringLiteral("0"));
        if (rig_open(rig) != RIG_OK) {
          rig_cleanup(rig);
          rig = nullptr;
          reply(requestId, epoch, false, "OPEN_FAILED");
          continue;
        }
        reply(requestId, epoch, true, "OPENED", describe(rig->caps, modelId));
        continue;
      }
      if (!rig) {
        reply(requestId, epoch, false, "RADIO_OFFLINE");
        continue;
      }
      if (operation == "snapshot") {
        const QJsonObject values =
            observe(rig, parameters.value("full").toBool(true));
        const bool ok = !values.value("frequencyHz").isNull() &&
                        !values.value("mode").isNull();
        reply(requestId, epoch, ok, ok ? "OBSERVED" : "READBACK_FAILED",
              values);
        continue;
      }
      if (operation == "stop" || operation == "ptt") {
        const bool enabled = operation == "ptt" &&
                             parameters.value("enabled").toBool(false);
        ptt_t observed = RIG_PTT_ON;
        bool transmitting = true;
        const bool ok = enabled
                            ? rig_set_ptt(rig, RIG_VFO_CURR, RIG_PTT_ON) == RIG_OK &&
                                  rig_get_ptt(rig, RIG_VFO_CURR, &observed) == RIG_OK &&
                                  observed != RIG_PTT_OFF
                            : forceRx(rig, &transmitting);
        if (enabled)
          transmitting = observed != RIG_PTT_OFF;
        reply(requestId, epoch, ok, ok ? "PTT_READBACK_CONFIRMED"
                                      : "PTT_READBACK_UNCONFIRMED",
              {{"transmitting", transmitting}});
        continue;
      }
      if (operation != "mutate") {
        reply(requestId, epoch, false, "OPERATION_PROHIBITED");
        continue;
      }
      const QString action = parameters.value("action").toString();
      const QJsonObject values = parameters.value("parameters").toObject();
      int code = RIG_EINVAL;
      if (action == "radio.set.frequency") {
        code = rig_set_freq(rig, RIG_VFO_CURR,
                            freq_t(values.value("frequencyHz").toDouble()));
      } else if (action == "radio.set.mode" || action == "radio.set.filter") {
        rmode_t currentMode = RIG_MODE_NONE;
        pbwidth_t currentWidth = 0;
        const rmode_t mode =
            action == "radio.set.mode"
                ? parseMode(values.value("mode").toString())
                : rig_get_mode(rig, RIG_VFO_CURR, &currentMode,
                               &currentWidth) == RIG_OK
                      ? currentMode
                      : RIG_MODE_NONE;
        const pbwidth_t width = action == "radio.set.filter"
                                    ? values.value("filterHz").toInt()
                                    : RIG_PASSBAND_NORMAL;
        code = mode == RIG_MODE_NONE
                   ? RIG_EINVAL
                   : rig_set_mode(rig, RIG_VFO_CURR, mode, width);
      } else if (action == "radio.set.rfGain" ||
                 action == "radio.set.afGain" ||
                 action == "radio.set.squelch") {
        const double requested = values.value("value").toDouble(-1.0);
        const setting_t level = action == "radio.set.rfGain"
                                    ? RIG_LEVEL_RF
                                    : action == "radio.set.afGain"
                                          ? RIG_LEVEL_AF
                                          : RIG_LEVEL_SQL;
        value_t value{};
        value.f = float(requested / 100.0);
        code = requested >= 0.0 && requested <= 100.0 &&
                       rig_has_set_level(rig, level)
                   ? rig_set_level(rig, RIG_VFO_CURR, level, value)
                   : RIG_EINVAL;
      } else if (action == "radio.set.agc") {
        const int requested = parseAgc(values.value("mode").toString());
        value_t value{};
        value.i = requested;
        code = requested >= 0 && rig_has_set_level(rig, RIG_LEVEL_AGC)
                   ? rig_set_level(rig, RIG_VFO_CURR, RIG_LEVEL_AGC, value)
                   : RIG_EINVAL;
      } else if (action == "radio.set.noiseBlanker" ||
                 action == "radio.set.notch" ||
                 action == "radio.set.noiseReduction") {
        const setting_t function = action == "radio.set.noiseBlanker"
                                       ? RIG_FUNC_NB
                                       : action == "radio.set.notch"
                                             ? RIG_FUNC_ANF
                                             : RIG_FUNC_NR;
        code = values.value("enabled").isBool() &&
                       rig_has_set_func(rig, function)
                   ? rig_set_func(rig, RIG_VFO_CURR, function,
                                  values.value("enabled").toBool() ? 1 : 0)
                   : RIG_EINVAL;
      } else if (action == "radio.set.rit") {
        shortfreq_t requested = 0;
        if (values.value("enabled").toBool(true))
          requested = shortfreq_t(values.value("valueHz").toInt());
        code = rig->caps && rig->caps->set_rit &&
                       requested >= -100'000 && requested <= 100'000
                   ? rig_set_rit(rig, RIG_VFO_CURR, requested)
                   : RIG_EINVAL;
      }
      const QJsonObject observed =
          code == RIG_OK ? mutationReadback(rig, action) : QJsonObject{};
      const bool confirmed = code == RIG_OK && !observed.isEmpty() &&
                             std::none_of(observed.begin(), observed.end(),
                                          [](const QJsonValue &value) {
                                            return value.isNull();
                                          });
      reply(requestId, epoch, confirmed,
            confirmed ? "MUTATION_OBSERVED"
                      : code == RIG_OK ? "MUTATION_READBACK_FAILED"
                                       : "MUTATION_FAILED",
            observed);
#endif
  }
#ifdef SHACKCQ_HAVE_HAMLIB
  if (rig) {
    // Parent EOF, explicit close, and ordinary shutdown all make one bounded
    // best-effort PTT-off/readback attempt before releasing the route.
    (void)forceRx(rig);
    rig_close(rig);
    rig_cleanup(rig);
  }
#endif
  return 0;
}
