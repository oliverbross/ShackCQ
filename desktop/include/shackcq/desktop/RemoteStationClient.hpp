#pragma once

#include "shackcq/desktop/DesktopPanadapter.hpp"
#include "shackcq/desktop/DesktopPlatform.hpp"

#include <QAudioSink>
#include <QObject>
#include <QPointer>
#include <QVariantList>
#include <QWebSocket>
#include <memory>

namespace shackcq::desktop {

class RemoteStationClient final : public QObject {
  Q_OBJECT
  Q_PROPERTY(QString state READ state NOTIFY stateChanged)
  Q_PROPERTY(QString status READ status NOTIFY stateChanged)
  Q_PROPERTY(bool connected READ connected NOTIFY stateChanged)
  Q_PROPERTY(bool certificatePinned READ certificatePinned NOTIFY stateChanged)
  Q_PROPERTY(QString selectedStationId READ selectedStationId WRITE setSelectedStationId NOTIFY profilesChanged)
  Q_PROPERTY(quint64 frequencyHz READ frequencyHz NOTIFY radioStateChanged)
  Q_PROPERTY(QString mode READ mode NOTIFY radioStateChanged)
  Q_PROPERTY(bool writerLease READ writerLease NOTIFY radioStateChanged)
  Q_PROPERTY(QVariantList profiles READ profiles NOTIFY profilesChanged)
public:
  explicit RemoteStationClient(DesktopCredentialVault *vault,
                               DesktopPanadapter *panadapter,
                               QObject *parent = nullptr);
  ~RemoteStationClient() override;

  QString state() const { return m_state; }
  QString status() const { return m_status; }
  bool connected() const { return m_state == "Ready"; }
  bool certificatePinned() const { return m_certificatePinned; }
  QString selectedStationId() const { return m_selectedStationId; }
  void setSelectedStationId(const QString &value);
  quint64 frequencyHz() const { return m_frequencyHz; }
  QString mode() const { return m_mode; }
  bool writerLease() const { return m_writerLease; }
  QVariantList profiles() const { return m_profiles; }

  bool restoreConfiguration(const QVariantMap &config, QString *error = nullptr);
  QVariantMap configuration() const;
  Q_INVOKABLE bool importPairingOffer(const QString &json, const QString &requestedRole = "OBSERVER");
  Q_INVOKABLE void connectSelected();
  Q_INVOKABLE void disconnectClient();
  Q_INVOKABLE void removeSelectedProfile();
  Q_INVOKABLE void configureMedia(bool rawIq);
  Q_INVOKABLE void acquireWriter();
  Q_INVOKABLE void setRemoteFrequency(const QString &value);
  Q_INVOKABLE void setRemoteMode(const QString &value);
  Q_INVOKABLE void globalStop();
  Q_INVOKABLE QVariantMap health() const;

signals:
  void stateChanged();
  void radioStateChanged();
  void profilesChanged();
  void configurationChanged();

private:
  void setState(const QString &state, const QString &status);
  void receiveText(const QString &text);
  void receiveBinary(const QByteArray &bytes);
  void sendRequest(const QString &type, const QVariantMap &payload = {});
  void sendObject(const QJsonObject &object);
  void authenticate(const QJsonObject &hello);
  bool ensureIdentity(QString *publicKeyPem = nullptr);
  QByteArray sign(const QByteArray &challenge) const;
  void handleCertificate(bool allowSelfSigned);
  QVariantMap selectedProfile() const;
  void upsertProfile(const QVariantMap &profile);
  void startHeartbeat();
  void stopTransport();
  void playPcm(quint32 sampleRate, int channels, const QByteArray &pcm);
  static QString fingerprint(const QSslCertificate &certificate);

  DesktopCredentialVault *m_vault{};
  DesktopPanadapter *m_panadapter{};
  QWebSocket m_socket;
  QTimer *m_heartbeat{};
  std::unique_ptr<QAudioSink> m_audioSink;
  QPointer<QIODevice> m_audioDevice;
  void *m_opusDecoder{};
  quint32 m_opusRate{};
  int m_opusChannels{1};
  quint32 m_expectedAudioSequence{};
  QVariantList m_profiles;
  QVariantMap m_pairingProfile;
  QString m_pairingNonce;
  QString m_selectedStationId;
  QString m_state{"Disconnected"};
  QString m_status{"No Remote Station selected"};
  QString m_sessionId;
  QString m_role{"OBSERVER"};
  QString m_expectedFingerprint;
  QString m_deviceId;
  quint64 m_generation{};
  quint64 m_frequencyHz{};
  quint64 m_mediaFrames{};
  quint64 m_droppedFrames{};
  QString m_mode;
  bool m_writerLease{};
  bool m_certificatePinned{};
  bool m_pairing{};
};

} // namespace shackcq::desktop
