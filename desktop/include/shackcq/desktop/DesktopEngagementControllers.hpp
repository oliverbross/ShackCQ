#pragma once

#include <QAudioOutput>
#include <QMediaPlayer>
#include <QObject>
#include <QStringList>
#include <QSystemTrayIcon>
#include <QUrl>
#include <QVariantMap>
#include <memory>

namespace shackcq::desktop {

class DesktopKeyerController final : public QObject {
  Q_OBJECT
  Q_PROPERTY(QString state READ state NOTIFY stateChanged)
  Q_PROPERTY(QString lastPreview READ lastPreview NOTIFY previewReady)
  Q_PROPERTY(int queueDepth READ queueDepth NOTIFY stateChanged)
  Q_PROPERTY(bool sendAvailable READ sendAvailable CONSTANT)
public:
  explicit DesktopKeyerController(QObject *parent = nullptr);
  QString state() const { return m_state; }
  QString lastPreview() const { return m_lastPreview; }
  int queueDepth() const { return m_queue.size(); }
  bool sendAvailable() const { return false; }
  QVariantMap configuration() const;
  bool restoreConfiguration(const QVariantMap &section,
                            QString *error = nullptr);
  Q_INVOKABLE bool previewMacro(const QString &macroId,
                                const QVariantMap &tokens = {});
  Q_INVOKABLE bool previewVoice(const QUrl &source);
  Q_INVOKABLE bool enqueueSend(const QString &macroId,
                              const QVariantMap &tokens = {});
  Q_INVOKABLE void stop();
signals:
  void stateChanged();
  void previewReady(QString text);
  void error(QString message);
private:
  QString render(const QString &macroId, const QVariantMap &tokens) const;
  QString m_state{"STOPPED / preview available / TX acceptance pending"};
  QString m_lastPreview;
  QStringList m_queue;
  QVariantMap m_templates;
  std::unique_ptr<QMediaPlayer> m_player;
  std::unique_ptr<QAudioOutput> m_audio;
};

class DesktopNotificationController final : public QObject {
  Q_OBJECT
  Q_PROPERTY(QString bannerTitle READ bannerTitle NOTIFY bannerChanged)
  Q_PROPERTY(QString bannerBody READ bannerBody NOTIFY bannerChanged)
  Q_PROPERTY(bool bannerVisible READ bannerVisible NOTIFY bannerChanged)
  Q_PROPERTY(QString profile READ profile WRITE setProfile NOTIFY profileChanged)
  Q_PROPERTY(QString backend READ backend CONSTANT)
public:
  explicit DesktopNotificationController(QObject *parent = nullptr);
  QString bannerTitle() const { return m_bannerTitle; }
  QString bannerBody() const { return m_bannerBody; }
  bool bannerVisible() const { return m_bannerVisible; }
  QString profile() const { return m_profile; }
  void setProfile(const QString &profile);
  QString backend() const;
  QVariantMap configuration() const;
  bool restoreConfiguration(const QVariantMap &section,
                            QString *error = nullptr);
  Q_INVOKABLE void deliver(const QString &title, const QString &body,
                           bool critical = false);
  Q_INVOKABLE void clearBanner();
signals:
  void bannerChanged();
  void profileChanged();
private:
  std::unique_ptr<QSystemTrayIcon> m_tray;
  QString m_profile{"DAY"};
  QString m_bannerTitle;
  QString m_bannerBody;
  bool m_bannerVisible{};
  QString m_lastDigest;
  qint64 m_lastDeliveryMs{};
};

} // namespace shackcq::desktop
