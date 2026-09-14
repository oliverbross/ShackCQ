#pragma once

#include "shackcq/desktop/DesktopPlatform.hpp"

#include <QHash>
#include <QJsonArray>
#include <QJsonObject>
#include <QObject>

class QUdpSocket;

namespace shackcq::desktop {

class LoggerIngestion final : public QObject {
  Q_OBJECT
public:
  explicit LoggerIngestion(DesktopCredentialVault *vault, QString journalPath,
                           QObject *parent = nullptr);
  ~LoggerIngestion() override;

  QJsonObject applyProfile(const QJsonObject &profile);
  void setAccountScope(const QString &accountId);
  QJsonObject nativeBinding() const;
  QJsonObject submitNativeContact(const QJsonObject &intent);
  QJsonObject resolveNativeEvent(const QString &eventId,
                                 const QString &action);
  void acknowledge(const QJsonArray &receipts);
  QJsonArray pendingEvents(int maximum = 32) const;
  QVariantMap health() const;

signals:
  void eventsReady();

private:
  struct Profile;
  bool loadJournal();
  bool saveJournal() const;
  bool loadProfiles();
  bool saveProfiles() const;
  bool storeEvent(const QJsonObject &event);
  void receive(Profile *profile);
  QJsonObject parseWsjt(Profile *profile, const QByteArray &packet) const;
  QJsonObject parseN1mm(Profile *profile, const QByteArray &packet) const;
  void setError(Profile *profile, const QString &code);

  DesktopCredentialVault *m_vault{};
  QString m_journalPath;
  QHash<QString, Profile *> m_profiles;
  QJsonArray m_journal;
  QJsonObject m_profileConfigs;
  QString m_lastError;
  QString m_accountId;
};

} // namespace shackcq::desktop
