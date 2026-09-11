#include "shackcq/desktop/DesktopRadioController.hpp"

#include <algorithm>
#include <array>
#include <iterator>
#include <QRegularExpression>
#include <QSet>
#include <QUrl>
#include <tuple>

#ifdef SHACKCQ_HAVE_NATIVE_DIGI
#include "shackcq_flex.h"
#endif

#ifdef SHACKCQ_HAVE_HAMLIB
#include <hamlib/rig.h>

namespace {
rmode_t hamlibModeForCloud(const QString &value) {
  if (value.compare(QStringLiteral("DATA"), Qt::CaseInsensitive) == 0)
    return RIG_MODE_PKTUSB;
  if (value.compare(QStringLiteral("CW"), Qt::CaseInsensitive) == 0)
    return RIG_MODE_CW;
  return rig_parse_mode(value.toLatin1().constData());
}

} // namespace
#endif

namespace shackcq::desktop {
namespace {
constexpr int RadioProfilesSchema = 3;

QVariantMap hamlibSnapshot(const QString &model, quint64 frequency,
                           const QString &mode) {
  return {
      {"id", "hamlib:0"},
      {"label", model.isEmpty() ? QStringLiteral("Hamlib receiver") : model},
      {"backendIndex", 0},
      {"enabled", true},
      {"muted", false},
      {"centreFrequencyHz", QVariant::fromValue<qulonglong>(frequency)},
      {"vfoAHz", QVariant::fromValue<qulonglong>(frequency)},
      {"vfoBHz", 0ULL},
      {"selectedChannel", 0},
      {"effectiveReceiveHz", QVariant::fromValue<qulonglong>(frequency)},
      {"mode", mode},
      {"filterLowHz", 0},
      {"filterHighHz", 0},
      {"sampleRate", 0},
      {"iqState", "Unavailable"},
      {"audioState", "External"},
      {"signalDbm", 0.0},
      {"forwardPowerW", 0.0},
      {"swr", 0.0},
      {"lastObservedMs", QDateTime::currentMSecsSinceEpoch()},
      {"droppedIqFrames", 0ULL},
      {"stale", false},
      {"error", QString{}}};
}
} // namespace

#ifdef SHACKCQ_HAVE_HAMLIB
int collectModel(const struct rig_caps *caps, void *data) {
  if (!caps || !data || caps->rig_model == RIG_MODEL_NONE)
    return 1;
  auto *models = static_cast<QVector<RadioModel> *>(data);
  models->push_back(
      {static_cast<int>(caps->rig_model), QString::fromUtf8(caps->mfg_name),
       QString::fromUtf8(caps->model_name),
       QStringLiteral("backend-%1").arg(RIG_BACKEND_NUM(caps->rig_model)),
       QString::fromLatin1(rig_strstatus(caps->status)),
       QStringLiteral("port-type-%1").arg(static_cast<int>(caps->port_type))});
  return 1;
}
#endif

HamlibModelRegistry::HamlibModelRegistry(QObject *parent)
    : QAbstractListModel(parent) {
  load();
}
void HamlibModelRegistry::load() {
#ifdef SHACKCQ_HAVE_HAMLIB
  rig_set_debug(RIG_DEBUG_NONE);
  rig_load_all_backends();
  rig_list_foreach(collectModel, &m_all);
#else
  m_all.push_back({1, "Hamlib", "Unavailable in this build", "not-linked",
                   "platform gap", "none"});
#endif
  std::sort(m_all.begin(), m_all.end(),
            [](const RadioModel &a, const RadioModel &b) {
              return std::tie(a.manufacturer, a.model, a.id) <
                     std::tie(b.manufacturer, b.model, b.id);
            });
  m_visible = m_all;
}
int HamlibModelRegistry::rowCount(const QModelIndex &p) const {
  return p.isValid() ? 0 : m_visible.size();
}
QVariant HamlibModelRegistry::data(const QModelIndex &i, int role) const {
  if (!i.isValid() || i.row() < 0 || i.row() >= m_visible.size())
    return {};
  const auto &m = m_visible.at(i.row());
  switch (role) {
  case IdRole:
    return m.id;
  case ManufacturerRole:
    return m.manufacturer;
  case ModelRole:
    return m.model;
  case BackendRole:
    return m.backend;
  case StatusRole:
    return m.status;
  case TransportRole:
    return m.transport;
  default:
    return {};
  }
}
QHash<int, QByteArray> HamlibModelRegistry::roleNames() const {
  return {{IdRole, "modelId"},    {ManufacturerRole, "manufacturer"},
          {ModelRole, "model"},   {BackendRole, "backend"},
          {StatusRole, "status"}, {TransportRole, "transport"}};
}
void HamlibModelRegistry::setSearch(const QString &search) {
  m_search = search.trimmed();
  beginResetModel();
  if (m_search.isEmpty())
    m_visible = m_all;
  else {
    m_visible.clear();
    std::copy_if(m_all.cbegin(), m_all.cend(), std::back_inserter(m_visible),
                 [this](const RadioModel &m) {
                   return (m.manufacturer + ' ' + m.model + ' ' + m.backend)
                       .contains(m_search, Qt::CaseInsensitive);
                 });
  }
  endResetModel();
  emit countChanged();
}

DesktopRadioController::DesktopRadioController(QObject *parent)
    : QObject(parent), m_hamlibHelper(this), m_tci(this), m_receivers(this) {
  m_poll.setInterval(250);
  connect(&m_poll, &QTimer::timeout, this, &DesktopRadioController::poll);
  connect(&m_nativeSerial, &QSerialPort::readyRead, this, [this] {
    consumeNative(m_nativeSerial.readAll());
  });
  connect(&m_nativeTcp, &QTcpSocket::readyRead, this, [this] {
    consumeNative(m_nativeTcp.readAll());
  });
  connect(&m_nativeTcp, &QTcpSocket::errorOccurred, this,
          [this](QAbstractSocket::SocketError) {
            if (m_backend != "native")
              return;
            m_lastError = m_nativeTcp.errorString().left(300);
            emit error(m_lastError);
          });
  connect(&m_tci, &TciClient::stateChanged, this,
          &DesktopRadioController::syncTci);
  connect(&m_tci, &TciClient::receiversChanged, this,
          &DesktopRadioController::syncTci);
  connect(&m_tci, &TciClient::error, this, [this](const QString &message) {
    m_lastError = message.left(300);
    emit error(m_lastError);
  });
  connect(&m_hamlibHelper, &HamlibHelperTransport::sanitizedError, this,
          [this](const QString &message) {
            m_lastError = message.left(300);
            emit error(m_lastError);
          });
  connect(&m_hamlibHelper, &HamlibHelperTransport::unsafeOwnershipLost, this,
          [this] {
            if (m_backend != "hamlib")
              return;
            const bool verified = m_hamlibHelper.priorityStop();
            m_transmitting = verified ? std::optional<bool>(false)
                                      : std::nullopt;
            m_state = verified
                          ? "Quarantined — helper lost; emergency RX verified"
                          : "Quarantined — helper lost; RX unconfirmed";
            emit unsafeRadioOwnershipLost(verified);
            emit snapshotChanged();
          });
  connect(&m_tci, &TciClient::iqFrame, this,
          [this](int rx, quint32 rate, QVector<float> values) {
            emit iqFrame(QStringLiteral("tci:%1").arg(rx), rate,
                         std::move(values));
          });
  connect(&m_tci, &TciClient::rxAudioFrame, this,
          [this](int rx, quint32 rate, QVector<float> values) {
            emit rxAudioFrame(QStringLiteral("tci:%1").arg(rx), rate,
                              std::move(values));
          });
}

DesktopRadioController::~DesktopRadioController() { disconnectRadio(); }

bool DesktopRadioController::connectRadio(int modelId, const QString &port,
                                          int baudRate) {
  disconnectRadio();
#ifdef SHACKCQ_HAVE_HAMLIB
  if (port.trimmed().isEmpty()) {
    emit error("An explicit serial or network route is required");
    return false;
  }
  QJsonObject description;
  if (!m_hamlibHelper.open(modelId, port, baudRate, &description)) {
    emit error("Hamlib helper failed to open and verify RX");
    return false;
  }
  m_hamlibModelId = modelId;
  m_generation++;
  m_backend = "hamlib";
  m_state = "Connected — receive controls only; PTT/TUNE disabled";
  m_digiPttSupported = description.value("pttSupported").toBool(false);
  m_model = description.value("model").toString(QString::number(modelId));
  m_manufacturer = description.value("manufacturer").toString("Hamlib");
  m_activeReceiverId = m_listeningReceiverId = m_transmitReceiverId =
      "hamlib:0";
  m_backendCapabilities = {{"receiverCount", 1},
                           {"iqStreaming", false},
                           {"rxAudioStreaming", false},
                           {"ptt", false},
                           {"tune", false}};
  if (const struct rig_caps *caps = rig_get_caps(modelId)) {
    QVariantList ranges, filters, modes, setters, meters;
    auto appendRanges = [&ranges](const freq_range_t *source) {
      for (int index = 0; index < HAMLIB_FRQRANGESIZ; ++index) {
        const auto &range = source[index];
        if (RIG_IS_FRNG_END(range))
          break;
        if (range.startf > 0 && range.endf >= range.startf)
          ranges << QVariantMap{
              {"min", QVariant::fromValue<qulonglong>(
                          static_cast<quint64>(range.startf))},
              {"max", QVariant::fromValue<qulonglong>(
                          static_cast<quint64>(range.endf))}};
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
        filters << int(filter.width);
      for (const auto &candidate : knownModes) {
        const QString name = QString::fromLatin1(candidate.name);
        if ((filter.modes & candidate.hamlib) && !modes.contains(name))
          modes << name;
      }
    }
    if (caps->set_freq && caps->get_freq)
      setters << "radio.set.frequency";
    if (caps->set_mode && caps->get_mode) {
      setters << "radio.set.mode" << "radio.set.filter";
      if (setters.contains("radio.set.frequency"))
        setters << "preset.recall";
    }
    if (caps->get_level) {
      if (caps->has_get_level & RIG_LEVEL_STRENGTH)
        meters << "signal";
      if (caps->has_get_level & RIG_LEVEL_SWR)
        meters << "swr";
      if (caps->has_get_level & RIG_LEVEL_ALC)
        meters << "alc";
    }
    m_backendCapabilities.insert("frequencyRangesHz", ranges);
    m_backendCapabilities.insert("modes", modes);
    m_backendCapabilities.insert("filtersHz", filters);
    m_backendCapabilities.insert("setters", setters);
    m_backendCapabilities.insert("meters", meters);
  }
  m_poll.start();
  poll();
  return true;
#else
  Q_UNUSED(modelId);
  Q_UNUSED(port);
  Q_UNUSED(baudRate);
  emit error("This build was compiled without pinned Hamlib 4.7.2");
  return false;
#endif
}

bool DesktopRadioController::saveHamlibProfile(int modelId,
                                               const QString &route,
                                               int baudRate,
                                               bool autoConnect) {
  const QString cleanRoute = route.trimmed();
  if (modelId < 1 || cleanRoute.isEmpty() || cleanRoute.size() > 512 ||
      cleanRoute.contains(QRegularExpression(QStringLiteral("[\\x00-\\x1f]"))) ||
      (baudRate != 0 && (baudRate < 1200 || baudRate > 921600))) {
    emit error("Invalid Hamlib model, route, or baud rate");
    return false;
  }
  HamlibModelRegistry models;
  const bool known = std::any_of(
      models.allModels().cbegin(), models.allModels().cend(),
      [modelId](const RadioModel &model) { return model.id == modelId; });
  if (!known) {
    emit error("Unknown Hamlib model id");
    return false;
  }
  m_hamlibProfile = {{"modelId", modelId},
                     {"route", cleanRoute},
                     {"baudRate", baudRate},
                     {"autoConnect", autoConnect}};
  emit preferencesChanged();
  return true;
}

void DesktopRadioController::clearHamlibProfile() {
  if (m_backend == "hamlib")
    disconnectRadio();
  m_hamlibProfile.clear();
  emit preferencesChanged();
}

bool DesktopRadioController::connectNativeProfile(const QString &profileId,
                                                  const QString &route,
                                                  int baudRate) {
  disconnectRadio();
  const QString id = profileId.trimmed().toUpper();
  const QSet<QString> supported{"KX3", "KX2", "FLEX", "QMX", "QMX+",
                                "RGO-V6"};
  if (!supported.contains(id) || route.trimmed().isEmpty()) {
    emit error(id == "RGO-UNKNOWN"
                   ? "Unknown RGO generation remains disconnected; framing is not guessed"
                   : "A supported native profile and explicit route are required");
    return false;
  }
  bool opened = false;
  const QUrl endpoint(route);
  if (endpoint.isValid() &&
      (endpoint.scheme() == "tcp" || endpoint.scheme() == "flex")) {
    const int port = endpoint.port();
    if (endpoint.host().isEmpty() || port < 1 || port > 65535) {
      emit error("Native TCP route must be tcp://host:port");
      return false;
    }
    m_nativeTcp.connectToHost(endpoint.host(), quint16(port));
    opened = m_nativeTcp.waitForConnected(1500);
    if (!opened)
      m_lastError = m_nativeTcp.errorString().left(300);
  } else {
    if (id == "FLEX") {
      emit error("FlexRadio native command channel requires tcp://host:port");
      return false;
    }
    m_nativeSerial.setPortName(route.trimmed());
    m_nativeSerial.setBaudRate(std::clamp(baudRate, 1200, 921600));
    m_nativeSerial.setDataBits(QSerialPort::Data8);
    m_nativeSerial.setParity(QSerialPort::NoParity);
    m_nativeSerial.setStopBits(QSerialPort::OneStop);
    m_nativeSerial.setFlowControl(QSerialPort::NoFlowControl);
    opened = m_nativeSerial.open(QIODevice::ReadWrite);
    if (!opened)
      m_lastError = m_nativeSerial.errorString().left(300);
  }
  if (!opened) {
    emit error(QStringLiteral("Native radio connect failed: %1").arg(m_lastError));
    disconnectRadio();
    return false;
  }
  m_backend = "native";
  m_nativeProfileId = id;
  m_model = id;
  m_generation++;
  m_activeReceiverId = m_listeningReceiverId = m_transmitReceiverId =
      "native:0";
  m_state = id == "RGO-V6"
                ? "Connecting — proving RGO ONE V6 identity"
                : "Connected — native receive/read controls; PTT/TUNE disabled";
  m_backendCapabilities = {{"receiverCount", 1},
                           {"iqStreaming", id == "FLEX"},
                           {"rxAudioStreaming", false},
                           {"readbackRequired", true},
                           {"ptt", false},
                           {"tune", false},
                           {"profile", id}};
  m_poll.start();
  pollNative();
  emit snapshotChanged();
  return true;
}

bool DesktopRadioController::connectTciProfile(const QString &profileId) {
  for (const QVariant &entry : m_tciProfiles) {
    bool ok{};
    const TciProfile profile = decodeTciProfile(entry.toMap(), &ok);
    if (ok && profile.id == profileId) {
      disconnectRadio();
      m_backend = "tci";
      m_model = profile.displayName;
      m_autoConnectProfileId = profile.autoConnect ? profile.id : QString{};
      emit snapshotChanged();
      return m_tci.connectProfile(profile);
    }
  }
  emit error("Unknown TCI profile");
  return false;
}

TciProfile DesktopRadioController::decodeTciProfile(const QVariantMap &value,
                                                    bool *ok) {
  TciProfile profile;
  profile.id = value.value("id").toString().trimmed();
  profile.displayName = value.value("displayName").toString().trimmed();
  profile.endpoint = QUrl(value.value("endpoint").toString());
  profile.preferredIqSampleRate =
      value.value("preferredIqSampleRate", 96000).toUInt();
  profile.preferredReceiver = value.value("preferredReceiver", 0).toInt();
  profile.autoConnect = value.value("autoConnect", false).toBool();
  profile.rxAudioOutputRoute = value.value("rxAudioOutputRoute").toString();
  const bool valid =
      !profile.id.isEmpty() && !profile.displayName.isEmpty() &&
      profile.endpoint.isValid() &&
      (profile.endpoint.scheme() == "ws" ||
       profile.endpoint.scheme() == "wss") &&
      !profile.endpoint.host().isEmpty() && profile.endpoint.port() > 0 &&
      profile.preferredIqSampleRate >= 8000 &&
      profile.preferredIqSampleRate <= 10000000 &&
      profile.preferredReceiver >= 0 && profile.preferredReceiver < 8;
  if (ok)
    *ok = valid;
  return profile;
}

QVariantMap DesktopRadioController::encodeTciProfile(const TciProfile &p) {
  return {{"id", p.id},
          {"displayName", p.displayName},
          {"endpoint", p.endpoint.toString()},
          {"preferredIqSampleRate", p.preferredIqSampleRate},
          {"preferredReceiver", p.preferredReceiver},
          {"autoConnect", p.autoConnect},
          {"rxAudioOutputRoute", p.rxAudioOutputRoute}};
}

bool DesktopRadioController::saveTciProfile(const QVariantMap &value) {
  bool ok{};
  const TciProfile profile = decodeTciProfile(value, &ok);
  if (!ok) {
    emit error("Invalid TCI profile");
    return false;
  }
  for (QVariant &entry : m_tciProfiles) {
    if (entry.toMap().value("id").toString() == profile.id) {
      entry = encodeTciProfile(profile);
      emit preferencesChanged();
      return true;
    }
  }
  if (m_tciProfiles.size() >= 32) {
    emit error("TCI profile capacity is 32");
    return false;
  }
  m_tciProfiles.push_back(encodeTciProfile(profile));
  emit preferencesChanged();
  return true;
}

bool DesktopRadioController::removeTciProfile(const QString &id) {
  for (int i = 0; i < m_tciProfiles.size(); ++i)
    if (m_tciProfiles.at(i).toMap().value("id").toString() == id) {
      m_tciProfiles.removeAt(i);
      if (m_autoConnectProfileId == id)
        m_autoConnectProfileId.clear();
      emit preferencesChanged();
      return true;
    }
  return false;
}

void DesktopRadioController::startConfiguredAutoConnect() {
  if (m_hamlibProfile.value("autoConnect", false).toBool()) {
    connectRadio(m_hamlibProfile.value("modelId").toInt(),
                 m_hamlibProfile.value("route").toString(),
                 m_hamlibProfile.value("baudRate").toInt());
  } else if (!m_autoConnectProfileId.isEmpty())
    connectTciProfile(m_autoConnectProfileId);
}

void DesktopRadioController::disconnectRadio() {
  emit aboutToDisconnect();
  m_poll.stop();
  m_tci.disconnectFromServer();
  if (m_nativeSerial.isOpen())
    m_nativeSerial.close();
  if (m_nativeTcp.state() != QAbstractSocket::UnconnectedState)
    m_nativeTcp.abort();
  m_nativeBuffer.clear();
  m_nativeProfileId.clear();
  m_hamlibHelper.close();
  m_generation++;
  m_backend = "none";
  m_state = "Disconnected";
  m_model.clear();
  m_manufacturer.clear();
  m_frequencyHz = 0;
  m_mode.clear();
  m_filterHz = 0;
  m_hamlibModelId = 1;
  m_activeReceiverId.clear();
  m_listeningReceiverId.clear();
  m_transmitReceiverId.clear();
  m_backendCapabilities.clear();
  m_meters.clear();
  m_transmitting.reset();
  m_digiPttSupported = false;
  m_receivers.clear();
  emit snapshotChanged();
}

void DesktopRadioController::syncSelection() {
  const QVariantList rows = m_receivers.snapshots();
  auto exists = [&rows](const QString &id) {
    for (const QVariant &e : rows)
      if (e.toMap().value("id").toString() == id)
        return true;
    return false;
  };
  if (rows.isEmpty()) {
    m_activeReceiverId.clear();
    m_listeningReceiverId.clear();
    m_transmitReceiverId.clear();
    return;
  }
  const QString first = rows.first().toMap().value("id").toString();
  if (!exists(m_activeReceiverId))
    m_activeReceiverId = first;
  if (!exists(m_listeningReceiverId))
    m_listeningReceiverId = first;
  const QString txCandidate =
      m_backend == "tci" ? QStringLiteral("tci:0") : first;
  m_transmitReceiverId = exists(txCandidate) ? txCandidate : first;
}

void DesktopRadioController::syncTci() {
  if (m_backend != "tci")
    return;
  m_state = m_tci.state();
  m_backendCapabilities = m_tci.capabilities();
  m_backendCapabilities["receiverCount"] = m_tci.receivers().size();
  m_backendCapabilities["iqStreaming"] = true;
  m_backendCapabilities["rxAudioStreaming"] = true;
  m_backendCapabilities["ptt"] = false;
  m_backendCapabilities["tune"] = false;
  QVariantList rows = m_tci.receivers();
  m_receivers.replace(rows, m_activeReceiverId, m_listeningReceiverId,
                      m_transmitReceiverId);
  syncSelection();
  syncTciAttachments();
  m_receivers.replace(rows, m_activeReceiverId, m_listeningReceiverId,
                      m_transmitReceiverId);
  const QVariantMap active = m_receivers.receiver(m_activeReceiverId);
  m_frequencyHz = active.value("effectiveReceiveHz").toULongLong();
  m_mode = active.value("mode").toString();
  emit snapshotChanged();
}
void DesktopRadioController::syncTciAttachments() {
  if (m_backend != "tci" || !m_tci.ready())
    return;
  QSet<int> desired;
  for (const QString &id : {m_activeReceiverId, m_listeningReceiverId})
    if (id.startsWith("tci:")) {
      bool ok{};
      const int receiver = id.sliced(4).toInt(&ok);
      if (ok && receiver >= 0 && receiver < receiverCount())
        desired.insert(receiver);
    }
  for (int receiver = 0; receiver < receiverCount(); ++receiver) {
    if (desired.contains(receiver))
      m_tci.attachReceiver(receiver);
    else
      m_tci.detachReceiver(receiver);
  }
}

bool DesktopRadioController::selectActiveReceiver(const QString &id) {
  if (m_receivers.receiver(id).isEmpty())
    return false;
  m_activeReceiverId = id;
  syncTci();
  if (m_backend == "hamlib") {
    m_receivers.replace(m_receivers.snapshots(), id, m_listeningReceiverId,
                        m_transmitReceiverId);
    emit snapshotChanged();
  }
  emit preferencesChanged();
  return true;
}
bool DesktopRadioController::selectListeningReceiver(const QString &id) {
  if (m_receivers.receiver(id).isEmpty())
    return false;
  m_listeningReceiverId = id;
  syncTci();
  if (m_backend == "hamlib") {
    m_receivers.replace(m_receivers.snapshots(), m_activeReceiverId, id,
                        m_transmitReceiverId);
    emit snapshotChanged();
  }
  emit preferencesChanged();
  return true;
}

int DesktopRadioController::activeTciIndex() const {
  if (!m_activeReceiverId.startsWith("tci:"))
    return -1;
  bool ok{};
  const int rx = m_activeReceiverId.sliced(4).toInt(&ok);
  return ok ? rx : -1;
}
bool DesktopRadioController::requestFrequency(qulonglong hz) {
  if (m_backend == "tci")
    return m_tci.requestFrequency(activeTciIndex(),
                                  m_receivers.receiver(m_activeReceiverId)
                                      .value("selectedChannel")
                                      .toInt(),
                                  hz);
  if (m_backend == "native") {
    if (!m_state.startsWith("Connected"))
      return false;
    const QByteArray setter = nativeFrame("setFrequency", hz);
    return !setter.isEmpty() && writeNative(setter);
  }
#ifdef SHACKCQ_HAVE_HAMLIB
  if (m_backend != "hamlib" || hz < 100000 || hz > 10500000000ULL)
    return false;
  const QJsonObject response = m_hamlibHelper.mutate(
      "radio.set.frequency", {{"frequencyHz", QJsonValue(double(hz))}});
  if (!response.value("ok").toBool())
    return false;
  m_frequencyHz = response.value("frequencyHz").toVariant().toULongLong();
  return m_frequencyHz == hz;
#else
  Q_UNUSED(hz);
  return false;
#endif
}
bool DesktopRadioController::requestMode(const QString &value) {
  if (m_backend == "tci")
    return m_tci.requestMode(activeTciIndex(), value);
  if (m_backend == "native") {
    if (!m_state.startsWith("Connected"))
      return false;
    const QByteArray setter = nativeFrame("setMode", value);
    return !setter.isEmpty() && writeNative(setter);
  }
#ifdef SHACKCQ_HAVE_HAMLIB
  if (m_backend != "hamlib")
    return false;
  const rmode_t parsed = hamlibModeForCloud(value);
  if (parsed == RIG_MODE_NONE)
    return false;
  const QJsonObject response =
      m_hamlibHelper.mutate("radio.set.mode", {{"mode", value}});
  if (!response.value("ok").toBool())
    return false;
  m_mode = response.value("mode").toString();
  m_filterHz = response.value("filterHz").toInt();
  return m_mode.compare(value, Qt::CaseInsensitive) == 0;
#else
  Q_UNUSED(value);
  return false;
#endif
}
bool DesktopRadioController::requestFilter(int filterHz) {
  if (m_backend != "hamlib" || filterHz < 50 || filterHz > 20'000)
    return false;
#ifdef SHACKCQ_HAVE_HAMLIB
  if (m_backend != "hamlib")
    return false;
  const QJsonObject response =
      m_hamlibHelper.mutate("radio.set.filter", {{"filterHz", filterHz}});
  if (!response.value("ok").toBool())
    return false;
  m_mode = response.value("mode").toString();
  m_filterHz = response.value("filterHz").toInt();
  return m_filterHz == filterHz;
#else
  Q_UNUSED(filterHz);
  return false;
#endif
}

void DesktopRadioController::poll() {
  if (m_backend == "native") {
    pollNative();
    return;
  }
#ifdef SHACKCQ_HAVE_HAMLIB
  if (m_backend != "hamlib" || m_hamlibHelper.quarantined() ||
      m_hamlibHelper.operationActive())
    return;
  // A physical serial snapshot may take longer than the timer interval. Stop
  // the repeating timer while it is in flight, then schedule the next poll
  // after this read completes so overdue timer events cannot starve the local
  // administration socket.
  m_poll.stop();
  const QJsonObject observed = m_hamlibHelper.snapshot();
  m_poll.start();
  if (!observed.value("ok").toBool())
    return;
  m_frequencyHz = observed.value("frequencyHz").toVariant().toULongLong();
  m_mode = observed.value("mode").toString();
  m_filterHz = observed.value("filterHz").toInt();
  m_meters = observed.value("meters").toObject().toVariantMap();
  m_transmitting = observed.value("transmitting").isBool()
                       ? std::optional<bool>(observed.value("transmitting").toBool())
                       : std::nullopt;
  QVariantList rows{hamlibSnapshot(m_model, m_frequencyHz, m_mode)};
  m_receivers.replace(rows, m_activeReceiverId, m_listeningReceiverId,
                      m_transmitReceiverId);
  emit snapshotChanged();
#endif
}

QByteArray DesktopRadioController::nativeFrame(const QString &operation,
                                               const QVariant &value) const {
  const QString op = operation.trimmed().toLower();
  if (m_nativeProfileId == "FLEX") {
#ifdef SHACKCQ_HAVE_NATIVE_DIGI
    std::array<char, 256> output{};
    int count = -1;
    if (op == "keepalive")
      count = shackcq_flex_keepalive(output.data(), output.size());
    else if (op == "setfrequency")
      count = shackcq_flex_frequency(0, value.toULongLong(), output.data(),
                                output.size());
    else if (op == "setmode")
      count = shackcq_flex_mode(0, value.toString().toUtf8().constData(),
                           output.data(), output.size());
    return count > 0 ? QByteArray(output.data(), count) : QByteArray{};
#else
    return {};
#endif
  }
  if (op == "identity")
    return m_nativeProfileId == "RGO-V6" ? QByteArray("ID;") : QByteArray{};
  if (op == "frequency")
    return "FA;";
  if (op == "mode")
    return "MD;";
  if (op == "setfrequency") {
    const quint64 hz = value.toULongLong();
    if (hz < 100000 || hz > 60000000)
      return {};
    return QStringLiteral("FA%1;")
        .arg(hz, 11, 10, QLatin1Char('0'))
        .toLatin1();
  }
  if (op == "setmode") {
    static const QHash<QString, char> modes{{"LSB", '1'}, {"USB", '2'},
                                            {"CW", '3'},  {"FM", '4'},
                                            {"AM", '5'},  {"DIGU", '6'},
                                            {"CWR", '7'}, {"DIGL", '9'}};
    const auto it = modes.constFind(value.toString().trimmed().toUpper());
    return it == modes.cend() ? QByteArray{}
                              : QByteArray("MD") + QByteArray(1, it.value()) + ";";
  }
  return {};
}

bool DesktopRadioController::writeNative(const QByteArray &frame) {
  if (frame.isEmpty() || frame.size() > 256 ||
      frame.contains("TX") || frame.contains("RX") || frame.contains("TQ"))
    return false;
  if (m_nativeSerial.isOpen())
    return m_nativeSerial.write(frame) == frame.size();
  if (m_nativeTcp.state() == QAbstractSocket::ConnectedState)
    return m_nativeTcp.write(frame) == frame.size();
  return false;
}

void DesktopRadioController::pollNative() {
  if (m_nativeProfileId.isEmpty())
    return;
  if (m_nativeProfileId == "FLEX") {
    writeNative(nativeFrame("keepalive"));
    return;
  }
  if (m_nativeProfileId == "RGO-V6" && !m_state.startsWith("Connected")) {
    writeNative(nativeFrame("identity"));
    return;
  }
  writeNative(nativeFrame("frequency"));
  writeNative(nativeFrame("mode"));
}

void DesktopRadioController::consumeNative(const QByteArray &bytes) {
  for (const char byte : bytes) {
    const uchar value = uchar(byte);
    if (value < 0x20 || value > 0x7e)
      continue;
    if (m_nativeBuffer.size() >= 4096) {
      m_nativeBuffer.clear();
      m_lastError = "Native radio response exceeded 4096-byte bound";
      emit error(m_lastError);
      return;
    }
    m_nativeBuffer.append(byte);
    if (byte != ';' && byte != '\n')
      continue;
    const QByteArray frame = m_nativeBuffer.trimmed();
    m_nativeBuffer.clear();
    if (frame.size() > 128)
      continue;
    if (m_nativeProfileId == "RGO-V6" && frame == "ID006;") {
      m_state = "Connected — proven RGO ONE V6 receive/read controls; PTT/TUNE disabled";
    }
    const QRegularExpression frequency(QStringLiteral("^FA(\\d{11});$"));
    const auto frequencyMatch =
        frequency.match(QString::fromLatin1(frame));
    if (frequencyMatch.hasMatch())
      m_frequencyHz = frequencyMatch.captured(1).toULongLong();
    const QRegularExpression mode(QStringLiteral("^MD([1-9]);$"));
    const auto modeMatch = mode.match(QString::fromLatin1(frame));
    if (modeMatch.hasMatch()) {
      static const QHash<QChar, QString> modes{{'1', "LSB"}, {'2', "USB"},
                                               {'3', "CW"},  {'4', "FM"},
                                               {'5', "AM"},  {'6', "DIGU"},
                                               {'7', "CWR"}, {'9', "DIGL"}};
      m_mode = modes.value(modeMatch.captured(1).at(0));
    }
    const QVariantMap row = hamlibSnapshot(m_model, m_frequencyHz, m_mode);
    m_receivers.replace({row}, m_activeReceiverId, m_listeningReceiverId,
                        m_transmitReceiverId);
    emit snapshotChanged();
  }
}

QVariantMap DesktopRadioController::configuration() const {
  QVariantMap result = m_legacyConfiguration;
  result["schemaVersion"] = RadioProfilesSchema;
  result["activeReceiverId"] = m_activeReceiverId;
  result["listeningReceiverId"] = m_listeningReceiverId;
  result["autoConnectProfileId"] = m_autoConnectProfileId;
  result["hamlibProfile"] = m_hamlibProfile;
  result["tciProfiles"] = m_tciProfiles;
  result["safeView"] = m_safeView;
  return result;
}

bool DesktopRadioController::restoreConfiguration(const QVariantMap &input,
                                                  QString *error) {
  QVariantMap section = input;
  int schema = section.value("schemaVersion", 0).toInt();
  if (schema > RadioProfilesSchema) {
    if (error)
      *error = QStringLiteral(
                   "radioProfiles schema %1 is newer than supported schema %2")
                   .arg(schema)
                   .arg(RadioProfilesSchema);
    return false;
  }
  m_legacyConfiguration = section;
  if (schema == 1) {
    QVariantList migrated;
    for (const QVariant &e : section.value("tciProfiles").toList()) {
      QVariantMap p = e.toMap();
      if (!p.contains("endpoint")) {
        const QString host = p.take("host").toString();
        const int port = p.take("port").toInt();
        p["endpoint"] = QStringLiteral("ws://%1:%2").arg(host).arg(port);
      }
      migrated.push_back(p);
    }
    section["tciProfiles"] = migrated;
  }
  if (section.value("tciProfiles").toList().size() > 32) {
    if (error)
      *error = "Persisted TCI profile count exceeds 32";
    return false;
  }
  m_activeReceiverId = section.value("activeReceiverId").toString();
  m_listeningReceiverId = section.value("listeningReceiverId").toString();
  m_safeView = section.value("safeView", m_safeView).toMap();
  m_hamlibProfile.clear();
  const QVariantMap hamlibProfile = section.value("hamlibProfile").toMap();
  if (!hamlibProfile.isEmpty()) {
    const int modelId = hamlibProfile.value("modelId").toInt();
    const QString route = hamlibProfile.value("route").toString().trimmed();
    const int baudRate = hamlibProfile.value("baudRate").toInt();
    if (modelId < 1 || route.isEmpty() || route.size() > 512 ||
        route.contains(QRegularExpression(QStringLiteral("[\\x00-\\x1f]"))) ||
        (baudRate != 0 && (baudRate < 1200 || baudRate > 921600))) {
      if (error)
        *error = "Invalid persisted Hamlib profile";
      return false;
    }
    m_hamlibProfile = {{"modelId", modelId},
                       {"route", route},
                       {"baudRate", baudRate},
                       {"autoConnect", hamlibProfile.value("autoConnect", true).toBool()}};
  }
  m_tciProfiles.clear();
  m_autoConnectProfileId = section.value("autoConnectProfileId").toString();
  for (const QVariant &e : section.value("tciProfiles").toList()) {
    bool ok{};
    TciProfile p = decodeTciProfile(e.toMap(), &ok);
    if (!ok) {
      if (error)
        *error = "Invalid persisted TCI profile";
      m_tciProfiles.clear();
      return false;
    }
    m_tciProfiles.push_back(encodeTciProfile(p));
    if (p.autoConnect && m_autoConnectProfileId.isEmpty())
      m_autoConnectProfileId = p.id;
  }
  return true;
}

QVariantMap DesktopRadioController::health() const {
  return {{"state", m_state},
          {"backend", m_backend},
          {"receiverCount", receiverCount()},
          {"activeReceiverId", m_activeReceiverId},
          {"listeningReceiverId", m_listeningReceiverId},
          {"transmitReceiverId", m_transmitReceiverId},
          {"pttAvailable", false},
          {"tuneAvailable", false},
          {"capabilities", m_backendCapabilities},
          {"meters", m_meters},
          {"transmitting",
           m_transmitting ? QVariant(*m_transmitting) : QVariant()},
          {"lastSanitizedError", m_lastError},
          {"hamlibProfileConfigured", !m_hamlibProfile.isEmpty()},
          {"hamlibProfileModelId", m_hamlibProfile.value("modelId")},
          {"tci", m_tci.diagnostics()}};
}
bool DesktopRadioController::requestDigiPtt(bool enabled) {
#ifdef SHACKCQ_HAVE_HAMLIB
  if (m_backend != "hamlib" || !m_digiPttSupported)
    return false;
  const QJsonObject response = m_hamlibHelper.setPtt(enabled);
  if (!response.value("ok").toBool() ||
      !response.value("transmitting").isBool())
    return false;
  m_transmitting = response.value("transmitting").toBool();
  emit snapshotChanged();
  return *m_transmitting == enabled;
#else
  Q_UNUSED(enabled);
  return false;
#endif
}

std::optional<bool> DesktopRadioController::digiPttReadback() const {
#ifdef SHACKCQ_HAVE_HAMLIB
  if (m_backend != "hamlib" || !m_digiPttSupported)
    return std::nullopt;
  const QJsonObject observed = m_hamlibHelper.snapshot();
  return observed.value("ok").toBool() &&
                 observed.value("transmitting").isBool()
             ? std::optional<bool>(observed.value("transmitting").toBool())
             : std::nullopt;
#else
  return std::nullopt;
#endif
}

bool DesktopRadioController::globalStop() {
  m_tci.globalStop();
  if (m_backend == "hamlib") {
    const bool verified = m_hamlibHelper.priorityStop();
    m_transmitting = verified ? std::optional<bool>(false) : std::nullopt;
    m_state = verified
                  ? "Quarantined — STOPPED, RX verified; reconnect required"
                  : "Quarantined — STOP requested; RX readback unconfirmed";
    emit snapshotChanged();
    return verified;
  }
  return true;
}
void DesktopRadioController::setTciTimeoutsForTest(int a, int b, int c) {
  m_tci.setTimeoutsForTest(a, b, c);
}
void DesktopRadioController::setHamlibSnapshotForTest(quint64 frequency,
                                                      const QString &mode) {
  disconnectRadio();
  m_backend = "hamlib";
  m_state = "Connected — fixture receive controls only; PTT/TUNE disabled";
  m_model = "Hamlib fixture";
  m_manufacturer = "Hamlib";
  m_frequencyHz = frequency;
  m_mode = mode;
  m_filterHz = 400;
  m_hamlibModelId = 1;
  m_activeReceiverId = m_listeningReceiverId = m_transmitReceiverId =
      "hamlib:0";
  m_backendCapabilities = {{"receiverCount", 1},
                           {"iqStreaming", false},
                           {"rxAudioStreaming", false},
                           {"ptt", false},
                           {"tune", false}};
  m_receivers.replace({hamlibSnapshot(m_model, m_frequencyHz, m_mode)},
                      m_activeReceiverId, m_listeningReceiverId,
                      m_transmitReceiverId);
  emit snapshotChanged();
}

} // namespace shackcq::desktop
