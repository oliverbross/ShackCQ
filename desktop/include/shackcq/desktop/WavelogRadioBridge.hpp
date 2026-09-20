#pragma once

#include <QJsonObject>
#include <QNetworkAccessManager>
#include <QObject>
#include <QTcpServer>
#include <QTimer>
#include <QUrl>
#include <functional>

namespace shackcq::desktop {

class DesktopRadioController;

class WavelogRadioBridge final : public QObject {
  Q_OBJECT
  Q_PROPERTY(QString state READ state NOTIFY stateChanged)
  Q_PROPERTY(QVariantMap capabilities READ capabilities NOTIFY stateChanged)
  Q_PROPERTY(QString callbackUrl READ callbackUrl NOTIFY stateChanged)
public:
  explicit WavelogRadioBridge(DesktopRadioController *radio, QObject *parent=nullptr);
  QString state() const { return m_state; }
  QString callbackUrl() const;
  QVariantMap capabilities() const;
  Q_INVOKABLE bool configure(const QString &serverUrl,const QString &credentialAlias,const QString &radioName,const QString &apiMode);
  Q_INVOKABLE void stop();
  void setCredentialResolver(std::function<QString(const QString&)> resolver){m_credentialResolver=std::move(resolver);}
  static QJsonObject radioState(const QString &radioName,quint64 frequencyHz,const QString &mode,const QUrl &callback={});
  static bool parseTunePath(const QString &path,const QString &nonce,quint64 *frequencyHz,QString *mode);
  static bool acceptsLoopbackHost(const QByteArray &request);
signals:
  void stateChanged();
  void error(QString message);
private:
  void publish();
  void acceptConnections();
  void setState(const QString &state);
  DesktopRadioController *m_radio{};
  QNetworkAccessManager m_network;
  QTcpServer m_server;
  QTimer m_publishTimer;
  std::function<QString(const QString&)> m_credentialResolver;
  QUrl m_serverUrl;
  QString m_credentialAlias;
  QString m_radioName;
  QString m_apiMode{"AUTO"};
  QString m_nonce;
  QString m_state{"Disabled"};
  qint64 m_lastTuneMs{};
};

} // namespace shackcq::desktop
