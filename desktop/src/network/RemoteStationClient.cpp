#include "shackcq/desktop/RemoteStationClient.hpp"

#include <QAudioDevice>
#include <QCryptographicHash>
#include <QDateTime>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QMediaDevices>
#include <QNetworkRequest>
#include <QRandomGenerator>
#include <QSslError>
#include <QTimer>
#include <QUuid>
#include <QtEndian>
#include <opus.h>
#include <openssl/evp.h>
#include <openssl/pem.h>
#include <openssl/ec.h>
#include <cstring>

namespace shackcq::desktop {
namespace {
constexpr qsizetype MaxControlBytes = 64 * 1024;
constexpr qsizetype MaxMediaBytes = 256 * 1024 + 36;
QString compact(const QJsonObject &value) {
  return QString::fromUtf8(QJsonDocument(value).toJson(QJsonDocument::Compact));
}
QJsonObject baseRequest(const QString &type) {
  return {{"version", 1}, {"type", type},
          {"requestId", QUuid::createUuid().toString(QUuid::WithoutBraces)}};
}
}

RemoteStationClient::RemoteStationClient(DesktopCredentialVault *vault,
                                         DesktopPanadapter *panadapter,
                                         QObject *parent)
    : QObject(parent), m_vault(vault), m_panadapter(panadapter),
      m_heartbeat(new QTimer(this)) {
  m_heartbeat->setInterval(2000);
  connect(m_heartbeat, &QTimer::timeout, this, [this] {
    sendRequest("HEARTBEAT", {{"foreground", true}});
  });
  connect(&m_socket, &QWebSocket::connected, this, [this] {
    handleCertificate(false);
    if (!m_certificatePinned)
      stopTransport();
    else
      setState("Authenticating", "Pinned TLS session established");
  });
  connect(&m_socket, &QWebSocket::textMessageReceived, this,
          &RemoteStationClient::receiveText);
  connect(&m_socket, &QWebSocket::binaryMessageReceived, this,
          &RemoteStationClient::receiveBinary);
  connect(&m_socket, &QWebSocket::sslErrors, this,
          [this](const QList<QSslError> &) {
            handleCertificate(true);
            if (m_certificatePinned)
              m_socket.ignoreSslErrors();
          });
  connect(&m_socket, &QWebSocket::disconnected, this, [this] {
    m_heartbeat->stop(); m_sessionId.clear(); m_writerLease = false;
    if (m_state != "Failed" && m_state != "Disconnected")
      setState("Disconnected", "Remote Station disconnected");
    emit radioStateChanged();
  });
  connect(&m_socket, &QWebSocket::errorOccurred, this,
          [this](QAbstractSocket::SocketError) {
            setState("Failed", m_certificatePinned ? "Remote Station transport failed"
                                                     : "TLS certificate pin validation failed");
          });
}

RemoteStationClient::~RemoteStationClient() {
  stopTransport();
  if (m_opusDecoder) opus_decoder_destroy(static_cast<OpusDecoder *>(m_opusDecoder));
}

bool RemoteStationClient::restoreConfiguration(const QVariantMap &config,
                                                QString *error) {
  const QVariantList rows = config.value("profiles").toList();
  for (const QVariant &value : rows) {
    const QVariantMap row = value.toMap();
    const QString fp = row.value("certificateSha256").toString();
    if (row.value("stationId").toString().isEmpty() ||
        row.value("host").toString().isEmpty() || fp.size() != 64) {
      if (error) *error = "Remote Station profile is malformed";
      return false;
    }
  }
  m_profiles = rows;
  m_selectedStationId = config.value("selectedStationId").toString();
  if (selectedProfile().isEmpty() && !m_profiles.isEmpty())
    m_selectedStationId = m_profiles.first().toMap().value("stationId").toString();
  return true;
}

QVariantMap RemoteStationClient::configuration() const {
  return {{"profiles", m_profiles}, {"selectedStationId", m_selectedStationId}};
}

void RemoteStationClient::setSelectedStationId(const QString &value) {
  if (m_selectedStationId == value || m_socket.state() != QAbstractSocket::UnconnectedState) return;
  m_selectedStationId = value; emit profilesChanged(); emit configurationChanged();
}

bool RemoteStationClient::importPairingOffer(const QString &json,
                                             const QString &requestedRole) {
  QJsonParseError parse;
  const QJsonDocument document = QJsonDocument::fromJson(json.toUtf8(), &parse);
  if (parse.error != QJsonParseError::NoError || !document.isObject()) {
    setState("Failed", "Pairing offer is malformed"); return false;
  }
  const QJsonObject offer = document.object();
  const QUrl endpoint(offer.value("endpoint").toString());
  const QString fingerprint = offer.value("certificateSha256").toString().toLower();
  const qint64 expiry = offer.value("expiresAtMs").toVariant().toLongLong();
  if (endpoint.scheme() != "wss" || endpoint.host().isEmpty() ||
      fingerprint.size() != 64 || expiry <= QDateTime::currentMSecsSinceEpoch() ||
      expiry - QDateTime::currentMSecsSinceEpoch() > 10 * 60 * 1000) {
    setState("Failed", "Pairing offer is invalid or expired"); return false;
  }
  m_deviceId = m_vault ? m_vault->read("remote-client-device-id").value_or(QString{}) : QString{};
  if (m_deviceId.isEmpty()) {
    m_deviceId = QUuid::createUuid().toString(QUuid::WithoutBraces);
    QString error;
    if (!m_vault || !m_vault->write("remote-client-device-id", "ShackCQ Remote device ID", m_deviceId, &error)) {
      setState("Failed", "System credential vault is unavailable"); return false;
    }
  }
  QString publicKey;
  if (!ensureIdentity(&publicKey)) { setState("Failed", "Secure device identity is unavailable"); return false; }
  m_pairingProfile = {{"stationId", offer.value("stationId").toString()},
                      {"name", offer.value("stationName").toString()},
                      {"host", endpoint.host()}, {"port", endpoint.port(7443)},
                      {"certificateSha256", fingerprint}, {"deviceId", m_deviceId},
                      {"role", requestedRole.toUpper()}, {"publicKeyPem", publicKey}};
  m_pairingNonce = offer.value("nonce").toString();
  m_expectedFingerprint = fingerprint; m_pairing = true; m_certificatePinned = false;
  QNetworkRequest request(endpoint); request.setRawHeader("Sec-WebSocket-Protocol", "shackcq.remote.v1");
  setState("Connecting", "Submitting signed pairing request"); m_socket.open(request); return true;
}

void RemoteStationClient::connectSelected() {
  const QVariantMap row = selectedProfile();
  if (row.isEmpty() || m_socket.state() != QAbstractSocket::UnconnectedState) return;
  m_expectedFingerprint = row.value("certificateSha256").toString().toLower();
  m_deviceId = row.value("deviceId").toString(); m_certificatePinned = false; m_pairing = false;
  const QUrl url(QString("wss://%1:%2").arg(row.value("host").toString()).arg(row.value("port", 7443).toInt()));
  QNetworkRequest request(url); request.setRawHeader("Sec-WebSocket-Protocol", "shackcq.remote.v1");
  setState("Connecting", "Connecting to pinned Remote Station"); m_socket.open(request);
}

void RemoteStationClient::disconnectClient() { stopTransport(); setState("Disconnected", "Disconnected by operator"); }
void RemoteStationClient::removeSelectedProfile() {
  if (m_socket.state() != QAbstractSocket::UnconnectedState || m_selectedStationId.isEmpty()) return;
  for (qsizetype i = 0; i < m_profiles.size(); ++i) {
    if (m_profiles[i].toMap().value("stationId").toString() == m_selectedStationId) {
      m_profiles.removeAt(i); break;
    }
  }
  m_selectedStationId = m_profiles.isEmpty() ? QString{} : m_profiles.first().toMap().value("stationId").toString();
  emit profilesChanged(); emit configurationChanged();
}
void RemoteStationClient::configureMedia(bool rawIq) {
  sendRequest("MEDIA_CONFIG", {{"audioCodec", "OPUS"}, {"audioPreset", "BALANCED"},
               {"audioCapKbps", 64}, {"rawIq", rawIq}, {"lowDataMode", false}});
}
void RemoteStationClient::acquireWriter() { sendRequest("LEASE", {{"kind", "WRITER"}, {"ttlMs", 10000}}); }
void RemoteStationClient::setRemoteFrequency(const QString &value) {
  bool ok{}; const quint64 hz = value.toULongLong(&ok);
  if (!ok || !m_writerLease) { m_status = "Writer lease and valid frequency required"; emit stateChanged(); return; }
  sendRequest("MUTATE", {{"operation", "frequency"}, {"value", QString::number(hz)}});
}
void RemoteStationClient::setRemoteMode(const QString &value) {
  if (!m_writerLease || value.trimmed().isEmpty()) { m_status = "Writer lease and valid mode required"; emit stateChanged(); return; }
  sendRequest("MUTATE", {{"operation", "mode"}, {"value", value.trimmed().toUpper()}});
}
void RemoteStationClient::globalStop() { sendRequest("GLOBAL_STOP"); }

void RemoteStationClient::receiveText(const QString &text) {
  if (text.toUtf8().size() > MaxControlBytes) { setState("Failed", "Oversized control frame rejected"); stopTransport(); return; }
  QJsonParseError parse; const QJsonDocument document = QJsonDocument::fromJson(text.toUtf8(), &parse);
  if (parse.error != QJsonParseError::NoError || !document.isObject()) { setState("Failed", "Malformed control frame rejected"); return; }
  const QJsonObject message = document.object(); const QString type = message.value("type").toString();
  if (type == "HELLO") {
    if (m_pairing) {
      const QString stationId = m_pairingProfile.value("stationId").toString();
      if (message.value("stationId").toString() != stationId) { setState("Failed", "Pairing station identity mismatch"); stopTransport(); return; }
      QJsonObject request = baseRequest("PAIR_REQUEST");
      request["payload"] = QJsonObject{{"nonce", m_pairingNonce}, {"deviceId", m_deviceId},
          {"publicKeyPem", m_pairingProfile.value("publicKeyPem").toString()},
          {"signature", QString::fromLatin1(sign((stationId + "|" + m_pairingNonce + "|" + m_deviceId).toUtf8()).toBase64())},
          {"requestedRole", m_pairingProfile.value("role").toString()}};
      sendObject(request);
    } else authenticate(message);
    return;
  }
  if (type == "ACK") {
    const bool ok = message.value("ok").toBool(); const QString code = message.value("code").toString();
    if (m_pairing && ok && code == "LOCAL_APPROVAL_REQUIRED") {
      m_pairingProfile.remove("publicKeyPem"); upsertProfile(m_pairingProfile);
      setState("Disconnected", "Pairing request submitted; approve it locally at the station"); stopTransport(); return;
    }
    if (ok && code == "AUTHENTICATED") {
      const QJsonObject payload = message.value("payload").toObject();
      m_sessionId = payload.value("sessionId").toString(); m_role = payload.value("role").toString("OBSERVER");
      setState("Ready", "Secure Remote Station session authenticated");
      configureMedia(false);
      startHeartbeat(); return;
    }
    if (code == "LEASE_GRANTED") { m_writerLease = true; emit radioStateChanged(); }
    if (ok && code == "GLOBAL_STOPPED") { m_writerLease = false; m_status = "Global Stop confirmed"; emit stateChanged(); emit radioStateChanged(); }
    if (!ok && (code == "AUTH_FAILED" || code == "STALE_GENERATION" || code == "SESSION_REQUIRED")) {
      setState("Failed", "Remote session rejected: " + code); stopTransport();
    }
    return;
  }
  if (type == "STATE" && connected()) {
    m_generation = message.value("generation").toString().toULongLong();
    const QJsonObject radio = message.value("radio").toObject();
    m_frequencyHz = radio.value("frequencyHz").toString().toULongLong(); m_mode = radio.value("mode").toString();
    m_writerLease = message.value("leases").toObject().value("writer").toBool(); emit radioStateChanged();
  }
}

void RemoteStationClient::authenticate(const QJsonObject &hello) {
  const QVariantMap row = selectedProfile();
  const QString stationId = row.value("stationId").toString();
  const QString nonce = hello.value("authNonce").toString();
  const quint64 generation = hello.value("generation").toString().toULongLong();
  if (hello.value("stationId").toString() != stationId || nonce.size() != 48 ||
      hello.value("certificateSha256").toString().compare(m_expectedFingerprint, Qt::CaseInsensitive) != 0 || !ensureIdentity()) {
    setState("Failed", "Pinned station identity or challenge mismatch"); stopTransport(); return;
  }
  m_generation = generation; QJsonObject request = baseRequest("AUTH"); request["generation"] = QString::number(generation);
  const QByteArray signature = sign((stationId + "|auth|" + nonce + "|" + QString::number(generation)).toUtf8());
  request["payload"] = QJsonObject{{"deviceId", m_deviceId}, {"nonce", nonce},
      {"signature", QString::fromLatin1(signature.toBase64())}, {"foreground", true}};
  sendObject(request);
}

void RemoteStationClient::sendRequest(const QString &type, const QVariantMap &payload) {
  if (m_socket.state() != QAbstractSocket::ConnectedState || (!connected() && type != "GLOBAL_STOP")) return;
  QJsonObject request = baseRequest(type); request["stationId"] = m_selectedStationId;
  request["sessionId"] = m_sessionId; request["generation"] = QString::number(m_generation);
  request["timestampMs"] = QString::number(QDateTime::currentMSecsSinceEpoch()); request["payload"] = QJsonObject::fromVariantMap(payload);
  sendObject(request);
}
void RemoteStationClient::sendObject(const QJsonObject &object) { m_socket.sendTextMessage(compact(object)); }

void RemoteStationClient::receiveBinary(const QByteArray &bytes) {
  if (!connected() || bytes.size() < 36 || bytes.size() > MaxMediaBytes || bytes.left(4) != "RWR1") { ++m_droppedFrames; return; }
  const auto *p = reinterpret_cast<const uchar *>(bytes.constData());
  const quint16 version = qFromBigEndian<quint16>(p + 4), flags = qFromBigEndian<quint16>(p + 8);
  const quint8 channel = p[6]; const quint64 generation = qFromBigEndian<quint64>(p + 24);
  const quint32 size = qFromBigEndian<quint32>(p + 32);
  if (version != 1 || generation != m_generation || size != static_cast<quint32>(bytes.size() - 36)) { ++m_droppedFrames; return; }
  const QByteArray payload = bytes.mid(36); ++m_mediaFrames;
  if (channel == 5 && payload.size() >= 4) {
    const auto *a = reinterpret_cast<const uchar *>(payload.constData()); const quint32 rate = qFromBigEndian<quint32>(a);
    if ((flags & 1) == 0) playPcm(rate, 1, payload.mid(4));
    else if (payload.size() >= 10 && a[4] >= 1 && a[4] <= 2 && a[5] == 20) {
      const int channels = a[4]; const quint32 audioSequence = qFromBigEndian<quint32>(a + 6);
      if (!m_opusDecoder || m_opusRate != rate || m_opusChannels != channels) {
        if (m_opusDecoder) opus_decoder_destroy(static_cast<OpusDecoder *>(m_opusDecoder));
        int error{}; m_opusDecoder = opus_decoder_create(static_cast<opus_int32>(rate), channels, &error);
        m_opusRate = error == OPUS_OK ? rate : 0; m_opusChannels = channels; m_expectedAudioSequence = audioSequence;
      }
      if (m_opusDecoder) {
        const int frameSamples = static_cast<int>(rate / 50);
        QVector<qint16> pcm(static_cast<qsizetype>(frameSamples * channels));
        const quint32 gap = audioSequence - m_expectedAudioSequence;
        if (m_expectedAudioSequence != 0 && gap > 0 && gap <= 2) {
          for (quint32 missing = 0; missing < gap; ++missing) {
            const int plc = opus_decode(static_cast<OpusDecoder *>(m_opusDecoder), nullptr, 0,
                                        pcm.data(), frameSamples, 0);
            if (plc > 0) playPcm(rate, channels, QByteArray(reinterpret_cast<const char *>(pcm.constData()), plc * channels * 2));
          }
        }
        const int decoded = opus_decode(static_cast<OpusDecoder *>(m_opusDecoder), a + 10, payload.size() - 10,
                                        pcm.data(), frameSamples, 1);
        if (decoded > 0) playPcm(rate, channels, QByteArray(reinterpret_cast<const char *>(pcm.constData()), decoded * channels * 2)); else ++m_droppedFrames;
        m_expectedAudioSequence = audioSequence + 1;
      }
    }
  } else if (channel == 9 && payload.size() >= 5 && static_cast<uchar>(payload[4]) == 2 && (payload.size() - 5) % 4 == 0 && m_panadapter) {
    const auto *a = reinterpret_cast<const uchar *>(payload.constData()); const quint32 rate = qFromBigEndian<quint32>(a);
    QVector<float> iq((payload.size() - 5) / 4);
    for (qsizetype i = 0; i < iq.size(); ++i) { const quint32 bits = qFromLittleEndian<quint32>(a + 5 + i * 4); std::memcpy(&iq[i], &bits, 4); }
    m_panadapter->pushFloatIq("remote", rate, iq, m_frequencyHz, false); m_panadapter->setCurrentReceiverId("remote");
  }
}

void RemoteStationClient::playPcm(quint32 sampleRate, int channels, const QByteArray &pcm) {
  if (sampleRate < 8000 || sampleRate > 192000 || channels < 1 || channels > 2 || pcm.size() % (2 * channels)) { ++m_droppedFrames; return; }
  if (!m_audioSink || m_audioSink->format().sampleRate() != static_cast<int>(sampleRate) || m_audioSink->format().channelCount() != channels) {
    QAudioFormat format; format.setSampleRate(sampleRate); format.setChannelCount(channels); format.setSampleFormat(QAudioFormat::Int16);
    if (!QMediaDevices::defaultAudioOutput().isFormatSupported(format)) { ++m_droppedFrames; return; }
    m_audioSink = std::make_unique<QAudioSink>(QMediaDevices::defaultAudioOutput(), format, this);
    m_audioSink->setBufferSize(qMax(4096, static_cast<int>(sampleRate / 5 * 2))); m_audioDevice = m_audioSink->start();
  }
  if (!m_audioDevice || m_audioSink->bytesFree() < pcm.size()) { ++m_droppedFrames; return; }
  m_audioDevice->write(pcm);
}

bool RemoteStationClient::ensureIdentity(QString *publicKeyPem) {
  if (!m_vault || m_deviceId.isEmpty()) return false;
  const QString alias = "remote-client-p256-" + m_deviceId;
  QString privatePem = m_vault->read(alias).value_or(QString{});
  EVP_PKEY *key{};
  if (!privatePem.isEmpty()) {
    const QByteArray privateUtf8 = privatePem.toUtf8();
    BIO *bio = BIO_new_mem_buf(privateUtf8.constData(), privateUtf8.size());
    key = PEM_read_bio_PrivateKey(bio, nullptr, nullptr, nullptr); BIO_free(bio);
  }
  if (!key) {
    EVP_PKEY_CTX *ctx = EVP_PKEY_CTX_new_id(EVP_PKEY_EC, nullptr);
    if (!ctx || EVP_PKEY_keygen_init(ctx) <= 0 || EVP_PKEY_CTX_set_ec_paramgen_curve_nid(ctx, NID_X9_62_prime256v1) <= 0 || EVP_PKEY_keygen(ctx, &key) <= 0) {
      if (ctx)
        EVP_PKEY_CTX_free(ctx);
      return false;
    }
    EVP_PKEY_CTX_free(ctx); BIO *bio = BIO_new(BIO_s_mem()); PEM_write_bio_PrivateKey(bio, key, nullptr, nullptr, 0, nullptr, nullptr);
    BUF_MEM *memory{}; BIO_get_mem_ptr(bio, &memory); privatePem = QString::fromUtf8(memory->data, static_cast<int>(memory->length)); BIO_free(bio);
    QString error; if (!m_vault->write(alias, "ShackCQ Remote P-256 identity", privatePem, &error)) { EVP_PKEY_free(key); return false; }
  }
  if (publicKeyPem) { BIO *bio = BIO_new(BIO_s_mem()); PEM_write_bio_PUBKEY(bio, key); BUF_MEM *memory{}; BIO_get_mem_ptr(bio, &memory); *publicKeyPem = QString::fromUtf8(memory->data, static_cast<int>(memory->length)); BIO_free(bio); }
  EVP_PKEY_free(key); return true;
}

QByteArray RemoteStationClient::sign(const QByteArray &challenge) const {
  if (!m_vault)
    return {};
  const QString pem = m_vault->read("remote-client-p256-" + m_deviceId).value_or(QString{});
  const QByteArray utf8 = pem.toUtf8(); BIO *bio = BIO_new_mem_buf(utf8.constData(), utf8.size()); EVP_PKEY *key = PEM_read_bio_PrivateKey(bio, nullptr, nullptr, nullptr); BIO_free(bio);
  if (!key)
    return {};
  EVP_MD_CTX *ctx = EVP_MD_CTX_new(); size_t size{}; QByteArray result;
  if (EVP_DigestSignInit(ctx, nullptr, EVP_sha256(), nullptr, key) > 0 && EVP_DigestSign(ctx, nullptr, &size,
      reinterpret_cast<const uchar *>(challenge.constData()), challenge.size()) > 0) {
    result.resize(static_cast<qsizetype>(size));
    if (EVP_DigestSign(ctx, reinterpret_cast<uchar *>(result.data()), &size,
        reinterpret_cast<const uchar *>(challenge.constData()), challenge.size()) > 0) result.resize(static_cast<qsizetype>(size)); else result.clear();
  }
  EVP_MD_CTX_free(ctx); EVP_PKEY_free(key); return result;
}

void RemoteStationClient::handleCertificate(bool) {
  const QSslCertificate certificate = m_socket.sslConfiguration().peerCertificate();
  m_certificatePinned = !certificate.isNull() && fingerprint(certificate).compare(m_expectedFingerprint, Qt::CaseInsensitive) == 0;
  emit stateChanged();
}
QString RemoteStationClient::fingerprint(const QSslCertificate &certificate) { return QString::fromLatin1(QCryptographicHash::hash(certificate.toDer(), QCryptographicHash::Sha256).toHex()); }
QVariantMap RemoteStationClient::selectedProfile() const { for (const QVariant &value : m_profiles) if (value.toMap().value("stationId").toString() == m_selectedStationId) return value.toMap(); return {}; }
void RemoteStationClient::upsertProfile(const QVariantMap &profile) { for (qsizetype i = 0; i < m_profiles.size(); ++i) if (m_profiles[i].toMap().value("stationId") == profile.value("stationId")) { m_profiles[i] = profile; m_selectedStationId = profile.value("stationId").toString(); emit profilesChanged(); emit configurationChanged(); return; } m_profiles << profile; m_selectedStationId = profile.value("stationId").toString(); emit profilesChanged(); emit configurationChanged(); }
void RemoteStationClient::startHeartbeat() { m_heartbeat->start(); }
void RemoteStationClient::stopTransport() { m_heartbeat->stop(); if (m_socket.state() != QAbstractSocket::UnconnectedState) m_socket.close(QWebSocketProtocol::CloseCodeNormal, "Operator disconnect"); m_sessionId.clear(); m_writerLease = false; if (m_audioSink) m_audioSink->stop(); }
void RemoteStationClient::setState(const QString &state, const QString &status) { m_state = state; m_status = status.left(240); emit stateChanged(); }
QVariantMap RemoteStationClient::health() const { return {{"state", m_state}, {"status", m_status}, {"certificatePinned", m_certificatePinned}, {"role", m_role}, {"generation", QString::number(m_generation)}, {"writerLease", m_writerLease}, {"frequencyHz", QString::number(m_frequencyHz)}, {"mode", m_mode}, {"mediaFrames", QString::number(m_mediaFrames)}, {"droppedFrames", QString::number(m_droppedFrames)}, {"tx", "Unavailable pending policy and physical acceptance"}, {"rotatorMovement", "Unavailable pending policy and physical acceptance"}}; }

} // namespace shackcq::desktop
