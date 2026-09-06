// SPDX-License-Identifier: GPL-3.0-only
#include "shackcq/desktop/CloudAgentClient.hpp"

#include <QCoreApplication>
#include <QDateTime>
#include <QEventLoop>
#include <QJsonArray>
#include <QJsonDocument>
#include <QNetworkAccessManager>
#include <QNetworkReply>
#include <QNetworkRequest>
#include <QRegularExpression>
#include <QSslError>
#include <QSslConfiguration>
#include <QSysInfo>

namespace shackcq::desktop {
namespace {
constexpr qsizetype MaxControlBytes = 64 * 1024;
QJsonObject protocol() { return {{"major", 1}, {"minor", 0}}; }
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
                                   QObject *parent)
    : QObject(parent), m_vault(vault), m_radio(radio) {
  m_heartbeat.setInterval(15'000);
  m_reconnect.setSingleShot(true);
  connect(&m_heartbeat, &QTimer::timeout, this, [this] {
    sendObject({{"type", "agent.heartbeat"},
                {"protocol", protocol()},
                {"generation", QJsonValue::fromVariant(m_generation)}});
  });
  connect(&m_reconnect, &QTimer::timeout, this,
          &CloudAgentClient::connectNow);
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
  code.remove('-');
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
                         {"build", QStringLiteral(SHACKCQ_BUILD_SHA).left(80)}};
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
  if (failure != QNetworkReply::NoError || status != 201 ||
      parse.error != QJsonParseError::NoError || connectUrl.scheme() != "wss" ||
      connectUrl.host() != origin.host() ||
      connectUrl.port(443) != origin.port(443) ||
      connectUrl.path() != "/api/v1/agent/connect" || !boundedId(agentId) ||
      credential.size() < 32 || credential.size() > 256) {
    if (error)
      *error = status == 401 ? "Pairing code is invalid or expired"
                            : "Cloud pairing failed";
    return false;
  }
  const QJsonObject stored{{"agentId", agentId},
                           {"connectUrl", connectUrl.toString()},
                           {"credential", credential}};
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
  m_enabled = true;
  setState("Paired", "Credential stored in the operating-system vault");
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
              {"build", QStringLiteral(SHACKCQ_BUILD_SHA).left(80)},
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
    if (m_generation == 0) {
      m_socket.close(QWebSocketProtocol::CloseCodeProtocolError,
                     "invalid generation");
      return;
    }
    m_heartbeat.start();
    setState("Live", "Cloud Agent connected; receive controls only");
    sendSnapshot();
    return;
  }
  if (frame.value("type") == "agent.heartbeat.ack")
    return;
  if (frame.value("type") != "radio.command")
    return;
  sendObject(processControlFrame(frame));
  sendSnapshot();
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
  const QString action = frame.value("action").toString();
  const QJsonObject parameters = frame.value("parameters").toObject();
  const QStringList advertisedSetters =
      m_radio->backendCapabilities().value("setters").toStringList();
  if (!advertisedSetters.contains(action))
    return result(false, "CAPABILITY_NOT_ADVERTISED");
  bool accepted = false;
  if (action == "radio.set.frequency") {
    const quint64 value =
        parameters.value("frequencyHz").toVariant().toULongLong();
    accepted = value >= 100'000 && value <= 10'500'000'000ULL &&
               m_radio->requestFrequency(value) &&
               m_radio->frequencyHz() == value;
  } else if (action == "radio.set.mode") {
    const QString value = parameters.value("mode").toString().toUpper();
    accepted = !value.isEmpty() && value.size() <= 12 &&
               m_radio->requestMode(value) &&
               m_radio->mode().compare(value, Qt::CaseInsensitive) == 0;
  } else if (action == "radio.set.filter") {
    const int value = parameters.value("filterHz").toInt();
    accepted = value >= 50 && value <= 20'000 &&
               m_radio->requestFilter(value) &&
               m_radio->filterHz() == value;
  } else if (action == "preset.recall") {
    const quint64 frequency =
        parameters.value("frequencyHz").toVariant().toULongLong();
    const QString mode = parameters.value("mode").toString().toUpper();
    const int filter = parameters.value("filterHz").toInt();
    accepted = frequency >= 100'000 && frequency <= 10'500'000'000ULL &&
               !mode.isEmpty() && mode.size() <= 12 && filter >= 50 &&
               filter <= 20'000 && m_radio->requestFrequency(frequency) &&
               m_radio->frequencyHz() == frequency &&
               m_radio->requestMode(mode) &&
               m_radio->mode().compare(mode, Qt::CaseInsensitive) == 0 &&
               m_radio->requestFilter(filter) &&
               m_radio->filterHz() == filter;
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
  sendObject({{"type", "radio.snapshot"},
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
              {"meters", QJsonObject{}},
              {"transmitting", QJsonValue::Null},
              {"capabilities", capabilityDescriptor()}});
}

void CloudAgentClient::sendObject(const QJsonObject &object) {
  const QByteArray bytes =
      QJsonDocument(object).toJson(QJsonDocument::Compact);
  if (bytes.size() <= MaxControlBytes &&
      m_socket.state() == QAbstractSocket::ConnectedState)
    m_socket.sendTextMessage(QString::fromUtf8(bytes));
}

void CloudAgentClient::scheduleReconnect() {
  if (m_stopping || !m_enabled)
    return;
  const int delay = qMin(60'000, 1'000 << qMin(m_reconnectAttempt++, 6));
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
          {"enabled", m_enabled},
          {"generation", m_generation},
          {"offlineQueue", false},
          {"authority", "RECEIVE_CONTROLS_ONLY"},
          {"ptt", false},
          {"tune", false},
          {"txAudio", false},
          {"rotatorMovement", false}};
}

} // namespace shackcq::desktop
