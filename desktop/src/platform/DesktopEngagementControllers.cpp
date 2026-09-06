#include "shackcq/desktop/DesktopEngagementControllers.hpp"

#include <QCryptographicHash>
#include <QDateTime>
#include <QFileInfo>
#include <QApplication>
#include <QRegularExpression>
#include <QSet>

namespace shackcq::desktop {

DesktopKeyerController::DesktopKeyerController(QObject *parent)
    : QObject(parent) {
  m_templates = {{"F1", "CQ {MYCALL} {MYCALL}"},
                 {"F2", "{HISCALL} 5NN {SERIAL}"},
                 {"F3", "TU {MYCALL}"},
                 {"F4", "{MYCALL}"}};
  if (qobject_cast<QApplication *>(QCoreApplication::instance())) {
    m_audio = std::make_unique<QAudioOutput>(this);
    m_player = std::make_unique<QMediaPlayer>(this);
    m_audio->setVolume(0.8);
    m_player->setAudioOutput(m_audio.get());
  }
}

QString DesktopKeyerController::render(const QString &macroId,
                                      const QVariantMap &tokens) const {
  QString output = m_templates.value(macroId).toString();
  if (output.isEmpty())
    return {};
  static const QSet<QString> allowed{"MYCALL", "HISCALL", "SERIAL", "GRID",
                                     "REFERENCE"};
  for (auto it = tokens.cbegin(); it != tokens.cend(); ++it) {
    const QString key = it.key().trimmed().toUpper();
    if (!allowed.contains(key))
      continue;
    QString value = it.value().toString().trimmed().left(32);
    value.remove(QRegularExpression(QStringLiteral("[^A-Za-z0-9 /-]")));
    output.replace(QStringLiteral("{%1}").arg(key), value);
  }
  return output.left(256);
}

bool DesktopKeyerController::previewMacro(const QString &macroId,
                                         const QVariantMap &tokens) {
  const QString rendered = render(macroId, tokens);
  if (rendered.isEmpty()) {
    emit error("Unknown keyer macro");
    return false;
  }
  m_lastPreview = rendered;
  m_state = "PREVIEW / no keying / TX acceptance pending";
  emit previewReady(rendered);
  emit stateChanged();
  return true;
}

bool DesktopKeyerController::previewVoice(const QUrl &source) {
  const QString path = source.toLocalFile();
  const QFileInfo file(path);
  if (!source.isLocalFile() || !file.isFile() || file.size() <= 0 ||
      file.size() > 10 * 1024 * 1024) {
    emit error("Voice preview requires a local clip no larger than 10 MiB");
    return false;
  }
  if (!m_player) {
    emit error("Voice preview requires a desktop GUI session");
    return false;
  }
  m_player->stop();
  m_player->setSource(source);
  m_player->play();
  m_state = "VOICE PREVIEW / local audio only / TX acceptance pending";
  emit stateChanged();
  return true;
}

bool DesktopKeyerController::enqueueSend(const QString &macroId,
                                        const QVariantMap &tokens) {
  Q_UNUSED(macroId);
  Q_UNUSED(tokens);
  m_state = "SEND UNAVAILABLE / radio TX and audio route acceptance pending";
  emit stateChanged();
  emit error("Keyer send is unavailable until physical transmit acceptance");
  return false;
}

void DesktopKeyerController::stop() {
  if (m_player)
    m_player->stop();
  m_queue.clear();
  m_state = "STOPPED / preview available / TX acceptance pending";
  emit stateChanged();
}

QVariantMap DesktopKeyerController::configuration() const {
  return {{"schemaVersion", 1},
          {"templates", m_templates},
          {"role", "GENERAL"},
          {"repeatCq", false},
          {"queue", QVariantList{}},
          {"armed", false}};
}

bool DesktopKeyerController::restoreConfiguration(const QVariantMap &section,
                                                 QString *error) {
  if (section.value("schemaVersion", 1).toInt() > 1) {
    if (error)
      *error = "keyer schema is newer than supported schema 1";
    return false;
  }
  const QVariantMap templates = section.value("templates").toMap();
  if (templates.size() > 48) {
    if (error)
      *error = "Keyer template count exceeds 48";
    return false;
  }
  for (auto it = templates.cbegin(); it != templates.cend(); ++it) {
    if (it.key().size() > 16 || it.value().toString().size() > 256) {
      if (error)
        *error = "Invalid bounded keyer template";
      return false;
    }
  }
  if (!templates.isEmpty())
    m_templates = templates;
  stop();
  return true;
}

DesktopNotificationController::DesktopNotificationController(QObject *parent)
    : QObject(parent) {
  if (qobject_cast<QApplication *>(QCoreApplication::instance())) {
    m_tray = std::make_unique<QSystemTrayIcon>(this);
    m_tray->setIcon(QGuiApplication::windowIcon());
    m_tray->setToolTip("ShackCQ");
    if (QSystemTrayIcon::isSystemTrayAvailable())
      m_tray->show();
  }
}

void DesktopNotificationController::setProfile(const QString &value) {
  const QString next = value.trimmed().toUpper();
  if (!QSet<QString>{"DAY", "NIGHT", "FIELD"}.contains(next) ||
      next == m_profile)
    return;
  m_profile = next;
  emit profileChanged();
}

QString DesktopNotificationController::backend() const {
#ifdef Q_OS_WIN
  return QSystemTrayIcon::isSystemTrayAvailable()
             ? "Windows native shell notification / tray fallback"
             : "In-app foreground banner";
#elif defined(Q_OS_MACOS)
  return QSystemTrayIcon::isSystemTrayAvailable()
             ? "macOS Notification Center via Qt platform integration"
             : "In-app foreground banner";
#else
  return QSystemTrayIcon::isSystemTrayAvailable()
             ? "Desktop notification area"
             : "In-app foreground banner";
#endif
}

void DesktopNotificationController::deliver(const QString &title,
                                            const QString &body,
                                            bool critical) {
  const QString safeTitle = title.trimmed().left(120);
  const QString safeBody = body.trimmed().left(500);
  if (safeTitle.isEmpty() || safeBody.isEmpty())
    return;
  const qint64 now = QDateTime::currentMSecsSinceEpoch();
  const QString digest = QString::fromLatin1(
      QCryptographicHash::hash((safeTitle + "\n" + safeBody).toUtf8(),
                               QCryptographicHash::Sha256)
          .toHex());
  if (digest == m_lastDigest && now - m_lastDeliveryMs < 5000)
    return;
  m_lastDigest = digest;
  m_lastDeliveryMs = now;
  m_bannerTitle = safeTitle;
  m_bannerBody = safeBody;
  m_bannerVisible = true;
  emit bannerChanged();
  const bool quiet = m_profile == "NIGHT" && !critical;
  if (!quiet && m_tray && QSystemTrayIcon::isSystemTrayAvailable())
    m_tray->showMessage(safeTitle, safeBody,
                       critical ? QSystemTrayIcon::Critical
                                : QSystemTrayIcon::Information,
                       critical ? 10000 : 5000);
  if (!quiet && (critical || m_profile == "FIELD"))
    QApplication::beep();
}

void DesktopNotificationController::clearBanner() {
  if (!m_bannerVisible)
    return;
  m_bannerVisible = false;
  emit bannerChanged();
}

QVariantMap DesktopNotificationController::configuration() const {
  return {{"schemaVersion", 1}, {"profile", m_profile}};
}

bool DesktopNotificationController::restoreConfiguration(
    const QVariantMap &section, QString *error) {
  if (section.value("schemaVersion", 1).toInt() > 1) {
    if (error)
      *error = "alerts schema is newer than supported schema 1";
    return false;
  }
  const QString requested = section.value("profile", "DAY").toString().toUpper();
  if (!QSet<QString>{"DAY", "NIGHT", "FIELD"}.contains(requested)) {
    if (error)
      *error = "Unknown alert profile";
    return false;
  }
  m_profile = requested;
  m_bannerVisible = false;
  return true;
}

} // namespace shackcq::desktop
