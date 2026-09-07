// SPDX-License-Identifier: GPL-3.0-only
#pragma once

#include "shackcq/desktop/DesktopPanadapter.hpp"
#include "shackcq/desktop/DesktopPlatform.hpp"
#include "shackcq/desktop/DesktopRadioController.hpp"
#include "shackcq/desktop/DesktopRotatorController.hpp"
#include "shackcq/remote.h"

#include <QHash>
#include <QJsonObject>
#include <QObject>
#include <QSet>
#include <QTcpServer>
#include <QTimer>
#include <QUdpSocket>
#include <QVariantMap>
#include <QWebSocketServer>

class QTcpSocket;
class QWebSocket;

namespace shackcq::desktop {

class RemoteStationService final : public QObject {
  Q_OBJECT
  Q_PROPERTY(QString state READ state NOTIFY stateChanged)
  Q_PROPERTY(bool running READ running NOTIFY stateChanged)
  Q_PROPERTY(int sessionCount READ sessionCount NOTIFY sessionsChanged)
public:
  explicit RemoteStationService(DesktopCredentialVault *vault,
                                DesktopRadioController *radio,
                                DesktopRotatorController *rotator,
                                DesktopPanadapter *panadapter,
                                QObject *parent = nullptr);
  ~RemoteStationService() override;

  QString state() const { return m_state; }
  bool running() const { return m_webSocketServer.isListening(); }
  int sessionCount() const { return static_cast<int>(m_authority.sessions().size()); }
  Q_INVOKABLE QVariantMap configuration() const;
  bool restoreConfiguration(const QVariantMap &configuration,
                            QString *error = nullptr);
  Q_INVOKABLE QVariantMap health() const;

  Q_INVOKABLE bool ensureIdentity(QString *error = nullptr);
  Q_INVOKABLE bool start(QString *error = nullptr);
  Q_INVOKABLE bool startFromUi();
  Q_INVOKABLE void stop();
  Q_INVOKABLE QVariantMap createPairingOffer(const QString &role = "OBSERVER");
  Q_INVOKABLE bool approvePendingDevice(const QString &deviceId,
                                        const QString &role);
  Q_INVOKABLE void revokeDevice(const QString &deviceId);
  Q_INVOKABLE void localPreempt();
  Q_INVOKABLE bool globalStop();
  Q_INVOKABLE QVariantList sessions() const;
  Q_INVOKABLE QVariantList pairedDevices() const;
  Q_INVOKABLE QVariantList pendingDevices() const;
  Q_INVOKABLE QVariantList radioRoster() const;
  Q_INVOKABLE bool applyLocalSettings(const QVariantMap &settings);
  Q_INVOKABLE bool armThirdPartyWriter(int ttlMs = 30'000);
  Q_INVOKABLE void clearLocalAcceptance();
  void setDebugNonSecureLoopback(bool enabled) { m_debugNonSecureLoopback = enabled; }

signals:
  void stateChanged();
  void sessionsChanged();
  void pairingChanged();
  void error(QString message);

private:
  struct PendingDevice {
    QString nonce;
    QString publicKeyPem;
    QString requestedRole;
  };
  bool loadTlsConfiguration(QString *error);
  void acceptWebSocket();
  void handleText(QWebSocket *socket, const QString &message);
  void handleBinary(QWebSocket *socket, const QByteArray &message);
  void sendReply(QWebSocket *socket, const QJsonObject &request, bool ok,
                 const QString &code, const QJsonObject &payload = {});
  void sendState();
  void sendSpectrum(const QString &receiverId);
  void sendAudio(const QString &receiverId, quint32 sampleRate,
                 const QVector<float> &values);
  void sendIq(const QString &receiverId, quint32 sampleRate,
              const QVector<float> &values);
  void acceptRigctld();
  void consumeRigctld(QTcpSocket *socket);
  void acceptTci();
  void consumeTci(QTcpSocket *socket);
  bool startDiscovery(QString *error);
  void stopDiscovery();
  void answerDiscovery();
  bool verifySignature(const QString &publicKeyPem, const QByteArray &message,
                       const QByteArray &signature) const;
  bool executeMutation(const QString &sessionId, const QString &operation,
                       const QJsonObject &payload, QString *failure);
  bool executeBridgeMutation(const remote::ProtocolReply &reply);
  remote::RigState rigState() const;
  static remote::Role decodeRole(const QString &role, bool *ok = nullptr);
  static QString fingerprint(const QByteArray &certificatePem);
  void setState(QString state);

  DesktopCredentialVault *m_vault{};
  DesktopRadioController *m_radio{};
  DesktopRotatorController *m_rotator{};
  DesktopPanadapter *m_panadapter{};
  QWebSocketServer m_webSocketServer;
  QTcpServer m_rigctldServer;
  QTcpServer m_tciServer;
  QUdpSocket m_discoverySocket;
  QTimer m_stateTimer;
  QTimer m_expiryTimer;
  remote::SessionAuthority m_authority;
  QHash<QWebSocket *, QString> m_socketSessions;
  QHash<QWebSocket *, QString> m_socketChallenges;
  QHash<QWebSocket *, QVariantMap> m_mediaPreferences;
  QWebSocket *m_rawIqClient{};
  QSet<QWebSocket *> m_openSockets;
  QHash<QString, PendingDevice> m_pendingDevices;
  QVariantMap m_pairedDevices;
  QString m_state{"Stopped · remote disconnected · TX disarmed"};
  QString m_stationId;
  QString m_stationName{"ShackCQ Station"};
  QString m_listenAddress{"127.0.0.1"};
  quint16 m_port{7443};
  quint16 m_rigctldPort{4532};
  quint16 m_tciPort{50001};
  bool m_serviceEnabled{};
  bool m_rigctldEnabled{};
  bool m_tciEnabled{};
  bool m_lanEnabled{};
  bool m_remoteTxPolicy{};
  bool m_rotatorPolicy{};
  bool m_rawIqHostEnabled{};
  quint32 m_rawIqMaxSampleRate{96'000};
  int m_audioChannels{1};
  bool m_debugNonSecureLoopback{};
  qint64 m_externalWriterExpiryMs{};
  quint64 m_generation{1};
  quint32 m_mediaSequence{};
  quint32 m_audioSequence{};
  quint64 m_rejectedFrames{};
  quint64 m_rejectedRequests{};
  quint64 m_mediaDrops{};
  void *m_opusEncoder{};
  quint32 m_opusSampleRate{};
  int m_opusChannels{1};
  QVector<float> m_opusPending;
  static constexpr const char *TlsKeyAlias = "shackcq.remote.station.tls-key";
  static constexpr const char *SigningKeyAlias = "shackcq.remote.station.signing-key";
};

} // namespace shackcq::desktop
