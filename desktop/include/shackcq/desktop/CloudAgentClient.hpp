#pragma once

#include "shackcq/desktop/DesktopPlatform.hpp"
#include "shackcq/desktop/DesktopRadioController.hpp"

#include <QJsonObject>
#include <QObject>
#include <QTimer>
#include <QUrl>
#include <QWebSocket>

namespace shackcq::desktop {

class CloudAgentClient final : public QObject {
  Q_OBJECT
public:
  explicit CloudAgentClient(DesktopCredentialVault *vault,
                            DesktopRadioController *radio,
                            QObject *parent = nullptr);
  bool restoreConfiguration(const QVariantMap &section,
                            QString *error = nullptr);
  QVariantMap configuration() const;
  bool pair(const QUrl &origin, const QString &code, const QString &name,
            QString *error = nullptr);
  void start();
  void stop();
  QVariantMap health() const;
  QJsonObject processControlFrame(const QJsonObject &frame);

signals:
  void stateChanged();

private:
  static constexpr auto CredentialAlias = "shackcq-cloud-agent-v1";
  void connectNow();
  void receiveText(const QString &text);
  void sendHello();
  void sendSnapshot();
  void sendObject(const QJsonObject &object);
  QJsonObject capabilityDescriptor() const;
  QString deviceId() const;
  void scheduleReconnect();
  void setState(const QString &value, const QString &detail);

  DesktopCredentialVault *m_vault{};
  DesktopRadioController *m_radio{};
  QWebSocket m_socket;
  QTimer m_heartbeat;
  QTimer m_reconnect;
  QUrl m_connectUrl;
  QString m_agentId;
  QString m_credential;
  QString m_announcedDeviceId;
  QString m_state{"Unpaired"};
  QString m_detail{"No cloud Agent credential"};
  quint64 m_generation{};
  quint64 m_sequence{};
  int m_reconnectAttempt{};
  bool m_enabled{};
  bool m_stopping{};
};

} // namespace shackcq::desktop
