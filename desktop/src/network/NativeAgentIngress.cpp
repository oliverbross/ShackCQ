// SPDX-License-Identifier: GPL-3.0-only
#include "shackcq/desktop/NativeAgentIngress.hpp"
#include "shackcq/desktop/LoggerIngestion.hpp"

#include <QJsonDocument>
#include <QLocalServer>
#include <QLocalSocket>
#include <QMessageAuthenticationCode>
#include <QRandomGenerator>
#include <QRegularExpression>
#include <QTimer>

namespace shackcq::desktop {
namespace {
constexpr auto SecretAlias = "shackcq-native-ingress-v1";
void appendField(QByteArray &output, const QByteArray &value) {
  const quint32 size = quint32(value.size());
  for (int shift = 24; shift >= 0; shift -= 8)
    output.append(char((size >> shift) & 0xff));
  output.append(value);
}
bool constantEqual(const QByteArray &left, const QByteArray &right) {
  if (left.size() != right.size())
    return false;
  unsigned char difference = 0;
  for (qsizetype index = 0; index < left.size(); ++index)
    difference |= static_cast<unsigned char>(left[index] ^ right[index]);
  return difference == 0;
}
bool safeId(const QString &value) {
  static const QRegularExpression pattern(
      QStringLiteral("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$"));
  return pattern.match(value).hasMatch();
}
} // namespace

NativeAgentIngress::NativeAgentIngress(
    DesktopCredentialVault *vault, LoggerIngestion *logger,
    std::function<QDateTime()> clock,
    std::function<QByteArray()> secretSource)
    : m_vault(vault), m_logger(logger),
      m_clock(clock ? std::move(clock)
                    : [] { return QDateTime::currentDateTimeUtc(); }),
      m_secretSource(secretSource ? std::move(secretSource) : [] {
        QByteArray bytes(32, Qt::Uninitialized);
        QRandomGenerator::system()->fillRange(
            reinterpret_cast<quint32 *>(bytes.data()), 8);
        return bytes;
      }) {}

bool NativeAgentIngress::initialize(QString *error) {
  const auto stored = m_vault ? m_vault->read(SecretAlias, error) : std::nullopt;
  if (stored) {
    m_secret = QByteArray::fromHex(stored->toLatin1());
    if (m_secret.size() == 32)
      return true;
    if (error)
      *error = "Native ingress credential is invalid";
    return false;
  }
  m_secret = m_secretSource();
  if (m_secret.size() != 32 || !m_vault ||
      !m_vault->write(SecretAlias, "ShackCQ native Agent ingress",
                      QString::fromLatin1(m_secret.toHex()), error)) {
    m_secret.clear();
    if (error && error->isEmpty())
      *error = "Native ingress credential is unavailable";
    return false;
  }
  return true;
}

QJsonObject NativeAgentIngress::capability() const {
  return {{"available", m_secret.size() == 32},
          {"contract", QJsonObject{{"major", 1}, {"minor", 0}}},
          {"maximumRequestBytes", MaxRequestBytes},
          {"authentication", "HMAC_SHA256_OS_VAULT"}};
}

QByteArray NativeAgentIngress::authenticatedBytes(const QJsonObject &envelope,
                                                  QByteArray *payload) {
  const QByteArray decoded =
      QByteArray::fromBase64(envelope.value("payloadBase64").toString().toLatin1(),
                             QByteArray::AbortOnBase64DecodingErrors);
  if (payload)
    *payload = decoded;
  QByteArray value("shackcq-native-ingress-v1");
  value.append('\0');
  appendField(value, envelope.value("action").toString().toUtf8());
  const QJsonObject protocol = envelope.value("protocol").toObject();
  appendField(value, QByteArray::number(protocol.value("major").toInt()));
  appendField(value, QByteArray::number(protocol.value("minor").toInt()));
  appendField(value, envelope.value("requestId").toString().toUtf8());
  appendField(value, envelope.value("nonce").toString().toUtf8());
  appendField(value, envelope.value("sentUtc").toString().toUtf8());
  appendField(value, decoded);
  return value;
}

QString NativeAgentIngress::signForTest(const QByteArray &secret,
                                        const QJsonObject &envelope) {
  return QString::fromLatin1(
      QMessageAuthenticationCode::hash(authenticatedBytes(envelope), secret,
                                       QCryptographicHash::Sha256)
          .toHex());
}

QJsonObject NativeAgentIngress::handle(const QJsonObject &envelope) {
  const QString requestId = envelope.value("requestId").toString();
  auto result = [&requestId](bool ok, const QString &code,
                             const QJsonObject &extra = {}) {
    QJsonObject value{{"ok", ok}, {"code", code}, {"requestId", requestId}};
    for (auto it = extra.begin(); it != extra.end(); ++it)
      value.insert(it.key(), it.value());
    return value;
  };
  const QJsonObject protocol = envelope.value("protocol").toObject();
  const QString nonce = envelope.value("nonce").toString();
  const QString sentUtc = envelope.value("sentUtc").toString();
  const QString macText = envelope.value("mac").toString();
  const QByteArray supplied = QByteArray::fromHex(macText.toLatin1());
  QByteArray payload;
  const QByteArray bytes = authenticatedBytes(envelope, &payload);
  const QDateTime sent = QDateTime::fromString(sentUtc, Qt::ISODate);
  const qint64 now = m_clock().toMSecsSinceEpoch();
  if (m_secret.size() != 32)
    return result(false, "AGENT_NATIVE_INGRESS_UNAVAILABLE");
  static const QRegularExpression lowerHex64(QStringLiteral("^[0-9a-f]{64}$"));
  const QString action = envelope.value("action").toString();
  if ((action != "nexus-contact.submit" &&
       action != "native-ingress.binding") ||
      protocol.value("major") != 1 || protocol.value("minor") != 0 ||
      !safeId(requestId) || !lowerHex64.match(nonce).hasMatch() ||
      !lowerHex64.match(macText).hasMatch() || !sent.isValid() ||
      sent.offsetFromUtc() != 0 || qAbs(sent.toMSecsSinceEpoch() - now) > 10'000 ||
      !(sentUtc.endsWith('Z') || sentUtc.endsWith("+00:00")) ||
      payload.isEmpty() || payload.size() > MaxPayloadBytes || supplied.size() != 32)
    return result(false, "AGENT_NATIVE_INGRESS_INVALID");
  const QByteArray expected = QMessageAuthenticationCode::hash(
      bytes, m_secret, QCryptographicHash::Sha256);
  if (!constantEqual(supplied, expected))
    return result(false, "AGENT_NATIVE_INGRESS_AUTH_FAILED");
  for (auto it = m_seenNonces.begin(); it != m_seenNonces.end();)
    if (it.value() < now - 10'000)
      it = m_seenNonces.erase(it);
    else
      ++it;
  if (m_seenNonces.contains(nonce))
    return result(false, "AGENT_NATIVE_INGRESS_REPLAYED");
  if (m_seenNonces.size() >= 256)
    return result(false, "AGENT_NATIVE_INGRESS_BUSY");
  m_seenNonces.insert(nonce, now);
  const QJsonDocument parsed = QJsonDocument::fromJson(payload);
  if (!parsed.isObject())
    return result(false, "AGENT_NATIVE_INGRESS_INVALID");
  if (!m_logger)
    return result(false, "AGENT_NATIVE_INGRESS_UNAVAILABLE");
  if (action == "native-ingress.binding") {
    const QJsonObject binding = m_logger->nativeBinding();
    return result(binding.value("ok").toBool(),
                  binding.value("code").toString(), binding);
  }
  const QJsonObject accepted = m_logger->submitNativeContact(parsed.object());
  return result(accepted.value("ok").toBool(),
                accepted.value("code").toString(), accepted);
}

NativeAgentIngressServer::NativeAgentIngressServer(QLocalServer *server,
                                                   NativeAgentIngress *ingress,
                                                   Fallback fallback,
                                                   QObject *parent,
                                                   int idleTimeoutMs)
    : QObject(parent), m_server(server), m_ingress(ingress),
      m_fallback(std::move(fallback)), m_idleTimeoutMs(idleTimeoutMs) {
  if (m_server) {
    m_server->setMaxPendingConnections(MaxActiveConnections);
    connect(m_server, &QLocalServer::newConnection, this,
            [this] { acceptConnections(); });
  }
}

void NativeAgentIngressServer::respond(QLocalSocket *socket,
                                       const QJsonObject &response) {
  socket->write(QJsonDocument(response).toJson(QJsonDocument::Compact) + '\n');
  socket->flush();
  socket->disconnectFromServer();
}

void NativeAgentIngressServer::acceptConnections() {
  while (QLocalSocket *socket = m_server->nextPendingConnection()) {
    if (m_active.size() >= MaxActiveConnections) {
      respond(socket, {{"ok", false},
                       {"result", QJsonObject{{"ok", false},
                                              {"code", "AGENT_NATIVE_INGRESS_BUSY"}}}});
      socket->deleteLater();
      continue;
    }
    m_active.insert(socket);
    auto *deadline = new QTimer(socket);
    deadline->setSingleShot(true);
    deadline->start(m_idleTimeoutMs);
    connect(deadline, &QTimer::timeout, socket, [this, socket] {
      respond(socket, {{"ok", false},
                       {"result", QJsonObject{{"ok", false},
                                              {"code", "AGENT_NATIVE_INGRESS_TIMEOUT"}}}});
    });
    connect(socket, &QLocalSocket::readyRead, socket,
            [this, socket, deadline] {
              deadline->start(m_idleTimeoutMs);
              readRequest(socket);
            });
    connect(socket, &QObject::destroyed, this,
            [this, socket] { m_active.remove(socket); });
    connect(socket, &QLocalSocket::disconnected, socket, &QObject::deleteLater);
    if (socket->bytesAvailable())
      readRequest(socket);
  }
}

void NativeAgentIngressServer::readRequest(QLocalSocket *socket) {
  const auto tooLarge = [this, socket] {
    respond(socket, {{"ok", false},
                     {"result", QJsonObject{{"ok", false},
                                            {"code", "AGENT_NATIVE_INGRESS_TOO_LARGE"}}}});
  };
  if (!socket->canReadLine()) {
    if (socket->bytesAvailable() >= NativeAgentIngress::MaxRequestBytes)
      tooLarge();
    return;
  }
  if (socket->bytesAvailable() > NativeAgentIngress::MaxRequestBytes) {
    tooLarge();
    return;
  }
  const QByteArray line =
      socket->readLine(NativeAgentIngress::MaxRequestBytes + 1);
  QJsonParseError parse;
  const QJsonObject request = QJsonDocument::fromJson(line, &parse).object();
  QJsonObject outcome;
  if (parse.error != QJsonParseError::NoError || request.isEmpty())
    outcome = {{"ok", false}, {"code", "AGENT_NATIVE_INGRESS_INVALID"}};
  else if ((request.value("action") == "nexus-contact.submit" ||
            request.value("action") == "native-ingress.binding") && m_ingress)
    outcome = m_ingress->handle(request);
  else if (m_fallback)
    outcome = m_fallback(request);
  else
    outcome = {{"ok", false}, {"code", "AGENT_ADMIN_ACTION_UNKNOWN"}};
  respond(socket, {{"ok", outcome.value("ok").toBool()}, {"result", outcome}});
}

} // namespace shackcq::desktop
