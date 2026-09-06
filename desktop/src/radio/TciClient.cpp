// SPDX-License-Identifier: GPL-3.0-only
#include "shackcq/desktop/TciClient.hpp"

#include "shackcq/tci.hpp"

#include <QAbstractSocket>
#include <QDateTime>
#include <QMetaObject>
#include <QSslError>
#include <QStringList>
#include <QThread>

namespace shackcq::desktop {
namespace {

QStringList arguments(const std::string &value) {
  return QString::fromStdString(value).split(',', Qt::KeepEmptyParts);
}

bool integer(const QString &value, qlonglong *output) {
  bool ok{};
  const qlonglong parsed = value.trimmed().toLongLong(&ok);
  if (ok && output != nullptr)
    *output = parsed;
  return ok;
}

bool boolean(const QString &value, bool *output) {
  const QString normalized = value.trimmed().toLower();
  if (normalized == "true" || normalized == "1") {
    if (output != nullptr)
      *output = true;
    return true;
  }
  if (normalized == "false" || normalized == "0") {
    if (output != nullptr)
      *output = false;
    return true;
  }
  return false;
}

QVariantMap receiverTemplate(int receiver) {
  return {{"id", QStringLiteral("tci:%1").arg(receiver)},
          {"label", QStringLiteral("Receiver %1").arg(receiver + 1)},
          {"backendIndex", receiver},
          {"enabled", false},
          {"muted", false},
          {"centreFrequencyHz", 0ULL},
          {"vfoAHz", 0ULL},
          {"vfoBHz", 0ULL},
          {"selectedChannel", 0},
          {"ifOffsetHz", 0LL},
          {"effectiveReceiveHz", 0ULL},
          {"mode", QString{}},
          {"sampleRate", 0U},
          {"iqState", "Stopped"},
          {"audioState", "Stopped"},
          {"forwardPowerW", 0.0},
          {"swr", 0.0},
          {"transmitting", false},
          {"tuning", false},
          {"lastObservedMs", 0LL},
          {"droppedIqFrames", 0ULL},
          {"stale", false},
          {"error", QString{}}};
}

} // namespace

TciClient::TciClient(QObject *parent) : QObject(parent) {
  m_decodePool.setMaxThreadCount(1);
  m_decodePool.setExpiryTimeout(-1);
  m_socket.setMaxAllowedIncomingFrameSize(8U * 1024U * 1024U);
  m_socket.setMaxAllowedIncomingMessageSize(8U * 1024U * 1024U);
  m_connectionTimer.setSingleShot(true);
  m_connectionTimer.setInterval(4'000);
  m_readyTimer.setSingleShot(true);
  m_readyTimer.setInterval(5'000);
  m_reconnectTimer.setSingleShot(true);
  m_reconnectTimer.setInterval(500);
  m_mutationTimer.setSingleShot(true);
  m_mutationTimer.setInterval(35);

  connect(&m_socket, &QWebSocket::connected, this, &TciClient::handleConnected);
  connect(&m_socket, &QWebSocket::disconnected, this,
          &TciClient::handleDisconnected);
  connect(&m_socket, &QWebSocket::textMessageReceived, this,
          &TciClient::handleText);
  connect(&m_socket, &QWebSocket::binaryMessageReceived, this,
          &TciClient::handleBinary);
  connect(&m_socket, &QWebSocket::errorOccurred, this,
          [this](QAbstractSocket::SocketError) {
            if (!m_explicitDisconnect)
              emit error(m_socket.errorString().left(300));
          });
  connect(&m_socket, &QWebSocket::sslErrors, this,
          [this](const QList<QSslError> &errors) {
            if (!errors.isEmpty())
              emit error(QStringLiteral("TCI TLS validation failed: %1")
                             .arg(errors.first().errorString()));
            m_socket.abort();
          });
  connect(&m_connectionTimer, &QTimer::timeout, this, [this] {
    if (m_state == "Connecting") {
      setState("Error — WebSocket connection timeout");
      m_socket.abort();
    }
  });
  connect(&m_readyTimer, &QTimer::timeout, this, [this] {
    if (!m_ready) {
      setState("Error — TCI ready/status timeout");
      m_socket.abort();
    }
  });
  connect(&m_reconnectTimer, &QTimer::timeout, this, &TciClient::openSocket);
  connect(&m_mutationTimer, &QTimer::timeout, this, &TciClient::flushMutations);
}

TciClient::~TciClient() {
  m_explicitDisconnect = true;
  ++m_generation;
  m_socket.abort();
  m_decodePool.waitForDone();
}

QVariantList TciClient::receivers() const { return m_receivers; }

QVariantMap TciClient::diagnostics() const {
  return {
      {"state", m_state},
      {"protocolVersion", m_protocolVersion},
      {"deviceIdentity", m_device},
      {"lastUpdateMs", m_lastUpdateMs},
      {"receivers", m_receivers},
      {"rxAudioUnderflows", 0},
      {"rxAudioOverflows", 0},
      {"unknownCommands", QVariant::fromValue<qulonglong>(m_unknownCommands)},
      {"malformedCommands",
       QVariant::fromValue<qulonglong>(m_malformedCommands)},
      {"malformedBinary", QVariant::fromValue<qulonglong>(m_malformedBinary)},
      {"droppedFrames", QVariant::fromValue<qulonglong>(m_droppedFrames)},
      {"binaryDecodeWorker", true},
      {"binaryDecodedOffOwnerThread", m_binaryDecodedOffOwnerThread.load()},
      {"binaryQueueDepth", m_pendingBinary.load()},
      {"binaryQueueCapacity", MaxPendingBinary},
      {"reconnectAttempts", m_reconnectAttempts},
      {"attachedReceivers", m_attachedReceivers.size()}};
}

bool TciClient::connectProfile(const TciProfile &profile) {
  if (profile.id.trimmed().isEmpty() ||
      profile.displayName.trimmed().isEmpty() || !profile.endpoint.isValid() ||
      (profile.endpoint.scheme() != "ws" &&
       profile.endpoint.scheme() != "wss") ||
      profile.endpoint.host().trimmed().isEmpty() ||
      profile.endpoint.port() < 1 || profile.preferredIqSampleRate < 8'000U ||
      profile.preferredIqSampleRate > 10'000'000U ||
      profile.preferredReceiver < 0 || profile.preferredReceiver >= 8) {
    emit error("TCI profile requires ID, name, ws/wss endpoint, valid port, "
               "sample rate, and receiver");
    return false;
  }
  disconnectFromServer();
  m_receivers.clear();
  emit receiversChanged();
  m_profile = profile;
  m_explicitDisconnect = false;
  m_reconnectAttempts = 0;
  openSocket();
  return true;
}

void TciClient::openSocket() {
  if (m_explicitDisconnect)
    return;
  clearSessionState();
  ++m_generation;
  setState("Connecting");
  m_connectionTimer.start();
  m_socket.open(m_profile.endpoint);
}

void TciClient::handleConnected() {
  m_connectionTimer.stop();
  setState("Handshaking — receive only");
  m_readyTimer.start();
}

void TciClient::handleDisconnected() {
  m_connectionTimer.stop();
  m_readyTimer.stop();
  m_mutationTimer.stop();
  m_pendingMutations.clear();
  m_attachedReceivers.clear();
  m_ready = false;
  for (QVariant &entry : m_receivers) {
    QVariantMap snapshot = entry.toMap();
    snapshot["stale"] = true;
    snapshot["iqState"] = "Stopped";
    snapshot["audioState"] = "Stopped";
    entry = snapshot;
  }
  emit receiversChanged();
  if (m_explicitDisconnect) {
    setState("Disconnected");
    return;
  }
  setState("Disconnected — reconnect bounded");
  scheduleReconnect();
}

void TciClient::scheduleReconnect() {
  if (m_explicitDisconnect || m_reconnectAttempts >= m_maxReconnectAttempts) {
    if (!m_explicitDisconnect)
      setState("Error — reconnect limit reached");
    return;
  }
  ++m_reconnectAttempts;
  m_reconnectTimer.setInterval(
      qMin(2'000, 250 * (1 << (m_reconnectAttempts - 1))));
  m_reconnectTimer.start();
}

void TciClient::disconnectFromServer() {
  m_explicitDisconnect = true;
  ++m_generation;
  m_connectionTimer.stop();
  m_readyTimer.stop();
  m_reconnectTimer.stop();
  m_mutationTimer.stop();
  m_pendingMutations.clear();
  if (m_ready) {
    const auto attached = m_attachedReceivers;
    for (const int receiver : attached)
      detachReceiver(receiver);
  }
  m_attachedReceivers.clear();
  m_ready = false;
  if (m_socket.state() != QAbstractSocket::UnconnectedState)
    m_socket.close();
  else
    setState("Disconnected");
}

void TciClient::clearSessionState() {
  m_ready = false;
  m_device.clear();
  m_protocolVersion.clear();
  m_capabilities.clear();
  m_startSeen = false;
  for (QVariant &entry : m_receivers) {
    QVariantMap snapshot = entry.toMap();
    snapshot["stale"] = true;
    snapshot["iqState"] = "Stopped";
    snapshot["audioState"] = "Stopped";
    entry = snapshot;
  }
  m_attachedReceivers.clear();
  m_pendingMutations.clear();
  emit receiversChanged();
}

void TciClient::setState(QString state) {
  if (m_state == state)
    return;
  m_state = std::move(state);
  emit stateChanged();
}

void TciClient::handleText(const QString &message) {
  m_lastUpdateMs = QDateTime::currentMSecsSinceEpoch();
  const auto commands = shackcq::tci::parse_status(message.toStdString());
  if (commands.empty() && !message.trimmed().isEmpty())
    markMalformed("frame");
  for (const auto &command : commands)
    handleStatus(command.name, command.arguments);
}

void TciClient::setReceiverCount(int count) {
  if (count < 1 || count > 8) {
    markMalformed("trx_count");
    return;
  }
  while (m_receivers.size() < count)
    m_receivers.push_back(receiverTemplate(m_receivers.size()));
  while (m_receivers.size() > count) {
    m_attachedReceivers.remove(m_receivers.size() - 1);
    m_receivers.removeLast();
  }
  emit receiversChanged();
}

bool TciClient::validReceiver(int receiver) const {
  return receiver >= 0 && receiver < m_receivers.size();
}

void TciClient::handleStatus(const std::string &name,
                             const std::string &rawArguments) {
  const QString command = QString::fromStdString(name);
  const QStringList fields = arguments(rawArguments);
  if (command == "protocol") {
    m_protocolVersion = QString::fromStdString(rawArguments).left(120);
    if (m_protocolVersion.trimmed().isEmpty())
      markMalformed(command);
  } else if (command == "device") {
    m_device = QString::fromStdString(rawArguments).left(120);
    if (m_device.trimmed().isEmpty())
      markMalformed(command);
  } else if (command == "trx_count") {
    qlonglong count{};
    if (!integer(fields.value(0), &count))
      markMalformed(command);
    else
      setReceiverCount(static_cast<int>(count));
  } else if (command == "start") {
    m_startSeen = true;
  } else if (command == "ready") {
    if (!m_startSeen || m_protocolVersion.isEmpty() || m_device.isEmpty() ||
        m_receivers.isEmpty()) {
      markMalformed("ready without complete start/capabilities");
      return;
    }
    m_readyTimer.stop();
    m_ready = true;
    m_reconnectAttempts = 0;
    for (QVariant &entry : m_receivers) {
      QVariantMap snapshot = entry.toMap();
      snapshot["stale"] = false;
      entry = snapshot;
    }
    emit receiversChanged();
    setState("Connected — TCI receive only; PTT/TUNE disabled");
    attachReceiver(
        qBound(0, m_profile.preferredReceiver, m_receivers.size() - 1));
  } else if (command == "vfo" || command == "dds" || command == "if" ||
             command == "modulation" || command == "rx_enable" ||
             command == "mute" || command == "iq_start" ||
             command == "iq_stop" || command == "audio_start" ||
             command == "audio_stop" || command == "trx" || command == "tune") {
    qlonglong receiver{};
    if (!integer(fields.value(0), &receiver) ||
        !validReceiver(static_cast<int>(receiver))) {
      markMalformed(command);
      return;
    }
    QVariantMap snapshot = m_receivers.at(static_cast<int>(receiver)).toMap();
    snapshot["lastObservedMs"] = QDateTime::currentMSecsSinceEpoch();
    bool ok = true;
    if (command == "vfo") {
      qlonglong channel{}, frequency{};
      ok = integer(fields.value(1), &channel) &&
           integer(fields.value(2), &frequency) && channel >= 0 &&
           channel <= 1 && frequency >= 100'000;
      if (ok) {
        snapshot[channel == 0 ? "vfoAHz" : "vfoBHz"] =
            QVariant::fromValue<qulonglong>(frequency);
        snapshot["selectedChannel"] = static_cast<int>(channel);
        if (channel == 0)
          snapshot["effectiveReceiveHz"] =
              QVariant::fromValue<qulonglong>(frequency);
        m_pendingMutations.remove(QStringLiteral("vfo:%1").arg(receiver));
      }
    } else if (command == "dds") {
      qlonglong frequency{};
      ok = integer(fields.value(1), &frequency) && frequency >= 100'000;
      if (ok)
        snapshot["centreFrequencyHz"] =
            QVariant::fromValue<qulonglong>(frequency);
    } else if (command == "if") {
      qlonglong channel{}, offset{};
      ok = integer(fields.value(1), &channel) &&
           integer(fields.value(2), &offset);
      if (ok)
        snapshot["ifOffsetHz"] = offset;
    } else if (command == "modulation") {
      const auto mode =
          shackcq::tci::canonical_mode(fields.value(1).toStdString());
      ok = mode.has_value();
      if (ok) {
        snapshot["mode"] = QString::fromStdString(*mode).toUpper();
        m_pendingMutations.remove(QStringLiteral("mode:%1").arg(receiver));
      }
    } else if (command == "rx_enable" || command == "mute" ||
               command == "trx" || command == "tune") {
      bool value{};
      ok = boolean(fields.value(1), &value);
      if (ok) {
        if (command == "rx_enable")
          snapshot["enabled"] = value;
        else if (command == "mute")
          snapshot["muted"] = value;
        else if (command == "trx")
          snapshot["transmitting"] = value;
        else
          snapshot["tuning"] = value;
      }
    } else {
      const bool running = command.endsWith("start");
      snapshot[command.startsWith("iq_") ? "iqState" : "audioState"] =
          running ? "Running" : "Stopped";
    }
    if (!ok)
      markMalformed(command);
    else {
      m_receivers[static_cast<int>(receiver)] = snapshot;
      emit receiversChanged();
    }
  } else if (command == "iq_samplerate") {
    qlonglong rate{};
    if (!integer(fields.value(0), &rate) || rate < 8'000 || rate > 10'000'000)
      markMalformed(command);
  } else if (command == "channels_count" || command == "vfo_limits" ||
             command == "if_limits" || command == "modulations_list" ||
             command == "iq_samplerates" || command == "audio_samplerates" ||
             command == "drive" || command == "tune_drive" ||
             command == "tx_power" || command == "tx_swr") {
    m_capabilities.insert(command,
                          QString::fromStdString(rawArguments).left(500));
  } else {
    ++m_unknownCommands;
  }
}

void TciClient::markMalformed(const QString &command) {
  ++m_malformedCommands;
  emit error(QStringLiteral("Malformed TCI status: %1").arg(command.left(120)));
}

void TciClient::handleBinary(const QByteArray &message) {
  m_lastUpdateMs = QDateTime::currentMSecsSinceEpoch();
  if (m_pendingBinary.load() >= MaxPendingBinary) {
    ++m_droppedFrames;
    return;
  }
  ++m_pendingBinary;
  const QByteArray payload = message;
  const quint64 generation = m_generation;
  const std::uint32_t receiverCount =
      static_cast<std::uint32_t>(qMax(1, m_receivers.size()));
  m_decodePool.start([this, payload, generation, receiverCount] {
    m_binaryDecodedOffOwnerThread = QThread::currentThread() != thread();
    shackcq::tci::BinaryError parseError{};
    const auto frame = shackcq::tci::decode_binary(
        reinterpret_cast<const std::uint8_t *>(payload.constData()),
        static_cast<std::size_t>(payload.size()), &parseError, receiverCount);
    int receiver = -1;
    quint32 sampleRate = 0;
    int dataType = -1;
    QVector<float> values;
    if (frame) {
      receiver = static_cast<int>(frame->header.receiver);
      sampleRate = frame->header.sample_rate;
      dataType = static_cast<int>(frame->header.data_type);
      values = QVector<float>(frame->values.cbegin(), frame->values.cend());
    }
    QMetaObject::invokeMethod(
        this,
        [this, receiver, sampleRate, dataType, values = std::move(values),
         parseError = static_cast<int>(parseError), generation]() mutable {
          --m_pendingBinary;
          deliverBinary(receiver, sampleRate, dataType, std::move(values),
                        parseError, generation);
        },
        Qt::QueuedConnection);
  });
}

void TciClient::deliverBinary(int receiver, quint32 sampleRate, int dataType,
                              QVector<float> values, int parseError,
                              quint64 generation) {
  if (generation != m_generation) {
    ++m_droppedFrames;
    return;
  }
  if (receiver < 0) {
    ++m_malformedBinary;
    emit error(QStringLiteral("Rejected malformed TCI binary frame (%1)")
                   .arg(static_cast<int>(parseError)));
    return;
  }
  if (!m_attachedReceivers.contains(receiver)) {
    ++m_droppedFrames;
    if (validReceiver(receiver)) {
      QVariantMap snapshot = m_receivers.at(receiver).toMap();
      snapshot["droppedIqFrames"] =
          snapshot.value("droppedIqFrames").toULongLong() + 1U;
      m_receivers[receiver] = snapshot;
      emit receiversChanged();
    }
    return;
  }
  if (dataType == static_cast<int>(shackcq::tci::DataType::Iq)) {
    emit iqFrame(receiver, sampleRate, std::move(values));
  } else if (dataType == static_cast<int>(shackcq::tci::DataType::RxAudio)) {
    emit rxAudioFrame(receiver, sampleRate, std::move(values));
  }
}

bool TciClient::attachReceiver(int receiver) {
  if (!m_ready || !validReceiver(receiver) ||
      m_attachedReceivers.contains(receiver))
    return false;
  m_attachedReceivers.insert(receiver);
  send(QString::fromStdString(
      *shackcq::tci::build_iq_sample_rate(m_profile.preferredIqSampleRate)));
  send(QString::fromStdString(
      *shackcq::tci::build_iq_start(static_cast<std::uint32_t>(receiver))));
  return true;
}

bool TciClient::detachReceiver(int receiver) {
  if (!m_ready || !m_attachedReceivers.remove(receiver))
    return false;
  send(QString::fromStdString(
      *shackcq::tci::build_iq_stop(static_cast<std::uint32_t>(receiver))));
  return true;
}

bool TciClient::requestFrequency(int receiver, int channel,
                                 quint64 frequencyHz) {
  const auto command = shackcq::tci::build_vfo(
      static_cast<std::uint32_t>(receiver), static_cast<std::uint32_t>(channel),
      frequencyHz);
  if (!m_ready || !validReceiver(receiver) || !command)
    return false;
  queueMutation(QStringLiteral("vfo:%1").arg(receiver),
                QString::fromStdString(*command));
  return true;
}

bool TciClient::requestMode(int receiver, const QString &mode) {
  const auto command = shackcq::tci::build_mode(
      static_cast<std::uint32_t>(receiver), mode.toStdString());
  if (!m_ready || !validReceiver(receiver) || !command)
    return false;
  queueMutation(QStringLiteral("mode:%1").arg(receiver),
                QString::fromStdString(*command));
  return true;
}

void TciClient::queueMutation(const QString &key, const QString &command) {
  m_pendingMutations.insert(key, command);
  m_mutationTimer.start();
}

void TciClient::flushMutations() {
  if (!m_ready) {
    m_pendingMutations.clear();
    return;
  }
  const auto pending = m_pendingMutations;
  m_pendingMutations.clear();
  for (const QString &command : pending)
    send(command);
}

void TciClient::send(const QString &command) {
  if (m_socket.state() == QAbstractSocket::ConnectedState)
    m_socket.sendTextMessage(command);
}

void TciClient::globalStop() {
  m_mutationTimer.stop();
  m_pendingMutations.clear();
  if (m_ready && m_stopSentGeneration != m_generation) {
    m_stopSentGeneration = m_generation;
    send(QString::fromStdString(*shackcq::tci::build_safe_stop(0U)));
  }
  const auto attached = m_attachedReceivers;
  for (const int receiver : attached)
    detachReceiver(receiver);
}

void TciClient::setTimeoutsForTest(int connectionMs, int readyMs,
                                   int reconnectMs) {
  m_connectionTimer.setInterval(qMax(10, connectionMs));
  m_readyTimer.setInterval(qMax(10, readyMs));
  m_reconnectTimer.setInterval(qMax(10, reconnectMs));
}

} // namespace shackcq::desktop
