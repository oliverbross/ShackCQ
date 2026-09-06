// SPDX-License-Identifier: GPL-3.0-only
#pragma once

#include <QHash>
#include <QObject>
#include <QSet>
#include <QThreadPool>
#include <QTimer>
#include <QUrl>
#include <QVariantList>
#include <QWebSocket>
#include <atomic>

namespace shackcq::desktop {

struct TciProfile {
  QString id;
  QString displayName;
  QUrl endpoint;
  quint32 preferredIqSampleRate{96'000U};
  int preferredReceiver{};
  bool autoConnect{};
  QString rxAudioOutputRoute;
};

class TciClient final : public QObject {
  Q_OBJECT
public:
  explicit TciClient(QObject *parent = nullptr);
  ~TciClient() override;

  QString state() const { return m_state; }
  QString device() const { return m_device; }
  QString protocolVersion() const { return m_protocolVersion; }
  bool ready() const { return m_ready; }
  QVariantList receivers() const;
  QVariantMap capabilities() const { return m_capabilities; }
  QVariantMap diagnostics() const;

  bool connectProfile(const TciProfile &profile);
  void disconnectFromServer();
  bool attachReceiver(int receiver);
  bool detachReceiver(int receiver);
  bool requestFrequency(int receiver, int channel, quint64 frequencyHz);
  bool requestMode(int receiver, const QString &mode);
  void globalStop();

  void setTimeoutsForTest(int connectionMs, int readyMs, int reconnectMs);

signals:
  void stateChanged();
  void receiversChanged();
  void iqFrame(int receiver, quint32 sampleRate, QVector<float> values);
  void rxAudioFrame(int receiver, quint32 sampleRate, QVector<float> values);
  void error(QString message);

private:
  void setState(QString state);
  void openSocket();
  void handleConnected();
  void handleDisconnected();
  void handleText(const QString &message);
  void handleBinary(const QByteArray &message);
  void deliverBinary(int receiver, quint32 sampleRate, int dataType,
                     QVector<float> values, int parseError, quint64 generation);
  void handleStatus(const std::string &name, const std::string &arguments);
  void markMalformed(const QString &command);
  void setReceiverCount(int count);
  bool validReceiver(int receiver) const;
  void queueMutation(const QString &key, const QString &command);
  void flushMutations();
  void send(const QString &command);
  void scheduleReconnect();
  void clearSessionState();

  QWebSocket m_socket;
  QThreadPool m_decodePool;
  QTimer m_connectionTimer;
  QTimer m_readyTimer;
  QTimer m_reconnectTimer;
  QTimer m_mutationTimer;
  TciProfile m_profile;
  QString m_state{"Disconnected"};
  QString m_device;
  QString m_protocolVersion;
  QVariantMap m_capabilities;
  QVariantList m_receivers;
  QSet<int> m_attachedReceivers;
  QHash<QString, QString> m_pendingMutations;
  bool m_ready{};
  bool m_startSeen{};
  bool m_explicitDisconnect{true};
  int m_reconnectAttempts{};
  int m_maxReconnectAttempts{3};
  quint64 m_generation{};
  quint64 m_stopSentGeneration{};
  quint64 m_unknownCommands{};
  quint64 m_malformedCommands{};
  quint64 m_malformedBinary{};
  quint64 m_droppedFrames{};
  qint64 m_lastUpdateMs{};
  std::atomic_int m_pendingBinary{};
  std::atomic_bool m_binaryDecodedOffOwnerThread{};
  static constexpr int MaxPendingBinary = 8;
};

} // namespace shackcq::desktop
