// SPDX-License-Identifier: GPL-3.0-only
#include "shackcq/desktop/RemoteStationService.hpp"

#include <QCryptographicHash>
#include <QDateTime>
#include <QHostAddress>
#include <QJsonArray>
#include <QJsonDocument>
#include <QNetworkInterface>
#include <QRandomGenerator>
#include <QSslCertificate>
#include <QSslConfiguration>
#include <QSslKey>
#include <QTcpSocket>
#include <QWebSocket>
#include <QUuid>

#include <openssl/bio.h>
#include <openssl/ec.h>
#include <openssl/evp.h>
#include <openssl/pem.h>
#include <openssl/x509.h>
#include <opus.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <cstring>
#include <memory>

namespace shackcq::desktop {
namespace {
constexpr int MaxControlBytes = static_cast<int>(remote::MaxControlFrame);
constexpr int MaxRigLine = 4096;
const QHostAddress MdnsGroup(QStringLiteral("224.0.0.251"));

void dns16(QByteArray &out, quint16 value) {
  out.append(static_cast<char>(value >> 8)); out.append(static_cast<char>(value));
}
void dns32(QByteArray &out, quint32 value) {
  out.append(static_cast<char>(value >> 24)); out.append(static_cast<char>(value >> 16));
  out.append(static_cast<char>(value >> 8)); out.append(static_cast<char>(value));
}
QByteArray dnsName(const QString &name) {
  QByteArray out;
  for (const QString &label : name.split('.', Qt::SkipEmptyParts)) {
    const QByteArray bytes = label.toUtf8().left(63);
    out.append(static_cast<char>(bytes.size())); out.append(bytes);
  }
  out.append(char(0)); return out;
}
void dnsRecord(QByteArray &out, const QString &name, quint16 type,
               const QByteArray &data, bool unique = false) {
  out.append(dnsName(name)); dns16(out, type); dns16(out, unique ? 0x8001 : 1);
  dns32(out, 120); dns16(out, static_cast<quint16>(data.size())); out.append(data);
}
bool withinRate(QObject *connection, int maximumPerMinute) {
  const qint64 now = QDateTime::currentMSecsSinceEpoch();
  qint64 window = connection->property("shackcqRateWindow").toLongLong();
  int count = connection->property("shackcqRateCount").toInt();
  if (window == 0 || now - window >= 60'000) { window = now; count = 0; }
  connection->setProperty("shackcqRateWindow", window);
  connection->setProperty("shackcqRateCount", ++count);
  return count <= maximumPerMinute;
}

QByteArray bioBytes(BIO *bio) {
  char *bytes{};
  const long size = BIO_get_mem_data(bio, &bytes);
  return size > 0 ? QByteArray(bytes, static_cast<int>(size)) : QByteArray{};
}

bool generateP256(QStringView commonName, QByteArray *privatePem,
                  QByteArray *publicPem, QByteArray *certificatePem,
                  QString *error) {
  using PkeyCtx = std::unique_ptr<EVP_PKEY_CTX, decltype(&EVP_PKEY_CTX_free)>;
  using Pkey = std::unique_ptr<EVP_PKEY, decltype(&EVP_PKEY_free)>;
  using Cert = std::unique_ptr<X509, decltype(&X509_free)>;
  using Bio = std::unique_ptr<BIO, decltype(&BIO_free)>;
  PkeyCtx context(EVP_PKEY_CTX_new_id(EVP_PKEY_EC, nullptr), EVP_PKEY_CTX_free);
  EVP_PKEY *rawKey{};
  if (!context || EVP_PKEY_keygen_init(context.get()) <= 0 ||
      EVP_PKEY_CTX_set_ec_paramgen_curve_nid(context.get(), NID_X9_62_prime256v1) <= 0 ||
      EVP_PKEY_keygen(context.get(), &rawKey) <= 0) {
    if (error) *error = "OpenSSL could not generate the P-256 station key";
    return false;
  }
  Pkey key(rawKey, EVP_PKEY_free);
  Bio privateBio(BIO_new(BIO_s_mem()), BIO_free);
  Bio publicBio(BIO_new(BIO_s_mem()), BIO_free);
  if (!privateBio || !publicBio ||
      // The PEM is plaintext only inside the platform credential-vault value;
      // the vault provides encryption-at-rest and access control. This avoids
      // relying on Qt support for a particular PKCS#8 cipher OID.
      PEM_write_bio_PrivateKey(privateBio.get(), key.get(), nullptr,
                               nullptr, 0, nullptr, nullptr) != 1 ||
      PEM_write_bio_PUBKEY(publicBio.get(), key.get()) != 1) {
    if (error) *error = "OpenSSL could not encode the station key";
    return false;
  }
  *privatePem = bioBytes(privateBio.get());
  *publicPem = bioBytes(publicBio.get());
  if (certificatePem == nullptr) return true;
  Cert certificate(X509_new(), X509_free);
  if (!certificate || X509_set_version(certificate.get(), 2) != 1 ||
      ASN1_INTEGER_set(X509_get_serialNumber(certificate.get()),
                       QRandomGenerator::global()->bounded(1, 0x7fffffff)) != 1 ||
      X509_gmtime_adj(X509_get_notBefore(certificate.get()), -60) == nullptr ||
      X509_gmtime_adj(X509_get_notAfter(certificate.get()), 10LL * 365 * 24 * 60 * 60) == nullptr ||
      X509_set_pubkey(certificate.get(), key.get()) != 1) {
    if (error) *error = "OpenSSL could not create the station certificate";
    return false;
  }
  X509_NAME *name = X509_get_subject_name(certificate.get());
  const QByteArray cn = commonName.toString().toUtf8();
  if (!name || X509_NAME_add_entry_by_txt(name, "CN", MBSTRING_UTF8,
      reinterpret_cast<const unsigned char *>(cn.constData()), cn.size(), -1, 0) != 1 ||
      X509_set_issuer_name(certificate.get(), name) != 1 ||
      X509_sign(certificate.get(), key.get(), EVP_sha256()) <= 0) {
    if (error) *error = "OpenSSL could not sign the station certificate";
    return false;
  }
  Bio certBio(BIO_new(BIO_s_mem()), BIO_free);
  if (!certBio || PEM_write_bio_X509(certBio.get(), certificate.get()) != 1) {
    if (error) *error = "OpenSSL could not encode the station certificate";
    return false;
  }
  *certificatePem = bioBytes(certBio.get());
  return true;
}

QJsonObject availableValue(bool available, const QJsonValue &value) {
  return {{"availability", available ? "AVAILABLE" : "UNAVAILABLE"},
          {"value", available ? value : QJsonValue{}}};
}
} // namespace

RemoteStationService::RemoteStationService(DesktopCredentialVault *vault,
    DesktopRadioController *radio, DesktopRotatorController *rotator,
    DesktopPanadapter *panadapter, QObject *parent)
    : QObject(parent), m_vault(vault), m_radio(radio), m_rotator(rotator),
      m_panadapter(panadapter),
      m_webSocketServer("ShackCQ Remote Protocol v1", QWebSocketServer::SecureMode, this) {
  m_webSocketServer.setMaxPendingConnections(static_cast<int>(remote::MaxSessions));
  m_webSocketServer.setHandshakeTimeout(5'000);
  m_webSocketServer.setSupportedSubprotocols({"shackcq.remote.v1"});
  connect(&m_webSocketServer, &QWebSocketServer::newConnection,
          this, &RemoteStationService::acceptWebSocket);
  connect(&m_rigctldServer, &QTcpServer::newConnection,
          this, &RemoteStationService::acceptRigctld);
  connect(&m_tciServer, &QTcpServer::newConnection,
          this, &RemoteStationService::acceptTci);
  connect(&m_discoverySocket, &QUdpSocket::readyRead,
          this, &RemoteStationService::answerDiscovery);
  m_stateTimer.setInterval(250);
  connect(&m_stateTimer, &QTimer::timeout, this, &RemoteStationService::sendState);
  m_expiryTimer.setInterval(1'000);
  connect(&m_expiryTimer, &QTimer::timeout, this, [this] {
    m_authority.expire(static_cast<quint64>(QDateTime::currentMSecsSinceEpoch()));
    const auto live = m_authority.sessions();
    for (auto it = m_socketSessions.begin(); it != m_socketSessions.end();) {
      const bool found = std::any_of(live.begin(), live.end(), [&](const auto &row) {
        return row.sessionId == it.value().toStdString();
      });
      if (!found) {
        it.key()->close(QWebSocketProtocol::CloseCodePolicyViolated, "Session heartbeat expired");
        it = m_socketSessions.erase(it);
      } else ++it;
    }
    emit sessionsChanged();
  });
  if (m_panadapter)
    connect(m_panadapter, &DesktopPanadapter::receiverFrameReady,
            this, &RemoteStationService::sendSpectrum);
  if (m_radio)
    connect(m_radio, &DesktopRadioController::rxAudioFrame,
            this, &RemoteStationService::sendAudio);
  if (m_radio)
    connect(m_radio, &DesktopRadioController::iqFrame,
            this, &RemoteStationService::sendIq);
}

RemoteStationService::~RemoteStationService() {
  stop();
  if (m_opusEncoder) opus_encoder_destroy(static_cast<OpusEncoder *>(m_opusEncoder));
}

QVariantMap RemoteStationService::configuration() const {
  return {{"schema", 1}, {"enabled", m_serviceEnabled}, {"stationId", m_stationId},
          {"stationName", m_stationName}, {"listenAddress", m_listenAddress},
          {"port", m_port}, {"lanEnabled", m_lanEnabled},
          {"rigctldEnabled", m_rigctldEnabled}, {"rigctldPort", m_rigctldPort},
          {"tciEnabled", m_tciEnabled}, {"tciPort", m_tciPort},
          {"remoteTxPolicy", false}, {"rotatorPolicy", false},
          {"rawIqEnabled", m_rawIqHostEnabled},
          {"rawIqMaxSampleRate", m_rawIqMaxSampleRate},
          {"audioChannels", m_audioChannels},
          {"pairedDevices", m_pairedDevices}};
}

bool RemoteStationService::restoreConfiguration(const QVariantMap &config,
                                                QString *error) {
  if (config.isEmpty()) return true;
  if (config.value("schema", 1).toInt() != 1 || config.value("stationName").toString().size() > 80 ||
      config.value("port", 7443).toInt() < 1 || config.value("port", 7443).toInt() > 65535) {
    if (error) *error = "Remote Station configuration is malformed";
    return false;
  }
  m_serviceEnabled = config.value("enabled", false).toBool();
  m_stationId = config.value("stationId").toString().left(128);
  m_stationName = config.value("stationName", "ShackCQ Station").toString().trimmed();
  m_listenAddress = config.value("listenAddress", "127.0.0.1").toString();
  m_port = static_cast<quint16>(config.value("port", 7443).toUInt());
  m_lanEnabled = config.value("lanEnabled", false).toBool();
  m_rigctldEnabled = config.value("rigctldEnabled", false).toBool();
  m_rigctldPort = static_cast<quint16>(config.value("rigctldPort", 4532).toUInt());
  m_tciEnabled = config.value("tciEnabled", false).toBool();
  m_tciPort = static_cast<quint16>(config.value("tciPort", 50001).toUInt());
  m_remoteTxPolicy = false;
  m_rotatorPolicy = false;
  // Raw I/Q is deliberately never restored. A local operator must enable it
  // for the current service lifetime and one client must request it again.
  m_rawIqHostEnabled = false;
  m_rawIqMaxSampleRate = qBound(16'000U,
      config.value("rawIqMaxSampleRate", 96'000).toUInt(), 192'000U);
  m_audioChannels = qBound(1, config.value("audioChannels", 1).toInt(), 2);
  m_pairedDevices = config.value("pairedDevices").toMap();
  for (auto it = m_pairedDevices.cbegin(); it != m_pairedDevices.cend(); ++it) {
    if (!it.value().canConvert<QVariantMap>()) continue;
    const QVariantMap device = it.value().toMap();
    bool roleOk{};
    const auto role = decodeRole(device.value("role").toString(), &roleOk);
    if (roleOk && !m_authority.restorePairedDevice(it.key().toStdString(),
        device.value("publicKeyPem").toString().toStdString(), role,
        device.value("revoked").toBool())) {
      if (error) *error = "Stored paired-device metadata is invalid";
      return false;
    }
  }
  return true;
}

bool RemoteStationService::applyLocalSettings(const QVariantMap &settings) {
  if (running()) return false;
  QVariantMap next = configuration();
  for (const QString &key : {QStringLiteral("enabled"), QStringLiteral("stationName"),
                             QStringLiteral("listenAddress"), QStringLiteral("port"),
                             QStringLiteral("lanEnabled"), QStringLiteral("rigctldEnabled"),
                             QStringLiteral("rigctldPort"), QStringLiteral("tciEnabled"),
                              QStringLiteral("tciPort"), QStringLiteral("rawIqMaxSampleRate"),
                              QStringLiteral("audioChannels")}) {
    if (settings.contains(key)) next[key] = settings.value(key);
  }
  QString error;
  const bool restored = restoreConfiguration(next, &error);
  if (restored && settings.contains("rawIqEnabled"))
    m_rawIqHostEnabled = settings.value("rawIqEnabled").toBool();
  if (!restored) emit this->error(error);
  emit stateChanged();
  return restored;
}

bool RemoteStationService::armThirdPartyWriter(int ttlMs) {
  if (!running() || (!m_rigctldServer.isListening() && !m_tciServer.isListening()))
    return false;
  m_externalWriterExpiryMs = QDateTime::currentMSecsSinceEpoch() + qBound(1'000, ttlMs, 30'000);
  emit stateChanged();
  return true;
}

void RemoteStationService::clearLocalAcceptance() {
  m_externalWriterExpiryMs = 0;
  m_remoteTxPolicy = false;
  m_rotatorPolicy = false;
  emit stateChanged();
}

bool RemoteStationService::ensureIdentity(QString *error) {
  if (!m_stationId.isEmpty() && m_vault && m_vault->read(TlsKeyAlias).has_value() &&
      m_vault->read(SigningKeyAlias).has_value() && m_pairedDevices.contains("stationCertificatePem")) return true;
  if (!m_vault) { if (error) *error = "Platform credential vault is unavailable"; return false; }
  m_stationId = QUuid::createUuid().toString(QUuid::WithoutBraces);
  QByteArray tlsPrivate, tlsPublic, certificate, signingPrivate, signingPublic;
  if (!generateP256(m_stationName, &tlsPrivate, &tlsPublic, &certificate, error) ||
      !generateP256(QStringLiteral("%1 signing").arg(m_stationName), &signingPrivate,
                    &signingPublic, nullptr, error)) return false;
  QString vaultError;
  if (!m_vault->write(TlsKeyAlias, "ShackCQ Remote Station TLS P-256 key",
                      QString::fromLatin1(tlsPrivate.toBase64()), &vaultError) ||
      !m_vault->write(SigningKeyAlias, "ShackCQ Remote Station signing P-256 key",
                      QString::fromLatin1(signingPrivate.toBase64()), &vaultError)) {
    if (error) *error = vaultError;
    return false;
  }
  m_pairedDevices["stationCertificatePem"] = QString::fromLatin1(certificate);
  m_pairedDevices["stationSigningPublicKeyPem"] = QString::fromLatin1(signingPublic);
  emit pairingChanged();
  return true;
}

QString RemoteStationService::fingerprint(const QByteArray &certificatePem) {
  const auto certs = QSslCertificate::fromData(certificatePem, QSsl::Pem);
  return certs.isEmpty() ? QString{} : QString::fromLatin1(
      certs.first().digest(QCryptographicHash::Sha256).toHex());
}

bool RemoteStationService::loadTlsConfiguration(QString *error) {
  const QByteArray certPem = m_pairedDevices.value("stationCertificatePem").toString().toLatin1();
  const auto secret = m_vault ? m_vault->read(TlsKeyAlias, error) : std::nullopt;
  if (!secret) return false;
  const auto certs = QSslCertificate::fromData(certPem, QSsl::Pem);
  const QByteArray encryptedPem = QByteArray::fromBase64(secret->toLatin1());
  const QSslKey key(encryptedPem, QSsl::Ec, QSsl::Pem, QSsl::PrivateKey);
  if (certs.isEmpty() || key.isNull()) { if (error) *error = "Station TLS identity is invalid"; return false; }
  QSslConfiguration tls = QSslConfiguration::defaultConfiguration();
  tls.setProtocol(QSsl::TlsV1_3OrLater);
  tls.setLocalCertificate(certs.first());
  tls.setPrivateKey(key);
  m_webSocketServer.setSslConfiguration(tls);
  return true;
}

bool RemoteStationService::start(QString *error) {
  stop();
  if (!m_serviceEnabled && !m_debugNonSecureLoopback) { if (error) *error = "Station Service is disabled"; return false; }
  if (!ensureIdentity(error)) return false;
  const QHostAddress address = m_lanEnabled ? QHostAddress(m_listenAddress) : QHostAddress::LocalHost;
  // Debug may bypass only the persisted enable bit on loopback; it never
  // weakens transport security or certificate identity.
  if (!loadTlsConfiguration(error)) return false;
  if (!m_webSocketServer.listen(address, m_port)) {
    if (error) *error = m_webSocketServer.errorString().left(300);
    return false;
  }
  if (m_rigctldEnabled && !m_rigctldServer.listen(address, m_rigctldPort)) {
    if (error) *error = m_rigctldServer.errorString().left(300);
    stop();
    return false;
  }
  if (m_tciEnabled && !m_tciServer.listen(address, m_tciPort)) {
    if (error) *error = m_tciServer.errorString().left(300);
    stop();
    return false;
  }
  if (m_lanEnabled && !startDiscovery(error)) { stop(); return false; }
  m_stateTimer.start(); m_expiryTimer.start();
  setState(QStringLiteral("Running · %1:%2 · read-only default · TX disarmed")
               .arg(address.toString()).arg(m_port));
  return true;
}

bool RemoteStationService::startFromUi() {
  QString message;
  const bool started = start(&message);
  if (!started) emit error(message);
  return started;
}

void RemoteStationService::stop() {
  m_stateTimer.stop(); m_expiryTimer.stop();
  globalStop();
  clearLocalAcceptance();
  for (QWebSocket *socket : m_socketSessions.keys()) socket->close(QWebSocketProtocol::CloseCodeGoingAway, "Station service stopped");
  m_socketSessions.clear();
  m_socketChallenges.clear();
  m_mediaPreferences.clear();
  m_rawIqClient = nullptr;
  m_opusPending.clear();
  m_openSockets.clear();
  m_webSocketServer.close(); m_rigctldServer.close(); m_tciServer.close();
  stopDiscovery();
  setState("Stopped · remote disconnected · TX disarmed");
  emit sessionsChanged();
}

bool RemoteStationService::startDiscovery(QString *error) {
  stopDiscovery();
  if (!m_discoverySocket.bind(QHostAddress::AnyIPv4, 5353,
      QUdpSocket::ShareAddress | QUdpSocket::ReuseAddressHint) ||
      !m_discoverySocket.joinMulticastGroup(MdnsGroup)) {
    if (error) *error = QStringLiteral("mDNS/DNS-SD publisher unavailable: %1")
        .arg(m_discoverySocket.errorString().left(240));
    stopDiscovery(); return false;
  }
  return true;
}

void RemoteStationService::stopDiscovery() {
  if (m_discoverySocket.state() == QAbstractSocket::BoundState)
    m_discoverySocket.leaveMulticastGroup(MdnsGroup);
  m_discoverySocket.close();
}

void RemoteStationService::answerDiscovery() {
  static const QByteArray serviceWire = dnsName(QStringLiteral("_shackcq._tcp.local"));
  while (m_discoverySocket.hasPendingDatagrams()) {
    QByteArray query; query.resize(static_cast<int>(qMin<qint64>(1500, m_discoverySocket.pendingDatagramSize())));
    QHostAddress sender; quint16 senderPort{};
    const qint64 received = m_discoverySocket.readDatagram(query.data(), query.size(), &sender, &senderPort);
    if (received < 12 || received > 1500 || !query.contains(serviceWire)) continue;
    const QString service = QStringLiteral("_shackcq._tcp.local");
    const QString instance = QStringLiteral("%1._shackcq._tcp.local")
        .arg(m_stationName.left(48).replace('.', '-'));
    const QString host = QStringLiteral("shackcq-%1.local").arg(m_stationId.left(12));
    QByteArray response; dns16(response, 0); dns16(response, 0x8400);
    dns16(response, 0); dns16(response, 4); dns16(response, 0); dns16(response, 0);
    dnsRecord(response, service, 12, dnsName(instance));
    QByteArray srv; dns16(srv, 0); dns16(srv, 0); dns16(srv, m_port); srv.append(dnsName(host));
    dnsRecord(response, instance, 33, srv, true);
    QByteArray txt;
    for (const QByteArray &value : {QByteArray("version=1"), QByteArray("stationId=") + m_stationId.toUtf8()}) {
      txt.append(static_cast<char>(qMin(255, value.size()))); txt.append(value.left(255));
    }
    dnsRecord(response, instance, 16, txt, true);
    quint32 ipv4 = QHostAddress(m_listenAddress).toIPv4Address();
    if (ipv4 == 0) {
      for (const QHostAddress &candidate : QNetworkInterface::allAddresses()) {
        if (candidate.protocol() == QAbstractSocket::IPv4Protocol && !candidate.isLoopback()) {
          ipv4 = candidate.toIPv4Address(); break;
        }
      }
    }
    QByteArray address; dns32(address, ipv4); dnsRecord(response, host, 1, address, true);
    if (response.size() <= 1500) m_discoverySocket.writeDatagram(response, MdnsGroup, 5353);
  }
}

remote::Role RemoteStationService::decodeRole(const QString &value, bool *ok) {
  const QString role = value.trimmed().toUpper();
  if (ok) *ok = role == "OBSERVER" || role == "OPERATOR" || role == "ADMIN";
  if (role == "OPERATOR") return remote::Role::Operator;
  if (role == "ADMIN") return remote::Role::Admin;
  return remote::Role::Observer;
}

QVariantMap RemoteStationService::createPairingOffer(const QString &roleValue) {
  bool roleOk{}; const auto role = decodeRole(roleValue, &roleOk);
  if (!roleOk || !running()) return {};
  QByteArray nonce(24, Qt::Uninitialized);
  QRandomGenerator::system()->fillRange(reinterpret_cast<quint32 *>(nonce.data()), 6);
  const QString nonceText = QString::fromLatin1(nonce.toHex());
  const quint64 expiry = static_cast<quint64>(QDateTime::currentMSecsSinceEpoch() + 120'000);
  const QString cert = m_pairedDevices.value("stationCertificatePem").toString();
  remote::PairingOffer offer{m_stationId.toStdString(),
      QStringLiteral("wss://%1:%2").arg(m_listenAddress).arg(m_port).toStdString(),
      fingerprint(cert.toLatin1()).toStdString(), nonceText.toStdString(), role, expiry};
  if (!m_authority.registerPairingOffer(offer, static_cast<quint64>(QDateTime::currentMSecsSinceEpoch()))) return {};
  return {{"version", 1}, {"stationId", m_stationId}, {"stationName", m_stationName},
          {"endpoint", QString::fromStdString(offer.endpoint)},
          {"certificateSha256", QString::fromStdString(offer.certificateSha256)},
          {"nonce", nonceText}, {"expiresAtMs", QVariant::fromValue<qulonglong>(expiry)},
          {"defaultRole", QString::fromStdString(remote::roleName(role))}};
}

bool RemoteStationService::approvePendingDevice(const QString &deviceId,
                                                const QString &roleValue) {
  const auto pending = m_pendingDevices.find(deviceId);
  bool roleOk{}; const auto role = decodeRole(roleValue, &roleOk);
  if (pending == m_pendingDevices.end() || !roleOk) return false;
  const bool accepted = m_authority.consumePairingOffer(
      pending->nonce.toStdString(), deviceId.toStdString(),
      pending->publicKeyPem.toStdString(), role,
      static_cast<quint64>(QDateTime::currentMSecsSinceEpoch()));
  if (!accepted) return false;
  m_pairedDevices[deviceId] = QVariantMap{{"role", roleValue.toUpper()},
      {"publicKeyPem", pending->publicKeyPem}, {"revoked", false},
      {"approvedAtMs", QDateTime::currentMSecsSinceEpoch()}};
  m_pendingDevices.erase(pending); emit pairingChanged(); return true;
}

void RemoteStationService::revokeDevice(const QString &deviceId) {
  m_authority.revoke(deviceId.toStdString());
  QVariantMap device = m_pairedDevices.value(deviceId).toMap();
  device["revoked"] = true; m_pairedDevices[deviceId] = device;
  for (auto it = m_socketSessions.begin(); it != m_socketSessions.end();) {
    const auto sessionRows = m_authority.sessions();
    const bool live = std::any_of(sessionRows.begin(), sessionRows.end(), [&](const auto &row) { return row.sessionId == it.value().toStdString(); });
    if (!live) { it.key()->close(QWebSocketProtocol::CloseCodePolicyViolated, "Device revoked"); it = m_socketSessions.erase(it); }
    else ++it;
  }
  emit pairingChanged(); emit sessionsChanged();
}

bool RemoteStationService::verifySignature(const QString &publicKeyPem,
                                           const QByteArray &message,
                                           const QByteArray &signature) const {
  BIO *rawBio = BIO_new_mem_buf(publicKeyPem.toLatin1().constData(), publicKeyPem.toLatin1().size());
  if (!rawBio) return false;
  EVP_PKEY *rawKey = PEM_read_bio_PUBKEY(rawBio, nullptr, nullptr, nullptr);
  BIO_free(rawBio);
  if (!rawKey) return false;
  EVP_MD_CTX *ctx = EVP_MD_CTX_new();
  const bool valid = ctx && EVP_DigestVerifyInit(ctx, nullptr, EVP_sha256(), nullptr, rawKey) == 1 &&
      EVP_DigestVerifyUpdate(ctx, message.constData(), static_cast<std::size_t>(message.size())) == 1 &&
      EVP_DigestVerifyFinal(ctx, reinterpret_cast<const unsigned char *>(signature.constData()),
                            static_cast<std::size_t>(signature.size())) == 1;
  EVP_MD_CTX_free(ctx); EVP_PKEY_free(rawKey); return valid;
}

void RemoteStationService::acceptWebSocket() {
  while (QWebSocket *socket = m_webSocketServer.nextPendingConnection()) {
    if (m_openSockets.size() >= static_cast<int>(remote::MaxSessions)) {
      socket->close(QWebSocketProtocol::CloseCodePolicyViolated, "Session limit"); socket->deleteLater(); continue;
    }
    m_openSockets.insert(socket);
    socket->setMaxAllowedIncomingFrameSize(MaxControlBytes);
    socket->setMaxAllowedIncomingMessageSize(MaxControlBytes);
    connect(socket, &QWebSocket::textMessageReceived, this, [this, socket](const QString &text) { handleText(socket, text); });
    connect(socket, &QWebSocket::binaryMessageReceived, this, [this, socket](const QByteArray &bytes) { handleBinary(socket, bytes); });
    connect(socket, &QWebSocket::disconnected, this, [this, socket] {
      const QString session = m_socketSessions.take(socket);
      m_socketChallenges.remove(socket);
      m_mediaPreferences.remove(socket);
      if (m_rawIqClient == socket) m_rawIqClient = nullptr;
      m_openSockets.remove(socket);
      if (!session.isEmpty()) m_authority.closeSession(session.toStdString());
      socket->deleteLater(); emit sessionsChanged();
    });
    QByteArray challenge(24, Qt::Uninitialized);
    QRandomGenerator::system()->fillRange(reinterpret_cast<quint32 *>(challenge.data()), 6);
    const QString authNonce = QString::fromLatin1(challenge.toHex());
    m_socketChallenges[socket] = authNonce;
    const QJsonObject hello{{"version", 1}, {"type", "HELLO"},
        {"stationId", m_stationId}, {"stationName", m_stationName},
        {"generation", QString::number(m_generation)},
        {"authNonce", authNonce},
        {"certificateSha256", fingerprint(m_pairedDevices.value("stationCertificatePem").toString().toLatin1())}};
    socket->sendTextMessage(QString::fromUtf8(QJsonDocument(hello).toJson(QJsonDocument::Compact)));
  }
}

void RemoteStationService::sendReply(QWebSocket *socket, const QJsonObject &request,
    bool ok, const QString &code, const QJsonObject &payload) {
  QJsonObject reply{{"version", 1}, {"type", "ACK"}, {"requestId", request.value("requestId")},
                    {"generation", QString::number(m_generation)}, {"ok", ok}, {"code", code},
                    {"timestampMs", QString::number(QDateTime::currentMSecsSinceEpoch())}};
  if (!payload.isEmpty()) reply["payload"] = payload;
  socket->sendTextMessage(QString::fromUtf8(QJsonDocument(reply).toJson(QJsonDocument::Compact)));
}

void RemoteStationService::handleText(QWebSocket *socket, const QString &message) {
  if (!withinRate(socket, 600)) { ++m_rejectedRequests; socket->close(QWebSocketProtocol::CloseCodePolicyViolated, "Control rate limit"); return; }
  if (message.toUtf8().size() > MaxControlBytes) { ++m_rejectedFrames; socket->close(QWebSocketProtocol::CloseCodeTooMuchData, "Control frame too large"); return; }
  QJsonParseError parseError;
  const auto document = QJsonDocument::fromJson(message.toUtf8(), &parseError);
  if (parseError.error != QJsonParseError::NoError || !document.isObject()) { ++m_rejectedRequests; socket->close(QWebSocketProtocol::CloseCodeProtocolError, "Malformed control frame"); return; }
  const QJsonObject request = document.object();
  if (request.value("version").toInt() != 1 || request.value("requestId").toString().size() > 128) {
    ++m_rejectedRequests; sendReply(socket, request, false, "UNSUPPORTED_PROTOCOL"); return;
  }
  const QString type = request.value("type").toString();
  const QJsonObject payload = request.value("payload").toObject();
  if (type == "PAIR_REQUEST") {
    const QString nonce = payload.value("nonce").toString();
    const QString deviceId = payload.value("deviceId").toString().left(128);
    const QString publicKey = payload.value("publicKeyPem").toString();
    const QByteArray challenge = (m_stationId + "|" + nonce + "|" + deviceId).toUtf8();
    const QByteArray signature = QByteArray::fromBase64(payload.value("signature").toString().toLatin1());
    if (deviceId.isEmpty() || publicKey.size() > 4096 || !verifySignature(publicKey, challenge, signature)) {
      ++m_rejectedRequests; sendReply(socket, request, false, "SIGNED_CHALLENGE_INVALID"); return;
    }
    m_pendingDevices[deviceId] = {nonce, publicKey, payload.value("requestedRole").toString("OBSERVER")};
    sendReply(socket, request, true, "LOCAL_APPROVAL_REQUIRED"); emit pairingChanged(); return;
  }
  if (type == "AUTH") {
    const QString deviceId = payload.value("deviceId").toString().left(128);
    const QVariantMap device = m_pairedDevices.value(deviceId).toMap();
    const QString publicKey = device.value("publicKeyPem").toString();
    const QString nonce = payload.value("nonce").toString().left(128);
    const QString expectedNonce = m_socketChallenges.take(socket);
    const QByteArray challenge = (m_stationId + "|auth|" + nonce + "|" + QString::number(m_generation)).toUtf8();
    if (expectedNonce.isEmpty() || nonce != expectedNonce || device.isEmpty() || device.value("revoked").toBool() ||
        !verifySignature(publicKey, challenge, QByteArray::fromBase64(payload.value("signature").toString().toLatin1()))) {
      ++m_rejectedRequests; sendReply(socket, request, false, "AUTH_FAILED"); return;
    }
    const auto session = m_authority.openSession(deviceId.toStdString(), payload.value("foreground").toBool(),
        m_generation, static_cast<quint64>(QDateTime::currentMSecsSinceEpoch()));
    if (!session) { sendReply(socket, request, false, "SESSION_LIMIT_OR_REVOKED"); return; }
    m_socketSessions[socket] = QString::fromStdString(*session);
    sendReply(socket, request, true, "AUTHENTICATED", {{"sessionId", QString::fromStdString(*session)},
        {"role", device.value("role").toString()}, {"radioRoster", QJsonArray::fromVariantList(radioRoster())},
        {"capabilities", QJsonArray{"STATE", "SPOTS", "HEALTH", "AUDIO_RX_OPUS", "AUDIO_RX_PCM16", "SPECTRUM", "WATERFALL", "IQ_OPTIONAL", "DIGI", "KEYER", "VOICE", "ROTATOR"}}});
    emit sessionsChanged(); return;
  }
  const QString session = m_socketSessions.value(socket);
  if (session.isEmpty() || request.value("sessionId").toString() != session) { ++m_rejectedRequests; sendReply(socket, request, false, "SESSION_REQUIRED"); return; }
  if (type == "HEARTBEAT") {
    const bool ok = m_authority.heartbeat(session.toStdString(), payload.value("foreground").toBool(), m_generation,
        static_cast<quint64>(QDateTime::currentMSecsSinceEpoch()));
    sendReply(socket, request, ok, ok ? "HEARTBEAT" : "STALE_GENERATION"); return;
  }
  if (type == "MEDIA_CONFIG") {
    const QString codec = payload.value("audioCodec").toString("OPUS").toUpper();
    const QString preset = payload.value("audioPreset").toString("BALANCED").toUpper();
    const int cap = qBound(12, payload.value("audioCapKbps").toInt(64), 128);
    const bool rawIq = payload.value("rawIq").toBool(false);
    const bool lowData = payload.value("lowDataMode").toBool(false);
    if (!QStringList{"OPUS", "PCM16"}.contains(codec) ||
        !QStringList{"LOW", "BALANCED", "HIGH"}.contains(preset) || lowData) {
      sendReply(socket, request, false, "MEDIA_CONFIGURATION_REJECTED"); return;
    }
    if (rawIq && (!m_rawIqHostEnabled || (m_rawIqClient && m_rawIqClient != socket))) {
      sendReply(socket, request, false, "RAW_IQ_UNAVAILABLE"); return;
    }
    if (m_rawIqClient == socket && !rawIq) m_rawIqClient = nullptr;
    if (rawIq) m_rawIqClient = socket;
    m_mediaPreferences[socket] = {{"audioCodec", codec}, {"audioPreset", preset},
                                  {"audioCapKbps", cap}, {"rawIq", rawIq}};
    sendReply(socket, request, true, "MEDIA_CONFIGURED",
        {{"audioCodec", codec}, {"audioPreset", preset}, {"audioCapKbps", cap},
         {"rawIq", rawIq}, {"frameMs", 20}});
    return;
  }
  if (type == "LEASE") {
    const QString kind = payload.value("kind").toString();
    const remote::Lease lease = kind == "TX" ? remote::Lease::Transmit : kind == "ROTATOR" ? remote::Lease::Rotator : remote::Lease::Writer;
    const bool ok = m_authority.acquire(session.toStdString(), lease,
        static_cast<quint64>(QDateTime::currentMSecsSinceEpoch()), qBound(1'000, payload.value("ttlMs").toInt(5'000), 30'000),
        lease == remote::Lease::Transmit && m_remoteTxPolicy,
        lease == remote::Lease::Rotator && m_rotatorPolicy);
    sendReply(socket, request, ok, ok ? "LEASE_GRANTED" : "LEASE_DENIED"); emit sessionsChanged(); return;
  }
  if (type == "GLOBAL_STOP") { const bool stopped = globalStop(); sendReply(socket, request, stopped, stopped ? "GLOBAL_STOPPED" : "RX_UNCONFIRMED"); return; }
  if (type == "MUTATE") {
    QString failure;
    const bool ok = executeMutation(session, payload.value("operation").toString(), payload, &failure);
    sendReply(socket, request, ok, ok ? "CONFIRMED_OR_PENDING_READBACK" : failure); return;
  }
  ++m_rejectedRequests; sendReply(socket, request, false, "UNKNOWN_MESSAGE");
}

void RemoteStationService::handleBinary(QWebSocket *socket, const QByteArray &message) {
  const auto frame = remote::decodeMedia(reinterpret_cast<const std::uint8_t *>(message.constData()), static_cast<std::size_t>(message.size()));
  const QString session = m_socketSessions.value(socket);
  if (!frame || session.isEmpty() || frame->generation != m_generation || frame->channel != remote::Channel::AudioTx) {
    ++m_rejectedFrames; socket->close(QWebSocketProtocol::CloseCodeProtocolError, "Rejected media frame"); return;
  }
  const auto rows = m_authority.sessions();
  const auto row = std::find_if(rows.begin(), rows.end(), [&](const auto &value) { return value.sessionId == session.toStdString(); });
  if (row == rows.end() || !row->transmit || !m_remoteTxPolicy) { ++m_rejectedFrames; return; }
  // Desktop TX audio is intentionally forwarded only when the existing radio
  // owner exposes an accepted TX-audio port; this base controller advertises
  // no such capability, so the bounded frame is rejected fail-closed.
  ++m_rejectedFrames;
}

bool RemoteStationService::executeMutation(const QString &sessionId,
    const QString &operation, const QJsonObject &payload, QString *failure) {
  const auto rows = m_authority.sessions();
  const auto row = std::find_if(rows.begin(), rows.end(), [&](const auto &value) { return value.sessionId == sessionId.toStdString(); });
  if (row == rows.end() || !row->writer) { if (failure) *failure = "WRITER_LEASE_REQUIRED"; return false; }
  if (!m_radio) { if (failure) *failure = "RADIO_UNAVAILABLE"; return false; }
  if (operation == "frequency") return m_radio->requestFrequency(payload.value("value").toVariant().toULongLong());
  if (operation == "mode") return m_radio->requestMode(payload.value("value").toString());
  if (operation == "radio.select") {
    if (row->role != remote::Role::Admin) { if (failure) *failure = "ADMIN_ROLE_REQUIRED"; return false; }
    return m_radio->connectTciProfile(payload.value("profileId").toString().left(128));
  }
  if (operation == "rotator.stop") { if (m_rotator) m_rotator->stop(); return m_rotator != nullptr; }
  if (operation == "rotator.prepare" && row->rotator && m_rotatorPolicy && m_rotator)
    return m_rotator->prepareTarget(payload.value("azimuth").toDouble(), payload.value("elevation").toDouble());
  if (operation == "ptt" || operation == "tune" || operation.startsWith("digi.") ||
      operation.startsWith("keyer.") || operation.startsWith("voice.")) {
    if (failure) *failure = "TX_OWNER_OR_PHYSICAL_ACCEPTANCE_UNAVAILABLE";
    return false;
  }
  if (failure) *failure = "UNSUPPORTED_OPERATION";
  return false;
}

remote::RigState RemoteStationService::rigState() const {
  remote::RigState state;
  if (m_radio) { state.frequencyHz = m_radio->frequencyHz(); state.mode = m_radio->mode().toStdString(); }
  return state;
}

void RemoteStationService::sendState() {
  const QJsonObject base{{"version", 1}, {"type", "STATE"}, {"stationId", m_stationId},
      {"generation", QString::number(m_generation)}, {"timestampMs", QString::number(QDateTime::currentMSecsSinceEpoch())},
      {"radio", QJsonObject{{"connection", m_radio ? m_radio->state() : "Unavailable"},
          {"profile", m_radio ? m_radio->model() : ""}, {"backend", m_radio ? m_radio->backend() : ""},
          {"frequencyHz", m_radio ? QJsonValue(QString::number(m_radio->frequencyHz())) : QJsonValue{}},
          {"mode", m_radio ? m_radio->mode() : ""}, {"ptt", availableValue(false, {})}, {"tune", availableValue(false, {})}}},
      {"radioRoster", QJsonArray::fromVariantList(radioRoster())},
      {"rotator", m_rotator ? QJsonObject{{"state", m_rotator->state()},
          {"azimuth", m_rotator->azimuth()}, {"elevation", m_rotator->elevation()},
          {"protocol", m_rotator->protocol()}, {"automationArmed", false}}
          : QJsonObject{{"state", "Unavailable"}, {"automationArmed", false}}}};
  const auto rows = m_authority.sessions();
  for (auto it = m_socketSessions.cbegin(); it != m_socketSessions.cend(); ++it) {
    QJsonObject state = base;
    const auto row = std::find_if(rows.begin(), rows.end(), [&](const auto &value) {
      return value.sessionId == it.value().toStdString();
    });
    if (row != rows.end()) state["leases"] = QJsonObject{{"writer", row->writer},
        {"tx", row->transmit}, {"rotator", row->rotator}, {"foreground", row->foreground}};
    it.key()->sendTextMessage(QString::fromUtf8(QJsonDocument(state).toJson(QJsonDocument::Compact)));
  }
}

void RemoteStationService::sendSpectrum(const QString &receiverId) {
  if (!m_panadapter) return;
  const auto frame = m_panadapter->renderFrame(receiverId);
  if (frame.trace.isEmpty()) return;
  const int bins = qBound(1, frame.trace.size(), 2048);
  remote::MediaFrame media{remote::Channel::Spectrum,
      static_cast<std::uint16_t>(frame.discontinuity ? 1 : 0), ++m_mediaSequence,
      static_cast<quint64>(QDateTime::currentMSecsSinceEpoch()), m_generation, {}};
  media.payload.reserve(static_cast<std::size_t>(bins + 24));
  for (int i = 0; i < bins; ++i) {
    const float db = frame.trace.at(i * frame.trace.size() / bins);
    const float normalized = static_cast<float>((db - frame.floorDb) / qMax(1.0, frame.topDb - frame.floorDb));
    media.payload.push_back(static_cast<std::uint8_t>(qBound(0, qRound(normalized * 255.0f), 255)));
  }
  const auto bytes = remote::encodeMedia(media);
  const QByteArray payload(reinterpret_cast<const char *>(bytes.data()), static_cast<int>(bytes.size()));
  for (QWebSocket *socket : m_socketSessions.keys()) socket->sendBinaryMessage(payload);
}

void RemoteStationService::sendAudio(const QString &, quint32 sampleRate,
                                     const QVector<float> &values) {
  if (values.isEmpty() || values.size() > 192'000) return;
  QList<QWebSocket *> opusSockets, pcmSockets;
  int bitrate = 128'000;
  QString preset = "HIGH";
  for (auto it = m_socketSessions.cbegin(); it != m_socketSessions.cend(); ++it) {
    const QVariantMap preference = m_mediaPreferences.value(it.key(),
        {{"audioCodec", "OPUS"}, {"audioPreset", "BALANCED"}, {"audioCapKbps", 64}});
    if (preference.value("audioCodec").toString() == "PCM16") pcmSockets << it.key();
    else {
      opusSockets << it.key();
      bitrate = qMin(bitrate, preference.value("audioCapKbps", 64).toInt() * 1000);
      if (preference.value("audioPreset").toString() == "LOW") preset = "LOW";
      else if (preference.value("audioPreset").toString() == "BALANCED" && preset != "LOW") preset = "BALANCED";
    }
  }
  if (opusSockets.isEmpty() && pcmSockets.isEmpty()) return;
  if (!pcmSockets.isEmpty()) {
    remote::MediaFrame media{remote::Channel::AudioRx, 0, ++m_mediaSequence,
      static_cast<quint64>(QDateTime::currentMSecsSinceEpoch()), m_generation, {}};
    media.payload.reserve(static_cast<std::size_t>(values.size() * 2 + 4));
    media.payload.push_back(static_cast<std::uint8_t>(sampleRate >> 24));
    media.payload.push_back(static_cast<std::uint8_t>(sampleRate >> 16));
    media.payload.push_back(static_cast<std::uint8_t>(sampleRate >> 8));
    media.payload.push_back(static_cast<std::uint8_t>(sampleRate));
    for (float value : values) {
      const qint16 pcm = static_cast<qint16>(qBound(-32768, qRound(value * 32767.0f), 32767));
      media.payload.push_back(static_cast<std::uint8_t>(pcm & 0xff));
      media.payload.push_back(static_cast<std::uint8_t>((pcm >> 8) & 0xff));
    }
    const auto bytes = remote::encodeMedia(media);
    const QByteArray payload(reinterpret_cast<const char *>(bytes.data()), static_cast<int>(bytes.size()));
    for (QWebSocket *socket : pcmSockets)
      if (socket->bytesToWrite() < 512 * 1024) socket->sendBinaryMessage(payload); else ++m_mediaDrops;
  }
  if (opusSockets.isEmpty() || !QStringList{"16000", "24000", "48000"}.contains(QString::number(sampleRate))) return;
  if (!m_opusEncoder || m_opusSampleRate != sampleRate || m_opusChannels != m_audioChannels) {
    if (m_opusEncoder) opus_encoder_destroy(static_cast<OpusEncoder *>(m_opusEncoder));
    int error{};
    m_opusEncoder = opus_encoder_create(static_cast<opus_int32>(sampleRate), m_audioChannels, OPUS_APPLICATION_AUDIO, &error);
    m_opusSampleRate = error == OPUS_OK ? sampleRate : 0;
    m_opusChannels = error == OPUS_OK ? m_audioChannels : 1;
    m_opusPending.clear();
  }
  auto *encoder = static_cast<OpusEncoder *>(m_opusEncoder);
  if (!encoder) { ++m_mediaDrops; return; }
  const int presetRate = preset == "LOW" ? 16'000 : preset == "BALANCED" ? 32'000 : 64'000;
  bitrate = qMin(bitrate, presetRate);
  if (std::any_of(opusSockets.cbegin(), opusSockets.cend(), [](QWebSocket *s) { return s->bytesToWrite() > 256 * 1024; }))
    bitrate = qMax(12'000, bitrate / 2);
  opus_encoder_ctl(encoder, OPUS_SET_BITRATE(bitrate));
  opus_encoder_ctl(encoder, OPUS_SET_COMPLEXITY(preset == "LOW" ? 3 : preset == "HIGH" ? 9 : 6));
  opus_encoder_ctl(encoder, OPUS_SET_PACKET_LOSS_PERC(10));
  opus_encoder_ctl(encoder, OPUS_SET_INBAND_FEC(1));
  m_opusPending += values;
  const int frameSamples = static_cast<int>(sampleRate / 50);
  const int frameValues = frameSamples * m_audioChannels;
  while (m_opusPending.size() >= frameValues) {
    std::array<unsigned char, 4096> packet{};
    const int encoded = opus_encode_float(encoder, m_opusPending.constData(), frameSamples,
                                           packet.data(), static_cast<opus_int32>(packet.size()));
    m_opusPending.remove(0, frameValues);
    if (encoded <= 0) { ++m_mediaDrops; continue; }
    remote::MediaFrame media{remote::Channel::AudioRx, 1, ++m_mediaSequence,
        static_cast<quint64>(QDateTime::currentMSecsSinceEpoch()), m_generation, {}};
    const quint32 audioSequence = ++m_audioSequence;
    media.payload = {static_cast<std::uint8_t>(sampleRate >> 24), static_cast<std::uint8_t>(sampleRate >> 16),
        static_cast<std::uint8_t>(sampleRate >> 8), static_cast<std::uint8_t>(sampleRate),
        static_cast<std::uint8_t>(m_audioChannels), 20,
        static_cast<std::uint8_t>(audioSequence >> 24), static_cast<std::uint8_t>(audioSequence >> 16),
        static_cast<std::uint8_t>(audioSequence >> 8), static_cast<std::uint8_t>(audioSequence)};
    media.payload.insert(media.payload.end(), packet.begin(), packet.begin() + encoded);
    const auto bytes = remote::encodeMedia(media);
    const QByteArray payload(reinterpret_cast<const char *>(bytes.data()), static_cast<int>(bytes.size()));
    for (QWebSocket *socket : opusSockets)
      if (socket->bytesToWrite() < 512 * 1024) socket->sendBinaryMessage(payload); else ++m_mediaDrops;
  }
}

void RemoteStationService::sendIq(const QString &, quint32 sampleRate,
                                  const QVector<float> &values) {
  QWebSocket *socket = m_rawIqClient;
  if (!socket || !m_rawIqHostEnabled || sampleRate > m_rawIqMaxSampleRate ||
      values.isEmpty() || values.size() > 65'000 || values.size() % 2 != 0 ||
      socket->bytesToWrite() >= 256 * 1024) { if (socket) ++m_mediaDrops; return; }
  remote::MediaFrame media{remote::Channel::IqOptional, 0, ++m_mediaSequence,
      static_cast<quint64>(QDateTime::currentMSecsSinceEpoch()), m_generation, {}};
  media.payload = {static_cast<std::uint8_t>(sampleRate >> 24), static_cast<std::uint8_t>(sampleRate >> 16),
      static_cast<std::uint8_t>(sampleRate >> 8), static_cast<std::uint8_t>(sampleRate), 2};
  media.payload.reserve(5 + static_cast<std::size_t>(values.size()) * sizeof(float));
  for (float value : values) {
    quint32 bits{};
    static_assert(sizeof(bits) == sizeof(value));
    std::memcpy(&bits, &value, sizeof(bits));
    media.payload.push_back(static_cast<std::uint8_t>(bits));
    media.payload.push_back(static_cast<std::uint8_t>(bits >> 8));
    media.payload.push_back(static_cast<std::uint8_t>(bits >> 16));
    media.payload.push_back(static_cast<std::uint8_t>(bits >> 24));
  }
  const auto bytes = remote::encodeMedia(media);
  socket->sendBinaryMessage(QByteArray(reinterpret_cast<const char *>(bytes.data()), static_cast<int>(bytes.size())));
}

void RemoteStationService::acceptRigctld() {
  while (QTcpSocket *socket = m_rigctldServer.nextPendingConnection()) {
    socket->setReadBufferSize(16 * 1024);
    connect(socket, &QTcpSocket::readyRead, this, [this, socket] { consumeRigctld(socket); });
    connect(socket, &QTcpSocket::disconnected, socket, &QObject::deleteLater);
  }
}
void RemoteStationService::consumeRigctld(QTcpSocket *socket) {
  while (socket->canReadLine()) {
    if (!withinRate(socket, 1'200)) { socket->write("RPRT -8\n"); socket->disconnectFromHost(); return; }
    const QByteArray line = socket->readLine(MaxRigLine + 1);
    if (line.size() > MaxRigLine) { socket->write("RPRT -1\n"); socket->disconnectFromHost(); return; }
    const bool writer = m_externalWriterExpiryMs > QDateTime::currentMSecsSinceEpoch();
    auto reply = remote::handleRigctld(line.toStdString(), rigState(), writer, false);
    if (reply.accepted && reply.commandClass == remote::CommandClass::SafeSet && !executeBridgeMutation(reply)) {
      reply.accepted = false; reply.errorCode = -7; reply.response = "RPRT -7\n";
    }
    socket->write(QByteArray::fromStdString(reply.response));
  }
}
void RemoteStationService::acceptTci() {
  while (QTcpSocket *socket = m_tciServer.nextPendingConnection()) {
    socket->setReadBufferSize(16 * 1024);
    socket->write("protocol:1.9;device:ShackCQ;trx_count:1;channels_count:2;ready;start;");
    connect(socket, &QTcpSocket::readyRead, this, [this, socket] { consumeTci(socket); });
    connect(socket, &QTcpSocket::disconnected, socket, &QObject::deleteLater);
  }
}
void RemoteStationService::consumeTci(QTcpSocket *socket) {
  QByteArray bytes = socket->readAll();
  if (bytes.size() > MaxRigLine) { socket->disconnectFromHost(); return; }
  const QList<QByteArray> commands = bytes.split(';');
  for (const QByteArray &raw : commands) {
    if (raw.trimmed().isEmpty()) continue;
    if (!withinRate(socket, 1'200)) { socket->disconnectFromHost(); return; }
    const bool writer = m_externalWriterExpiryMs > QDateTime::currentMSecsSinceEpoch();
    const auto reply = remote::handleTci((raw + ';').toStdString(), rigState(), writer, false);
    if (reply.accepted && reply.commandClass == remote::CommandClass::SafeSet)
      executeBridgeMutation(reply);
    if (!reply.response.empty()) socket->write(QByteArray::fromStdString(reply.response));
  }
}

bool RemoteStationService::executeBridgeMutation(const remote::ProtocolReply &reply) {
  if (!m_radio || reply.arguments.empty()) return false;
  const QString operation = QString::fromStdString(reply.operation).toUpper();
  const auto argument = [&](int index) {
    return index < static_cast<int>(reply.arguments.size())
        ? QString::fromStdString(reply.arguments[static_cast<std::size_t>(index)]) : QString{};
  };
  if (operation == "F" || operation == "SET_FREQ")
    return m_radio->requestFrequency(argument(0).toULongLong());
  if (operation == "M" || operation == "SET_MODE")
    return m_radio->requestMode(argument(0));
  if (operation == "VFO") return m_radio->requestFrequency(argument(2).toULongLong());
  if (operation == "MODULATION") return m_radio->requestMode(argument(1));
  return false;
}

void RemoteStationService::localPreempt() { m_authority.localPreempt(); ++m_generation; emit sessionsChanged(); }
bool RemoteStationService::globalStop() {
  m_authority.globalStop(); ++m_generation;
  clearLocalAcceptance();
  const bool radioStopped = !m_radio || m_radio->globalStop();
  if (m_rotator) m_rotator->stop();
  emit sessionsChanged();
  return radioStopped;
}

QVariantList RemoteStationService::sessions() const {
  QVariantList result;
  for (const auto &session : m_authority.sessions()) result.push_back(QVariantMap{
      {"sessionId", QString::fromStdString(session.sessionId)}, {"deviceId", QString::fromStdString(session.deviceId)},
      {"role", QString::fromStdString(remote::roleName(session.role))}, {"foreground", session.foreground},
      {"writer", session.writer}, {"tx", session.transmit}, {"rotator", session.rotator},
      {"lastHeartbeatMs", QVariant::fromValue<qulonglong>(session.lastHeartbeatMs)}});
  return result;
}
QVariantList RemoteStationService::pairedDevices() const {
  QVariantList result;
  for (auto it = m_pairedDevices.cbegin(); it != m_pairedDevices.cend(); ++it) {
    if (!it.value().canConvert<QVariantMap>()) continue;
    QVariantMap device = it.value().toMap(); device.remove("publicKeyPem"); device["deviceId"] = it.key(); result.push_back(device);
  }
  return result;
}
QVariantList RemoteStationService::pendingDevices() const {
  QVariantList result;
  for (auto it = m_pendingDevices.cbegin(); it != m_pendingDevices.cend(); ++it)
    result.push_back(QVariantMap{{"deviceId", it.key()}, {"requestedRole", it->requestedRole}});
  return result;
}
QVariantList RemoteStationService::radioRoster() const {
  QVariantList result;
  if (!m_radio) return result;
  result.push_back(QVariantMap{{"id", "active"}, {"name", m_radio->model()},
      {"backend", m_radio->backend()}, {"state", m_radio->state()}, {"active", true}});
  for (const QVariant &entry : m_radio->tciProfiles()) {
    const QVariantMap profile = entry.toMap();
    result.push_back(QVariantMap{{"id", profile.value("id").toString().left(128)},
        {"name", profile.value("name", "Configured TCI radio").toString().left(80)},
        {"backend", "TCI"}, {"state", "Configured · disconnected"}, {"active", false}});
  }
  return result;
}
QVariantMap RemoteStationService::health() const {
  return {{"state", m_state}, {"protocolVersion", 1}, {"stationId", m_stationId},
      {"certificateSha256", fingerprint(m_pairedDevices.value("stationCertificatePem").toString().toLatin1())},
      {"sessions", sessions()}, {"pairedDeviceCount", pairedDevices().size()},
      {"rejectedFrames", QVariant::fromValue<qulonglong>(m_rejectedFrames)},
      {"rejectedRequests", QVariant::fromValue<qulonglong>(m_rejectedRequests)},
      {"mediaDrops", QVariant::fromValue<qulonglong>(m_mediaDrops)},
      {"writerLimit", 1}, {"txLimit", 1}, {"rotatorLimit", 1},
      {"rawIqClientLimit", 1}, {"rawIqHostEnabled", m_rawIqHostEnabled},
      {"rawIqActive", m_rawIqClient != nullptr},
      {"thirdPartyWriterArmed", m_externalWriterExpiryMs > QDateTime::currentMSecsSinceEpoch()},
      {"remoteTx", "Disabled until physical acceptance and explicit session arm"},
      {"audioCodec", "Opus 20 ms adaptive within operator cap; PCM16 LAN/debug fallback"}};
}
void RemoteStationService::setState(QString state) { if (m_state == state) return; m_state = std::move(state); emit stateChanged(); }

} // namespace shackcq::desktop
