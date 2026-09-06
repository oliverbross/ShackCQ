#pragma once

#include "shackcq/desktop/ReceiverListModel.hpp"
#include "shackcq/desktop/TciClient.hpp"

#include <QAbstractListModel>
#include <QObject>
#include <QSerialPort>
#include <QTcpSocket>
#include <QTimer>
#include <optional>

namespace shackcq::desktop {

struct RadioModel {
  int id{};
  QString manufacturer;
  QString model;
  QString backend;
  QString status;
  QString transport;
};

class HamlibModelRegistry final : public QAbstractListModel {
  Q_OBJECT
  Q_PROPERTY(int count READ rowCount NOTIFY countChanged)
public:
  enum Role {
    IdRole = Qt::UserRole + 1,
    ManufacturerRole,
    ModelRole,
    BackendRole,
    StatusRole,
    TransportRole
  };
  explicit HamlibModelRegistry(QObject *parent = nullptr);
  int rowCount(const QModelIndex &parent = {}) const override;
  QVariant data(const QModelIndex &index, int role) const override;
  QHash<int, QByteArray> roleNames() const override;
  Q_INVOKABLE void setSearch(const QString &search);
  const QVector<RadioModel> &allModels() const { return m_all; }
signals:
  void countChanged();

private:
  void load();
  QString m_search;
  QVector<RadioModel> m_all;
  QVector<RadioModel> m_visible;
};

class DesktopRadioController final : public QObject {
  Q_OBJECT
  Q_PROPERTY(QString state READ state NOTIFY snapshotChanged)
  Q_PROPERTY(QString model READ model NOTIFY snapshotChanged)
  Q_PROPERTY(QString backend READ backend NOTIFY snapshotChanged)
  Q_PROPERTY(qulonglong frequencyHz READ frequencyHz NOTIFY snapshotChanged)
  Q_PROPERTY(QString mode READ mode NOTIFY snapshotChanged)
  Q_PROPERTY(int filterHz READ filterHz NOTIFY snapshotChanged)
  Q_PROPERTY(bool readOnly READ readOnly NOTIFY snapshotChanged)
  Q_PROPERTY(bool pttAvailable READ pttAvailable CONSTANT)
  Q_PROPERTY(bool tuneAvailable READ tuneAvailable CONSTANT)
  Q_PROPERTY(ReceiverListModel *receivers READ receivers CONSTANT)
  Q_PROPERTY(int receiverCount READ receiverCount NOTIFY snapshotChanged)
  Q_PROPERTY(
      QString activeReceiverId READ activeReceiverId NOTIFY snapshotChanged)
  Q_PROPERTY(QString listeningReceiverId READ listeningReceiverId NOTIFY
                 snapshotChanged)
  Q_PROPERTY(
      QString transmitReceiverId READ transmitReceiverId NOTIFY snapshotChanged)
  Q_PROPERTY(QVariantMap backendCapabilities READ backendCapabilities NOTIFY
                 snapshotChanged)
  Q_PROPERTY(
      QVariantList tciProfiles READ tciProfiles NOTIFY preferencesChanged)
public:
  explicit DesktopRadioController(QObject *parent = nullptr);
  ~DesktopRadioController() override;
  QString state() const { return m_state; }
  QString model() const { return m_model; }
  QString manufacturer() const { return m_manufacturer; }
  QString backend() const { return m_backend; }
  quint64 frequencyHz() const { return m_frequencyHz; }
  QString mode() const { return m_mode; }
  int filterHz() const { return m_filterHz; }
  int hamlibModelId() const { return m_hamlibModelId; }
  bool readOnly() const { return true; }
  bool pttAvailable() const { return false; }
  bool tuneAvailable() const { return false; }
  ReceiverListModel *receivers() { return &m_receivers; }
  int receiverCount() const { return m_receivers.rowCount(); }
  QString activeReceiverId() const { return m_activeReceiverId; }
  QString listeningReceiverId() const { return m_listeningReceiverId; }
  QString transmitReceiverId() const { return m_transmitReceiverId; }
  QVariantMap backendCapabilities() const { return m_backendCapabilities; }
  QVariantMap meters() const { return m_meters; }
  std::optional<bool> transmitting() const { return m_transmitting; }
  QVariantList tciProfiles() const { return m_tciProfiles; }
  QVariantMap hamlibProfile() const { return m_hamlibProfile; }
  QVariantMap configuration() const;
  bool restoreConfiguration(const QVariantMap &section,
                            QString *error = nullptr);
  QVariantMap health() const;

  Q_INVOKABLE bool connectRadio(int modelId, const QString &port, int baudRate);
  Q_INVOKABLE bool saveHamlibProfile(int modelId, const QString &route,
                                     int baudRate, bool autoConnect = true);
  Q_INVOKABLE void clearHamlibProfile();
  Q_INVOKABLE bool connectNativeProfile(const QString &profileId,
                                        const QString &route, int baudRate);
  Q_INVOKABLE bool connectTciProfile(const QString &profileId);
  Q_INVOKABLE bool saveTciProfile(const QVariantMap &profile);
  Q_INVOKABLE bool removeTciProfile(const QString &profileId);
  Q_INVOKABLE void startConfiguredAutoConnect();
  Q_INVOKABLE void disconnectRadio();
  Q_INVOKABLE bool selectActiveReceiver(const QString &receiverId);
  Q_INVOKABLE bool selectListeningReceiver(const QString &receiverId);
  Q_INVOKABLE QVariantMap receiverSnapshot(const QString &receiverId) const {
    return m_receivers.receiver(receiverId);
  }
  Q_INVOKABLE bool requestFrequency(qulonglong frequencyHz);
  Q_INVOKABLE bool requestMode(const QString &mode);
  Q_INVOKABLE bool requestFilter(int filterHz);
  Q_INVOKABLE void globalStop();
  void setTciTimeoutsForTest(int connectionMs, int readyMs, int reconnectMs);
  void setHamlibSnapshotForTest(quint64 frequencyHz, const QString &mode);

signals:
  void snapshotChanged();
  void preferencesChanged();
  void iqFrame(QString receiverId, quint32 sampleRate, QVector<float> values);
  void rxAudioFrame(QString receiverId, quint32 sampleRate,
                    QVector<float> values);
  void error(QString message);

private:
  void poll();
  void pollNative();
  void consumeNative(const QByteArray &bytes);
  bool writeNative(const QByteArray &frame);
  QByteArray nativeFrame(const QString &operation,
                         const QVariant &value = {}) const;
  void syncTci();
  void syncSelection();
  void syncTciAttachments();
  int activeTciIndex() const;
  static TciProfile decodeTciProfile(const QVariantMap &profile,
                                     bool *ok = nullptr);
  static QVariantMap encodeTciProfile(const TciProfile &profile);

  void *m_rig{};
  QSerialPort m_nativeSerial;
  QTcpSocket m_nativeTcp;
  QByteArray m_nativeBuffer;
  QString m_nativeProfileId;
  QTimer m_poll;
  TciClient m_tci;
  ReceiverListModel m_receivers;
  QString m_state{"Disconnected"};
  QString m_model;
  QString m_manufacturer;
  QString m_backend{"none"};
  QString m_activeReceiverId;
  QString m_listeningReceiverId;
  QString m_transmitReceiverId;
  QString m_autoConnectProfileId;
  QVariantMap m_backendCapabilities;
  QVariantMap m_meters;
  std::optional<bool> m_transmitting;
  QVariantMap m_hamlibProfile;
  QVariantMap m_legacyConfiguration;
  QVariantList m_tciProfiles;
  QVariantMap m_safeView{{"spectrumVisible", true},
                         {"waterfallVisible", true},
                         {"audioRouteEnabled", false}};
  quint64 m_frequencyHz{};
  QString m_mode;
  int m_filterHz{};
  int m_hamlibModelId{1};
  QString m_lastError;
  quint64 m_generation{};
};

} // namespace shackcq::desktop
