// SPDX-License-Identifier: GPL-3.0-only
#pragma once

#include <QAudioDevice>
#include <QAudioFormat>
#include <QAudioSink>
#include <QAudioSource>
#include <QBuffer>
#include <QElapsedTimer>
#include <QFuture>
#include <QHash>
#include <QJsonObject>
#include <QJsonArray>
#include <QList>
#include <QObject>
#include <QTimer>
#include <QVariantMap>
#include <functional>
#include <optional>

class AgentDigiControllerTests;
namespace shackcq::desktop {
class DesktopRadioController;

class AgentDigiController final : public QObject {
  Q_OBJECT
public:
  explicit AgentDigiController(DesktopRadioController *radio,
                               QObject *parent = nullptr);
  ~AgentDigiController() override;
  bool restoreConfiguration(const QVariantMap &section, QString *error = nullptr);
  QVariantMap configuration() const;
  static QJsonArray audioDevices();
  QJsonObject processCommand(const QJsonObject &frame, const QString &agentId,
                             const QString &deviceId, quint64 generation);
  QJsonObject snapshot(const QString &agentId, const QString &deviceId,
                       quint64 generation);
  void setServerTxPermitted(bool permitted);
  void stop(const QString &reason);
  bool radioMutationBlocked() const;
  QString currentAcceptanceIdentity() const;
  bool isKx3Profile() const;

signals:
  void snapshotChanged();

private:
  friend class ::AgentDigiControllerTests;
  void setSafetyHooksForTest(std::function<bool(bool)> ptt,
                             std::function<std::optional<bool>()> readback);
  void setLocalAcceptanceForTest(bool permitted, bool accepted);
  void setAudioReadyForTest();
  enum class State { Safe, Rx, Preparing, Ready, Armed, PttConfirmed, Transmitting, Stopping, RxVerified, RxUnconfirmed };
  bool ownsLease(const QJsonObject &frame) const;
  bool configureAudio(const QString &profileId, QString *error);
  bool startRx(QString *error);
  bool prepare(const QJsonObject &parameters, QString *error);
  bool prepareSstv(const QJsonObject &parameters, QString *error);
  bool storeWaveform(const QVector<float> &samples, const QString &label, QString *error);
  bool setPtt(bool enabled);
  std::optional<bool> pttReadback() const;
  void consumeInput();
  void expireLease();
  void finishTransmit();
  bool scheduleTransmit(QString *error);
  void processDisplayAndContinuous();
  bool applyReceiveEntry(const QJsonObject &entry, QString *error);
  void scannerStep();
  void resetPrepared();
  QString stateName() const;
  QJsonObject result(const QJsonObject &frame, const QString &agentId,
                     const QString &deviceId, quint64 generation, bool ok,
                     const QString &code);

  DesktopRadioController *m_radio{};
  QAudioSource *m_source{};
  QAudioSink *m_sink{};
  QIODevice *m_input{};
  QBuffer m_output;
  QTimer m_supervisor;
  QTimer m_txFinish;
  QTimer m_scanner;
  QFuture<void> m_dspFuture;
  QElapsedTimer m_monotonic;
  QVariantMap m_profile;
  QByteArray m_txPcm;
  QVector<float> m_capture;
  QVector<float> m_displaySamples;
  QVector<float> m_continuousSamples;
  QVector<QJsonObject> m_waterfall;
  QVector<QJsonObject> m_decodes;
  QJsonObject m_sstv;
  QString m_sessionId;
  QString m_mode{"FT8"};
  QString m_submode;
  QString m_leaseBrowserSession;
  QString m_leaseControlInstance;
  QString m_preparedMessage;
  QString m_selectedDecode;
  QString m_acceptedRadioIdentity;
  QString m_preparedRadioMode;
  State m_state{State::Safe};
  quint64 m_contextGeneration{1};
  quint64 m_sequence{};
  quint64 m_sendIntentGeneration{};
  quint64 m_preparedFrequencyHz{};
  qint64 m_leaseExpiryMono{};
  qint64 m_armExpiryMono{};
  qint64 m_lastInputMono{};
  qint64 m_nextSlotEpoch{};
  qint64 m_captureSlotStart{};
  int m_messageRevision{};
  int m_maxRepeats{1};
  int m_repeatsRemaining{};
  int m_modeIndex{};
  int m_scannerIndex{};
  int m_scannerRemaining{};
  int m_sampleRate{12000};
  int m_preparedFilterHz{};
  int m_channels{1};
  float m_rxAudioHz{1500.0f};
  float m_txAudioHz{1500.0f};
  float m_rms{};
  float m_peak{};
  double m_resamplePhase{};
  bool m_clipped{};
  bool m_serverTxPermitted{};
  bool m_localTxPermitted{};
  bool m_hardwareAccepted{};
  bool m_pttOwned{};
  bool m_pttReleaseRequired{};
  bool m_sendScheduled{};
  bool m_dspInFlight{};
  bool m_slotDecodeInFlight{};
  QHash<QString, QJsonObject> m_commandResults;
  QList<QString> m_commandOrder;
  QJsonArray m_scannerEntries;
  void *m_continuousContext{};
  QString m_audioState{"NOT_SELECTED"};
  QString m_audioDetail{"No local audio profile configured"};
  std::function<bool(bool)> m_pttHook;
  std::function<std::optional<bool>()> m_readbackHook;
};
} // namespace shackcq::desktop
