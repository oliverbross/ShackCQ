// SPDX-License-Identifier: GPL-3.0-only
#include "shackcq/desktop/CloudAgentClient.hpp"
#include "shackcq/desktop/LoggerIngestion.hpp"

#include <algorithm>
#include <initializer_list>

#include <QCoreApplication>
#include <QDateTime>
#include <QEventLoop>
#include <QJsonArray>
#include <QJsonDocument>
#include <QNetworkAccessManager>
#include <QNetworkReply>
#include <QNetworkRequest>
#include <QRegularExpression>
#include <QRandomGenerator>
#include <QSslError>
#include <QSslConfiguration>
#include <QSysInfo>

namespace shackcq::desktop {
namespace {
constexpr qsizetype MaxControlBytes = 64 * 1024;
QJsonObject protocol() { return {{"major", 1}, {"minor", 2}}; }
QString buildIdentity() {
  const QString configured = QStringLiteral(SHACKCQ_BUILD_SHA).left(80);
  return configured.isEmpty() ? QStringLiteral("local-uncommitted-build")
                              : configured;
}
QString compact(const QJsonObject &value) {
  return QString::fromUtf8(QJsonDocument(value).toJson(QJsonDocument::Compact));
}
bool boundedId(const QString &value) {
  static const QRegularExpression pattern(
      QStringLiteral("^[A-Za-z0-9._:-]{1,128}$"));
  return pattern.match(value).hasMatch();
}
}

CloudAgentClient::CloudAgentClient(DesktopCredentialVault *vault,
                                   DesktopRadioController *radio,
                                   AgentDigiController *digi,
                                   LoggerIngestion *logger,
                                   QObject *parent)
    : QObject(parent), m_vault(vault), m_radio(radio), m_digi(digi), m_logger(logger) {
  m_heartbeat.setInterval(15'000);
  m_reconnect.setSingleShot(true);
  m_radioCommandRetry.setInterval(25);
  m_radioCommandRetry.setSingleShot(true);
  connect(&m_heartbeat, &QTimer::timeout, this, [this] {
    sendObject({{"type", "agent.heartbeat"},
                {"protocol", protocol()},
                {"generation", QJsonValue::fromVariant(m_generation)}});
  });
  connect(&m_reconnect, &QTimer::timeout, this,
          &CloudAgentClient::connectNow);
  connect(&m_radioCommandRetry, &QTimer::timeout, this, [this] {
    if (m_pendingRadioCommand.isEmpty())
      return;
    if (m_radio->radioOperationActive()) {
      m_radioCommandRetry.start();
      return;
    }
    const QJsonObject pending = m_pendingRadioCommand;
    m_pendingRadioCommand = {};
    completeRadioCommand(pending);
  });
  connect(&m_socket, &QWebSocket::connected, this, [this] {
    m_reconnectAttempt = 0;
    setState("Authenticating", "TLS connected; sending bounded Agent hello");
    sendHello();
  });
  connect(&m_socket, &QWebSocket::textMessageReceived, this,
          &CloudAgentClient::receiveText);
  connect(&m_socket, &QWebSocket::binaryMessageReceived, this,
          [this](const QByteArray &) {
            m_socket.close(QWebSocketProtocol::CloseCodeDatatypeNotSupported,
                           "binary frames prohibited");
          });
  connect(&m_socket, &QWebSocket::sslErrors, this,
          [this](const QList<QSslError> &) {
            setState("Failed", "TLS verification failed");
            m_socket.close();
          });
  connect(&m_socket, &QWebSocket::disconnected, this, [this] {
    m_heartbeat.stop();
    m_generation = 0;
    m_announcedDeviceId.clear();
    m_radioCommandRetry.stop();
    m_pendingRadioCommand = {};
    if (m_digi) {
      m_digi->setServerTxPermitted(false);
      m_digi->stop("cloud transport disconnected", false);
    }
    if (!m_stopping) {
      setState("Offline", "Cloud transport disconnected; no commands queued");
      scheduleReconnect();
    }
  });
  connect(m_radio, &DesktopRadioController::snapshotChanged, this,
          [this] {
            if (m_generation == 0)
              return;
            if (deviceId() != m_announcedDeviceId) {
              m_socket.close(QWebSocketProtocol::CloseCodeNormal,
                             "radio inventory changed");
              return;
            }
            sendSnapshot();
          });
  if (m_digi)
    connect(m_digi, &AgentDigiController::snapshotChanged, this, [this] {
      if (m_generation != 0) sendSnapshot();
    });
  if (m_logger)
    connect(m_logger, &LoggerIngestion::eventsReady, this, [this] {
      sendLoggerEvents();
    });
}

bool CloudAgentClient::restoreConfiguration(const QVariantMap &section,
                                             QString *error) {
  if (section.contains("connectUrl") || section.contains("credential") ||
      section.contains("token")) {
    if (error)
      *error = "Cloud Agent secrets and endpoints must not be stored in configuration";
    return false;
  }
  m_enabled = section.value("enabled", false).toBool();
  return true;
}

QVariantMap CloudAgentClient::configuration() const {
  return {{"enabled", m_enabled}};
}

bool CloudAgentClient::pair(const QUrl &origin, const QString &rawCode,
                            const QString &name, QString *error) {
  if (origin.scheme() != "https" || origin.host().isEmpty() ||
      (!origin.path().isEmpty() && origin.path() != "/")) {
    if (error)
      *error = "Pairing origin must be an HTTPS origin without a path";
    return false;
  }
  QString code = rawCode;
  code.remove(QRegularExpression(QStringLiteral("[\\s-]")));
  code = code.trimmed().toUpper();
  if (code.size() != 12 || name.trimmed().isEmpty() || name.size() > 80) {
    if (error)
      *error = "Pairing code or Agent name is invalid";
    return false;
  }
  QNetworkAccessManager network;
  QUrl endpoint(origin);
  endpoint.setPath("/api/v1/agent/pair");
  QNetworkRequest request(endpoint);
  request.setHeader(QNetworkRequest::ContentTypeHeader, "application/json");
  request.setTransferTimeout(15'000);
  const QJsonObject body{{"code", code},
                         {"name", name.trimmed()},
                         {"platform", QSysInfo::prettyProductName().left(40)},
                         {"version", QCoreApplication::applicationVersion()},
                         {"build", buildIdentity()}};
  QNetworkReply *reply =
      network.post(request, QJsonDocument(body).toJson(QJsonDocument::Compact));
  QEventLoop loop;
  connect(reply, &QNetworkReply::finished, &loop, &QEventLoop::quit);
  loop.exec();
  const QByteArray response = reply->readAll();
  const int status =
      reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
  const auto failure = reply->error();
  reply->deleteLater();
  QJsonParseError parse;
  if (response.size() > MaxControlBytes) {
    if (error)
      *error = "Cloud pairing response exceeded the safety bound";
    return false;
  }
  const QJsonObject result = QJsonDocument::fromJson(response, &parse).object();
  const QUrl connectUrl(result.value("connectUrl").toString());
  const QString agentId = result.value("agentId").toString();
  const QString credential = result.value("credential").toString();
  const QString userId = result.value("userId").toString();
  const QString stationProfileId = result.value("stationProfileId").toString();
  const QString accountLabel = result.value("accountLabel").toString().trimmed();
  const QString stationLabel = result.value("stationLabel").toString().trimmed();
  if (failure != QNetworkReply::NoError || status != 201 ||
      parse.error != QJsonParseError::NoError || connectUrl.scheme() != "wss" ||
      connectUrl.host() != origin.host() ||
      connectUrl.port(443) != origin.port(443) ||
      connectUrl.path() != "/api/v1/agent/connect" || !boundedId(agentId) ||
      !boundedId(userId) || !boundedId(stationProfileId) ||
      accountLabel.isEmpty() || accountLabel.size() > 160 ||
      stationLabel.isEmpty() || stationLabel.size() > 200 ||
      credential.size() < 32 || credential.size() > 256) {
    if (error) {
      if (status == 401) {
        *error = "Pairing code is invalid or expired";
      } else if (status == 429) {
        *error = "Too many pairing attempts; wait before trying again";
      } else if (status != 0 && status != 201) {
        *error = QStringLiteral("Cloud pairing failed (HTTP %1)").arg(status);
      } else if (failure != QNetworkReply::NoError) {
        *error = "Cloud pairing failed: secure connection unavailable";
      } else if (parse.error != QJsonParseError::NoError) {
        *error = "Cloud pairing failed: invalid server response";
      } else if (connectUrl.scheme() != "wss" ||
                 connectUrl.host() != origin.host() ||
                 connectUrl.port(443) != origin.port(443) ||
                 connectUrl.path() != "/api/v1/agent/connect") {
        *error = "Cloud pairing failed: invalid secure connection route";
      } else if (!boundedId(agentId) || !boundedId(userId) ||
                 !boundedId(stationProfileId)) {
        *error = "Cloud pairing failed: invalid account association";
      } else if (accountLabel.isEmpty() || accountLabel.size() > 160 ||
                 stationLabel.isEmpty() || stationLabel.size() > 200) {
        *error = "Cloud pairing failed: account association label unavailable";
      } else {
        *error = "Cloud pairing failed: invalid credential response";
      }
    }
    return false;
  }
  const QJsonObject stored{{"agentId", agentId},
                           {"connectUrl", connectUrl.toString()},
                           {"credential", credential},
                           {"userId", userId},
                           {"stationProfileId", stationProfileId},
                           {"accountLabel", accountLabel},
                           {"stationLabel", stationLabel}};
  QString vaultError;
  if (!m_vault ||
      !m_vault->write(CredentialAlias, "ShackCQ cloud Agent",
                      compact(stored), &vaultError)) {
    if (error)
      *error = "System credential vault is unavailable";
    return false;
  }
  m_agentId = agentId;
  m_connectUrl = connectUrl;
  m_credential = credential;
  m_userId = userId;
  m_stationProfileId = stationProfileId;
  m_accountLabel = accountLabel;
  m_stationLabel = stationLabel;
  m_enabled = true;
  setState("Paired", QStringLiteral("Linked to %1 · %2").arg(m_accountLabel, m_stationLabel));
  return true;
}

bool CloudAgentClient::unpair(QString *error) {
  stop();
  if (!m_vault || !m_vault->remove(CredentialAlias, error))
    return false;
  m_agentId.clear();
  m_credential.clear();
  m_userId.clear();
  m_stationProfileId.clear();
  m_accountLabel.clear();
  m_stationLabel.clear();
  m_connectUrl = QUrl{};
  m_enabled = false;
  setState("Unpaired",
           "Cloud Agent credential removed from the operating-system vault");
  return true;
}

void CloudAgentClient::start() {
  m_stopping = false;
  if (!m_enabled) {
    setState("Disabled", "Cloud Agent is explicitly disabled");
    return;
  }
  const auto secret = m_vault ? m_vault->read(CredentialAlias) : std::nullopt;
  QJsonParseError parse;
  const QJsonObject stored =
      secret ? QJsonDocument::fromJson(secret->toUtf8(), &parse).object()
             : QJsonObject{};
  m_agentId = stored.value("agentId").toString();
  m_connectUrl = QUrl(stored.value("connectUrl").toString());
  m_credential = stored.value("credential").toString();
  m_userId = stored.value("userId").toString();
  m_stationProfileId = stored.value("stationProfileId").toString();
  m_accountLabel = stored.value("accountLabel").toString().trimmed().left(160);
  m_stationLabel = stored.value("stationLabel").toString().trimmed().left(200);
  if ((!m_userId.isEmpty() && !boundedId(m_userId)) ||
      (!m_stationProfileId.isEmpty() && !boundedId(m_stationProfileId))) {
    m_userId.clear();
    m_stationProfileId.clear();
    m_accountLabel.clear();
    m_stationLabel.clear();
  }
  if (parse.error != QJsonParseError::NoError || !boundedId(m_agentId) ||
      m_connectUrl.scheme() != "wss" || m_connectUrl.host().isEmpty() ||
      m_credential.size() < 32 || m_credential.size() > 256) {
    setState("Unpaired", "No valid cloud Agent credential");
    return;
  }
  connectNow();
}

void CloudAgentClient::stop() {
  m_stopping = true;
  m_heartbeat.stop();
  m_reconnect.stop();
  m_generation = 0;
  if (m_digi) {
    m_digi->setServerTxPermitted(false);
    m_digi->stop("cloud Agent stopped", false);
  }
  if (m_socket.state() != QAbstractSocket::UnconnectedState)
    m_socket.close(QWebSocketProtocol::CloseCodeNormal, "Agent shutdown");
  setState("Stopped", "No cloud commands accepted");
}

void CloudAgentClient::connectNow() {
  if (m_stopping || !m_enabled ||
      m_socket.state() != QAbstractSocket::UnconnectedState)
    return;
  QNetworkRequest request(m_connectUrl);
  request.setRawHeader("Authorization",
                       QByteArray("Bearer ") + m_credential.toUtf8());
  QSslConfiguration ssl = QSslConfiguration::defaultConfiguration();
  ssl.setProtocol(QSsl::TlsV1_3OrLater);
  request.setSslConfiguration(ssl);
  setState("Connecting", "Opening outbound TLS WebSocket");
  m_socket.open(request);
}

void CloudAgentClient::sendHello() {
  const QString currentDeviceId = deviceId();
  QJsonArray devices;
  if (!currentDeviceId.isEmpty()) {
    devices.append(QJsonObject{{"id", currentDeviceId},
                               {"name", m_radio->model().left(80)},
                               {"hamlibModelId", m_radio->hamlibModelId()},
                               {"manufacturer", m_radio->manufacturer().left(80)},
                               {"model", m_radio->model().left(120)},
                               {"capabilities", capabilityDescriptor()}});
  }
  m_announcedDeviceId = currentDeviceId;
  sendObject({{"type", "agent.hello"},
              {"protocol", protocol()},
              {"agentId", m_agentId},
              {"platform", QSysInfo::productType().left(40)},
              {"version", QCoreApplication::applicationVersion().left(40)},
              {"build", buildIdentity()},
              {"loggerSources", QJsonArray{"WSJTX", "N1MM"}},
              {"devices", devices}});
}

void CloudAgentClient::receiveText(const QString &text) {
  if (text.toUtf8().size() > MaxControlBytes) {
    m_socket.close(QWebSocketProtocol::CloseCodeTooMuchData,
                   "control frame too large");
    return;
  }
  QJsonParseError parse;
  const QJsonObject frame =
      QJsonDocument::fromJson(text.toUtf8(), &parse).object();
  if (parse.error != QJsonParseError::NoError || frame.isEmpty()) {
    m_socket.close(QWebSocketProtocol::CloseCodeProtocolError,
                   "malformed control frame");
    return;
  }
  if (frame.value("type") == "agent.accepted" &&
      frame.value("agentId").toString() == m_agentId &&
      frame.value("protocol").toObject().value("major").toInt() == 1) {
    m_generation = frame.value("generation").toVariant().toULongLong();
    if (m_digi)
      m_digi->setServerTxPermitted(frame.value("digiTxEnabled").toBool(false));
    if (m_generation == 0) {
      m_socket.close(QWebSocketProtocol::CloseCodeProtocolError,
                     "invalid generation");
      return;
    }
    m_heartbeat.start();
    setState("Live", "Cloud Agent connected; receive controls only");
    sendSnapshot();
    sendLoggerEvents();
    return;
  }
  if (frame.value("type") == "agent.heartbeat.ack")
    return;
  if(frame.value("type")=="logger.event.receipt"&&m_logger){
    const QJsonArray receipts=frame.value("receipts").toArray();bool allAccepted=!receipts.isEmpty();
    for(const QJsonValue &item:receipts)if(!item.toObject().value("accepted").toBool())allAccepted=false;
    m_logger->acknowledge(receipts);if(allAccepted)sendLoggerEvents();return;
  }
  if(frame.value("type")=="logger.profile.apply"){
    const QString commandId=frame.value("commandId").toString();QJsonObject result{{"type","logger.profile.result"},{"protocol",protocol()},{"commandId",commandId},{"agentId",m_agentId},{"deviceId","logger"},{"generation",QJsonValue::fromVariant(m_generation)}};
    if(!m_logger||!boundedId(commandId)||frame.value("agentId").toString()!=m_agentId||frame.value("expectedGeneration").toVariant().toULongLong()!=m_generation||m_generation==0){result.insert("ok",false);result.insert("code","LOGGER_COMMAND_SCOPE_REJECTED");}
    else {const QJsonObject applied=m_logger->applyProfile(frame.value("profile").toObject());result.insert("ok",applied.value("ok"));result.insert("code",applied.value("code"));}
    sendObject(result);if(result.value("ok").toBool())sendLoggerEvents();return;
  }
  if (frame.value("type") == "digi.command" && m_digi) {
    sendObject(m_digi->processCommand(frame, m_agentId, deviceId(), m_generation));
    sendSnapshot();
    return;
  }
  if (frame.value("type") != "radio.command")
    return;
  if (m_radio->radioOperationActive()) {
    if (m_pendingRadioCommand.isEmpty()) {
      m_pendingRadioCommand = frame;
      m_radioCommandRetry.start();
    }
    return;
  }
  completeRadioCommand(frame);
}

void CloudAgentClient::completeRadioCommand(const QJsonObject &frame) {
  const QJsonObject result = processControlFrame(frame);
  // Publish the fresh readback before the result. The hosted command path
  // resolves when it receives the result and must validate against this exact
  // post-mutation snapshot, not the preceding poll.
  sendSnapshot();
  sendObject(result);
}

void CloudAgentClient::sendLoggerEvents() {
  if (!m_logger || m_generation == 0) return;
  const QJsonArray events=m_logger->pendingEvents();
  if(!events.isEmpty())sendObject({{"type","logger.event.batch"},{"protocol",protocol()},
    {"agentId",m_agentId},{"generation",QJsonValue::fromVariant(m_generation)},{"events",events}});
}

QJsonObject CloudAgentClient::processControlFrame(const QJsonObject &frame) {
  const QString commandId = frame.value("commandId").toString();
  auto result = [&](bool ok, const QString &code) {
    return QJsonObject{{"type", "radio.command.result"},
                       {"protocol", protocol()},
                       {"commandId", commandId},
                       {"agentId", m_agentId},
                       {"deviceId", deviceId()},
                       {"generation", QJsonValue::fromVariant(m_generation)},
                       {"ok", ok},
                       {"code", code}};
  };
  if (!boundedId(commandId) || frame.value("agentId").toString() != m_agentId ||
      frame.value("deviceId").toString() != deviceId())
    return result(false, "COMMAND_SCOPE_REJECTED");
  if (frame.value("protocol").toObject().value("major").toInt() != 1 ||
      frame.value("expectedGeneration").toVariant().toULongLong() !=
          m_generation ||
      m_generation == 0)
    return result(false, "STALE_AGENT_GENERATION");
  if (m_radio->backend() != "hamlib" ||
      !m_radio->state().startsWith("Connected"))
    return result(false, "RADIO_OFFLINE");
  if (m_digi && m_digi->radioMutationBlocked())
    return result(false, "DIGI_BUSY_REQUIRES_DISARM");
  const QString action = frame.value("action").toString();
  const QJsonObject parameters = frame.value("parameters").toObject();
  const auto exactParameters = [&parameters](std::initializer_list<const char *> names) {
    if (parameters.size() != static_cast<int>(names.size()))
      return false;
    return std::all_of(names.begin(), names.end(), [&parameters](const char *name) {
      return parameters.contains(QString::fromLatin1(name));
    });
  };
  const QStringList advertisedSetters =
      m_radio->backendCapabilities().value("setters").toStringList();
  if (!advertisedSetters.contains(action))
    return result(false, "CAPABILITY_NOT_ADVERTISED");
  bool accepted = false;
  if (action == "radio.set.frequency") {
    if (!exactParameters({"frequencyHz"}) || !parameters.value("frequencyHz").isDouble())
      return result(false, "INVALID_PARAMETERS");
    const quint64 value =
        parameters.value("frequencyHz").toVariant().toULongLong();
    accepted = value >= 100'000 && value <= 10'500'000'000ULL &&
               m_radio->requestFrequency(value) &&
               m_radio->frequencyHz() == value;
  } else if (action == "radio.set.mode") {
    if (!exactParameters({"mode"}) || !parameters.value("mode").isString())
      return result(false, "INVALID_PARAMETERS");
    const QString value = parameters.value("mode").toString().toUpper();
    accepted = !value.isEmpty() && value.size() <= 12 &&
               m_radio->requestMode(value) &&
               m_radio->mode().compare(value, Qt::CaseInsensitive) == 0;
  } else if (action == "radio.set.filter") {
    if (!exactParameters({"filterHz"}) || !parameters.value("filterHz").isDouble())
      return result(false, "INVALID_PARAMETERS");
    const int value = parameters.value("filterHz").toInt();
    accepted = value >= 50 && value <= 20'000 &&
               m_radio->requestFilter(value) &&
               m_radio->filterHz() == value;
  } else if (action == "radio.set.rfGain" ||
             action == "radio.set.afGain" ||
             action == "radio.set.squelch") {
    if (!exactParameters({"value"}) || !parameters.value("value").isDouble())
      return result(false, "INVALID_PARAMETERS");
    const double value = parameters.value("value").toDouble(-1.0);
    accepted = value >= 0.0 && value <= 100.0 &&
               m_radio->requestReceiveControl(action,
                                               parameters.toVariantMap());
  } else if (action == "radio.set.noiseBlanker" ||
             action == "radio.set.notch" ||
             action == "radio.set.noiseReduction") {
    if (!exactParameters({"enabled"}) ||
        !parameters.value("enabled").isBool())
      return result(false, "INVALID_PARAMETERS");
    accepted = m_radio->requestReceiveControl(action,
                                               parameters.toVariantMap());
  } else if (action == "radio.set.agc") {
    if (!exactParameters({"mode"}) || !parameters.value("mode").isString() ||
        parameters.value("mode").toString().isEmpty() ||
        parameters.value("mode").toString().size() > 20)
      return result(false, "INVALID_PARAMETERS");
    accepted = m_radio->requestReceiveControl(action,
                                               parameters.toVariantMap());
  } else if (action == "radio.set.rit") {
    bool allowedKeys = parameters.size() >= 1 && parameters.size() <= 2;
    for (const QString &key : parameters.keys())
      allowedKeys = allowedKeys &&
                    (key == QStringLiteral("enabled") ||
                     key == QStringLiteral("valueHz"));
    const bool enabledValid = !parameters.contains("enabled") ||
                              parameters.value("enabled").isBool();
    const bool valueValid = !parameters.contains("valueHz") ||
                            parameters.value("valueHz").isDouble();
    const int value = parameters.value("valueHz").toInt();
    if (!allowedKeys || !enabledValid || !valueValid || value < -100'000 ||
        value > 100'000)
      return result(false, "INVALID_PARAMETERS");
    accepted = m_radio->requestReceiveControl(action,
                                               parameters.toVariantMap());
  } else if (action == "preset.recall") {
    if (!exactParameters({"frequencyHz", "mode", "filterHz"}) ||
        !parameters.value("frequencyHz").isDouble() ||
        !parameters.value("mode").isString() ||
        !parameters.value("filterHz").isDouble())
      return result(false, "INVALID_PARAMETERS");
    const quint64 frequency =
        parameters.value("frequencyHz").toVariant().toULongLong();
    const QString mode = parameters.value("mode").toString().toUpper();
    const int filter = parameters.value("filterHz").toInt();
    if (frequency < 100'000 || frequency > 10'500'000'000ULL ||
        mode.isEmpty() || mode.size() > 12 || filter < 50 || filter > 20'000)
      return result(false, "INVALID_PARAMETERS");
    const quint64 previousFrequency = m_radio->frequencyHz();
    const QString previousMode = m_radio->mode();
    const int previousFilter = m_radio->filterHz();
    const bool frequencyApplied = m_radio->requestFrequency(frequency) &&
                                  m_radio->frequencyHz() == frequency;
    const bool modeApplied = frequencyApplied && m_radio->requestMode(mode) &&
        m_radio->mode().compare(mode, Qt::CaseInsensitive) == 0;
    accepted = modeApplied && m_radio->requestFilter(filter) &&
               m_radio->filterHz() == filter;
    if (!accepted) {
      // Setter success is not sufficient rollback evidence: a backend may round
      // or normalise the requested value while still changing the radio. Compare
      // every fresh readback with the captured tuple and compensate any drift.
      if (m_radio->mode().compare(previousMode, Qt::CaseInsensitive) != 0 &&
          !previousMode.isEmpty())
        (void)m_radio->requestMode(previousMode);
      if (m_radio->filterHz() != previousFilter && previousFilter > 0)
        (void)m_radio->requestFilter(previousFilter);
      if (m_radio->frequencyHz() != previousFrequency && previousFrequency > 0)
        (void)m_radio->requestFrequency(previousFrequency);

      const bool completeRollback =
          m_radio->frequencyHz() == previousFrequency &&
          m_radio->mode().compare(previousMode, Qt::CaseInsensitive) == 0 &&
          m_radio->filterHz() == previousFilter;
      return result(false, completeRollback ? "PRESET_RECALL_ROLLED_BACK"
                                            : "PRESET_RECALL_PARTIAL");
    }
  } else {
    return result(false, "ACTION_PROHIBITED");
  }
  return result(accepted, accepted ? "READBACK_CONFIRMED"
                                   : "READBACK_NOT_CONFIRMED");
}

QJsonObject CloudAgentClient::capabilityDescriptor() const {
  const QVariantMap capabilities = m_radio->backendCapabilities();
  return {{"modelId", m_radio->hamlibModelId()},
          {"manufacturer", m_radio->manufacturer()},
          {"model", m_radio->model()},
          {"backend", m_radio->backend()},
          {"frequencyRangesHz", QJsonArray::fromVariantList(
                                    capabilities.value("frequencyRangesHz")
                                        .toList())},
          {"modes", QJsonArray::fromStringList(
                        capabilities.value("modes").toStringList())},
          {"filtersHz", QJsonArray::fromVariantList(
                            capabilities.value("filtersHz").toList())},
          {"setters", QJsonArray::fromStringList(
                          capabilities.value("setters").toStringList())},
          {"agcModes", QJsonArray::fromStringList(
                           capabilities.value("agcModes").toStringList())},
          {"meters", QJsonArray::fromStringList(
                         capabilities.value("meters").toStringList())},
          {"readOnlyTxState", true},
          {"rotatorEnvelope", "DECLARED_NO_MOVEMENT"}};
}

QString CloudAgentClient::deviceId() const {
  if (m_radio->backend() != "hamlib" ||
      !m_radio->state().startsWith("Connected"))
    return {};
  return QStringLiteral("hamlib:%1").arg(m_radio->hamlibModelId());
}

void CloudAgentClient::sendSnapshot() {
  if (m_generation == 0 ||
      m_socket.state() != QAbstractSocket::ConnectedState ||
      deviceId().isEmpty())
    return;
  ++m_sequence;
  QJsonObject snapshot{{"type", "radio.snapshot"},
              {"protocol", protocol()},
              {"agentId", m_agentId},
              {"deviceId", deviceId()},
              {"generation", QJsonValue::fromVariant(m_generation)},
              {"sequence", QJsonValue::fromVariant(m_sequence)},
              {"observedUtc",
               QDateTime::currentDateTimeUtc().toString(Qt::ISODateWithMs)},
              {"connection",
               m_radio->state().startsWith("Connected") ? "live" : "offline"},
              {"frequencyHz",
               m_radio->frequencyHz()
                   ? QJsonValue::fromVariant(m_radio->frequencyHz())
                   : QJsonValue::Null},
              {"mode", m_radio->mode().isEmpty()
                           ? QJsonValue::Null
                           : QJsonValue(m_radio->mode())},
              {"filterHz", m_radio->filterHz() > 0
                               ? QJsonValue(m_radio->filterHz())
                               : QJsonValue::Null},
              {"meters", QJsonObject::fromVariantMap(m_radio->meters())},
              {"transmitting",
               m_radio->transmitting()
                   ? QJsonValue(*m_radio->transmitting())
                   : QJsonValue::Null},
              {"capabilities", capabilityDescriptor()}};
  const QVariantMap receiveControls = m_radio->receiveControls();
  for (auto it = receiveControls.cbegin(); it != receiveControls.cend(); ++it)
    snapshot.insert(it.key(), QJsonValue::fromVariant(it.value()));
  sendObject(snapshot);
  if (m_digi)
    sendObject(m_digi->snapshot(m_agentId, deviceId(), m_generation));
}

void CloudAgentClient::sendObject(const QJsonObject &object) {
  const QByteArray bytes =
      QJsonDocument(object).toJson(QJsonDocument::Compact);
  const QString type=object.value("type").toString();
  if((type=="digi.snapshot"||type=="radio.snapshot")&&m_socket.bytesToWrite()>128*1024)return;
  if(m_socket.bytesToWrite()>1024*1024){m_socket.close(QWebSocketProtocol::CloseCodePolicyViolated,"outgoing queue limit");return;}
  if (bytes.size() <= MaxControlBytes &&
      m_socket.state() == QAbstractSocket::ConnectedState)
    m_socket.sendTextMessage(QString::fromUtf8(bytes));
}

void CloudAgentClient::scheduleReconnect() {
  if (m_stopping || !m_enabled)
    return;
  const int baseDelay = qMin(60'000, 1'000 << qMin(m_reconnectAttempt++, 6));
  const int jitter = qMax(1, baseDelay / 5);
  const int delay = qBound(1'000,
      baseDelay + QRandomGenerator::global()->bounded(-jitter, jitter + 1),
      60'000);
  m_reconnect.start(delay);
}

void CloudAgentClient::setState(const QString &value,
                                const QString &detail) {
  m_state = value;
  m_detail = detail.left(240);
  emit stateChanged();
}

QVariantMap CloudAgentClient::health() const {
  return {{"state", m_state},
          {"detail", m_detail},
          {"paired", !m_agentId.isEmpty()},
          {"userId", m_userId},
          {"stationProfileId", m_stationProfileId},
          {"accountLabel", m_accountLabel},
          {"stationLabel", m_stationLabel},
          {"enabled", m_enabled},
          {"generation", m_generation},
          {"offlineQueue", false},
          {"authority", "RECEIVE_CONTROLS_ONLY"},
          {"ptt", false},
          {"tune", false},
          {"txAudio", false},
          {"rotatorMovement", false},
          {"logger", m_logger ? m_logger->health() : QVariantMap{}},
          {"digi", m_digi ? m_digi->snapshot(m_agentId, deviceId(), m_generation).toVariantMap() : QVariantMap{}}};
}

} // namespace shackcq::desktop
