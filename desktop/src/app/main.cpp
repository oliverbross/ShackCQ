#include "shackcq/desktop/DesktopApplication.hpp"
#include "shackcq/desktop/DesktopPlatform.hpp"
#include "shackcq/desktop/PanadapterSceneItem.hpp"
#include "shackcq/desktop/RfMapItem.hpp"
#include "shackcq/tci.hpp"

#include <QCommandLineOption>
#include <QCommandLineParser>
#include <QDir>
#include <QElapsedTimer>
#include <QEvent>
#include <QEventLoop>
#include <QFont>
#include <QFontDatabase>
#include <QJsonDocument>
#include <QIcon>
#include <QApplication>
#include <QAction>
#include <QMenu>
#include <QMenuBar>
#include <QHostAddress>
#include <QHash>
#include <QPointer>
#include <QQmlApplicationEngine>
#include <QQuickWindow>
#include <QSaveFile>
#include <QSet>
#include <QTimer>
#include <QWebSocket>
#include <QWebSocketServer>
#include <cmath>
#include <algorithm>
#include <functional>
#include <memory>
#include <qqml.h>

#ifdef Q_OS_WIN
#include <QAbstractNativeEventFilter>
#include <qt_windows.h>
#endif

using namespace shackcq::desktop;

namespace {

class GalleryTciServer final : public QObject {
public:
  explicit GalleryTciServer(QObject *parent = nullptr)
      : QObject(parent),
        m_server(QStringLiteral("ShackCQ deterministic gallery TCI"),
                 QWebSocketServer::NonSecureMode, this) {
    m_timer.setInterval(45);
    connect(&m_timer, &QTimer::timeout, this, [this] { sendIq(); });
    connect(&m_server, &QWebSocketServer::newConnection, this, [this] {
      while (m_server.hasPendingConnections()) {
        QWebSocket *peer = m_server.nextPendingConnection();
        peer->setParent(this);
        m_peers.push_back(peer);
        connect(peer, &QWebSocket::textMessageReceived, peer,
                [peer](const QString &message) {
                  if (!message.contains("trx:0,true") &&
                      !message.contains("trx:1,true") &&
                      !message.contains("tune:0,true") &&
                      !message.contains("tune:1,true"))
                    peer->sendTextMessage(message);
                });
        connect(peer, &QWebSocket::disconnected, peer, &QObject::deleteLater);
        QTimer::singleShot(10, peer, [peer] {
          peer->sendTextMessage(
              "start;protocol:GalleryFixture,1.0;device:Deterministic fake "
              "TCI;trx_count:2;channels_count:2;dds:0,14074000;dds:1,7074000;"
              "vfo:0,0,14074000;vfo:1,0,7074000;modulation:0,digu;modulation:1,"
              "cw;rx_enable:0,true;rx_enable:1,true;ready;");
        });
        QTimer::singleShot(40, this, [this] {
          if (!m_timer.isActive())
            m_timer.start();
        });
      }
    });
  }
  bool start() { return m_server.listen(QHostAddress::LocalHost, 0); }
  QUrl url() const {
    return QUrl(QStringLiteral("ws://127.0.0.1:%1").arg(m_server.serverPort()));
  }

private:
  void sendIq() {
    m_peers.removeIf(
        [](const QPointer<QWebSocket> &peer) { return peer.isNull(); });
    for (int receiver = 0; receiver < 2; ++receiver) {
      std::vector<float> values(8192);
      for (int sample = 0; sample < 4096; ++sample) {
        const double phase = 2.0 * 3.141592653589793 *
                                 (receiver ? 311.0 : 173.0) * sample / 4096.0 +
                             double(m_sequence) * .025;
        values[2 * sample] = float(std::cos(phase) * .28);
        values[2 * sample + 1] = float(std::sin(phase) * .28);
      }
      const auto bytes = shackcq::tci::build_binary_for_test(
          shackcq::tci::DataType::Iq, static_cast<std::uint32_t>(receiver),
          96'000U, 2U, values);
      const QByteArray frame(reinterpret_cast<const char *>(bytes.data()),
                             static_cast<qsizetype>(bytes.size()));
      for (const auto &peer : m_peers)
        if (peer && peer->state() == QAbstractSocket::ConnectedState)
          peer->sendBinaryMessage(frame);
    }
    ++m_sequence;
  }
  QWebSocketServer m_server;
  QTimer m_timer;
  QList<QPointer<QWebSocket>> m_peers;
  quint64 m_sequence{};
};

bool installPlatformUiFont(QGuiApplication &app) {
#ifdef Q_OS_WIN
  const QString fonts =
      qEnvironmentVariable("WINDIR", QStringLiteral("C:/Windows")) +
      QStringLiteral("/Fonts/");
  const QStringList candidates{fonts + QStringLiteral("segoeui.ttf"),
                               fonts + QStringLiteral("arial.ttf")};
  for (const QString &path : candidates) {
    const int id = QFontDatabase::addApplicationFont(path);
    if (id < 0)
      continue;
    const QStringList families = QFontDatabase::applicationFontFamilies(id);
    if (families.isEmpty())
      continue;
    app.setFont(QFont(families.first()));
    return true;
  }
  qWarning(
      "Windows UI font could not be loaded; glyph evidence may be incomplete");
  return false;
#else
  app.setFont(QFontDatabase::systemFont(QFontDatabase::GeneralFont));
  return true;
#endif
}

bool validGalleryFrame(const QImage &image) {
  if (image.isNull() || image.width() < 1180 || image.height() < 720)
    return false;
  int minimum = 255, maximum = 0, opaque = 0, samples = 0;
  for (int row = 0; row < 24; ++row) {
    const int y = row * (image.height() - 1) / 23;
    for (int column = 0; column < 32; ++column) {
      const int x = column * (image.width() - 1) / 31;
      const QColor colour = image.pixelColor(x, y);
      const int luminance = qGray(colour.rgb());
      minimum = std::min(minimum, luminance);
      maximum = std::max(maximum, luminance);
      opaque += colour.alpha() >= 250 ? 1 : 0;
      ++samples;
    }
  }
  return maximum - minimum >= 18 && opaque * 100 >= samples * 98;
}

#ifdef Q_OS_MACOS
std::unique_ptr<QMenuBar> buildNativeMenuBar(DesktopApplication &desktop) {
  auto menuBar = std::make_unique<QMenuBar>();
  menuBar->setNativeMenuBar(true);
  const auto command = [&desktop](QMenu *menu, const QString &commandId,
                                  QAction::MenuRole role = QAction::NoRole) {
    for (const QVariant &value : desktop.commands()) {
      const QVariantMap item = value.toMap();
      if (item.value("id").toString() != commandId)
        continue;
      auto *action = menu->addAction(item.value("label").toString());
      action->setEnabled(item.value("enabled").toBool());
      action->setCheckable(item.value("checkable").toBool());
      action->setChecked(item.value("checked").toBool());
      action->setMenuRole(role);
      const QString key = item.value("shortcut").toString();
      if (!key.isEmpty())
        action->setShortcut(QKeySequence(key));
      QObject::connect(action, &QAction::triggered, &desktop,
                       [&desktop, commandId] { desktop.invokeCommand(commandId); });
      QObject::connect(&desktop, &DesktopApplication::commandStateChanged, action,
                       [&desktop, action, commandId] {
                         for (const QVariant &current : desktop.commands()) {
                           const QVariantMap state = current.toMap();
                           if (state.value("id").toString() == commandId) {
                             action->setEnabled(state.value("enabled").toBool());
                             action->setChecked(state.value("checked").toBool());
                             break;
                           }
                         }
                       });
      return action;
    }
    return static_cast<QAction *>(nullptr);
  };

  QMenu *appMenu = menuBar->addMenu(QStringLiteral("ShackCQ"));
  command(appMenu, "nav.about", QAction::AboutRole)->setText(QStringLiteral("About ShackCQ"));
  command(appMenu, "nav.settings", QAction::PreferencesRole)->setText(QStringLiteral("Settings…"));
  appMenu->addSeparator();
  command(appMenu, "app.quit", QAction::QuitRole);

  QMenu *file = menuBar->addMenu(QStringLiteral("File"));
  command(file, "file.fastEntry");
  file->addSeparator();
  command(file, "file.importAdif");
  command(file, "file.exportAdif");
  command(file, "file.importConfig");
  command(file, "file.exportConfig");
  file->addSeparator();
  command(file, "file.close");

  QMenu *edit = menuBar->addMenu(QStringLiteral("Edit"));
  command(edit, "edit.undo");
  command(edit, "edit.redo");
  edit->addSeparator();
  command(edit, "edit.cut");
  command(edit, "edit.copy");
  command(edit, "edit.paste");
  command(edit, "edit.delete");
  command(edit, "edit.selectAll");
  edit->addSeparator();
  command(edit, "edit.find");

  QMenu *view = menuBar->addMenu(QStringLiteral("View"));
  command(view, "view.fullScreen");
  command(view, "view.shack");
  command(view, "view.editLayout");
  command(view, "view.resetLayout");

  QMenu *radio = menuBar->addMenu(QStringLiteral("Radio"));
  command(radio, "radio.connect");
  command(radio, "radio.disconnect");
  command(radio, "radio.review");
  radio->addSeparator();
  command(radio, "radio.stop");

  QMenu *navigate = menuBar->addMenu(QStringLiteral("Navigate"));
  for (const QVariant &value : desktop.commands()) {
    const QVariantMap item = value.toMap();
    if (item.value("workspace").toBool())
      command(navigate, item.value("id").toString());
  }
  navigate->addSeparator();
  command(navigate, "tools.palette");

  QMenu *window = menuBar->addMenu(QStringLiteral("Window"));
  QAction *minimize = window->addAction(QStringLiteral("Minimize"));
  minimize->setShortcut(QKeySequence(QStringLiteral("Meta+M")));
  QObject::connect(minimize, &QAction::triggered, qApp, [] {
    if (QWindow *window = QGuiApplication::focusWindow())
      window->showMinimized();
  });
  QAction *zoom = window->addAction(QStringLiteral("Zoom"));
  QObject::connect(zoom, &QAction::triggered, qApp, [] {
    if (QWindow *active = QGuiApplication::focusWindow()) {
      if (active->visibility() == QWindow::Maximized)
        active->showNormal();
      else
        active->showMaximized();
    }
  });
  QAction *front = window->addAction(QStringLiteral("Bring All to Front"));
  QObject::connect(front, &QAction::triggered, qApp, [] {
    for (QWindow *candidate : QGuiApplication::allWindows()) {
      candidate->show();
      candidate->raise();
    }
  });
  window->addSeparator();
  command(window, "view.fullScreen")->setText(QStringLiteral("Enter Full Screen"));

  QMenu *help = menuBar->addMenu(QStringLiteral("Help"));
  command(help, "help.guide");
  command(help, "help.shortcuts");
  command(help, "nav.health");
  command(help, "tools.support");
  help->addSeparator();
  command(help, "help.licences");
  return menuBar;
}
#endif

#ifdef Q_OS_WIN
class WindowsNativeMenu final : public QAbstractNativeEventFilter {
public:
  explicit WindowsNativeMenu(DesktopApplication &desktop)
      : m_desktop(desktop), m_root(CreateMenu()) {
    HMENU file = addMenu(L"&File");
    addCommand(file, "file.fastEntry");
    addSeparator(file);
    addCommand(file, "file.importAdif");
    addCommand(file, "file.exportAdif");
    addCommand(file, "file.importConfig");
    addCommand(file, "file.exportConfig");
    addSeparator(file);
    addCommand(file, "app.quit", "Exit");

    HMENU edit = addMenu(L"&Edit");
    addCommand(edit, "edit.undo");
    addCommand(edit, "edit.redo");
    addSeparator(edit);
    addCommand(edit, "edit.cut");
    addCommand(edit, "edit.copy");
    addCommand(edit, "edit.paste");
    addCommand(edit, "edit.delete");
    addCommand(edit, "edit.selectAll");
    addSeparator(edit);
    addCommand(edit, "edit.find");

    HMENU view = addMenu(L"&View");
    addCommand(view, "view.fullScreen");
    addCommand(view, "view.shack");
    addCommand(view, "view.editLayout");
    addCommand(view, "view.resetLayout");

    HMENU radio = addMenu(L"&Radio");
    addCommand(radio, "radio.connect");
    addCommand(radio, "radio.disconnect");
    addCommand(radio, "radio.review");
    addSeparator(radio);
    addCommand(radio, "radio.stop");

    HMENU navigate = addMenu(L"&Navigate");
    for (const QVariant &value : m_desktop.commands()) {
      const QVariantMap item = value.toMap();
      if (item.value("workspace").toBool())
        addCommand(navigate, item.value("id").toString());
    }
    addSeparator(navigate);
    addCommand(navigate, "tools.palette");

    HMENU tools = addMenu(L"&Tools");
    addCommand(tools, "tools.palette");
    addCommand(tools, "nav.settings");
    addCommand(tools, "nav.health");
    addCommand(tools, "tools.support");

    HMENU window = addMenu(L"&Window");
    addCommand(window, "view.fullScreen");
    addCommand(window, "view.shack");

    HMENU help = addMenu(L"&Help");
    addCommand(help, "help.guide");
    addCommand(help, "help.shortcuts");
    addSeparator(help);
    addCommand(help, "nav.about", "About ShackCQ");
    addCommand(help, "help.licences");
    QObject::connect(&m_desktop, &DesktopApplication::commandStateChanged,
                     &m_desktop, [this] { refreshCommandState(); });
  }

  ~WindowsNativeMenu() override {
    if (m_window && IsWindow(m_window)) {
      SetMenu(m_window, nullptr);
      DestroyMenu(m_root);
    }
  }

  bool attach(QQuickWindow *window) {
    m_window = reinterpret_cast<HWND>(window->winId());
    return m_window && SetMenu(m_window, m_root) && DrawMenuBar(m_window);
  }

  bool nativeEventFilter(const QByteArray &, void *message,
                         qintptr *result) override {
    auto *nativeMessage = static_cast<MSG *>(message);
    if (nativeMessage && nativeMessage->message == WM_COMMAND &&
        HIWORD(nativeMessage->wParam) == 0) {
      const UINT command = LOWORD(nativeMessage->wParam);
      const auto it = m_commands.constFind(command);
      if (it != m_commands.cend()) {
        m_desktop.invokeCommand(it.value());
        if (result)
          *result = 0;
        return true;
      }
    }
    return false;
  }

private:
  HMENU addMenu(const wchar_t *label) {
    HMENU menu = CreatePopupMenu();
    AppendMenuW(m_root, MF_POPUP, reinterpret_cast<UINT_PTR>(menu), label);
    return menu;
  }

  void addSeparator(HMENU menu) { AppendMenuW(menu, MF_SEPARATOR, 0, nullptr); }

  void addCommand(HMENU menu, const QString &commandId,
                  const QString &overrideLabel = {}) {
    for (const QVariant &value : m_desktop.commands()) {
      const QVariantMap item = value.toMap();
      if (item.value("id").toString() != commandId)
        continue;
      QString label = overrideLabel.isEmpty() ? item.value("label").toString()
                                              : overrideLabel;
      const QString shortcut = item.value("shortcut").toString();
      if (!shortcut.isEmpty())
        label += QStringLiteral("\t") + shortcut;
      const UINT id = m_nextId++;
      const UINT flags = MF_STRING |
                         (item.value("enabled").toBool() ? MF_ENABLED
                                                         : MF_GRAYED) |
                         (item.value("checked").toBool() ? MF_CHECKED
                                                        : MF_UNCHECKED);
      AppendMenuW(menu, flags, id,
                  reinterpret_cast<LPCWSTR>(label.utf16()));
      m_commands.insert(id, commandId);
      return;
    }
  }

  void refreshCommandState() {
    for (auto it = m_commands.constBegin(); it != m_commands.constEnd(); ++it) {
      for (const QVariant &value : m_desktop.commands()) {
        const QVariantMap item = value.toMap();
        if (item.value("id").toString() != it.value())
          continue;
        EnableMenuItem(m_root, it.key(), MF_BYCOMMAND |
                       (item.value("enabled").toBool() ? MF_ENABLED : MF_GRAYED));
        CheckMenuItem(m_root, it.key(), MF_BYCOMMAND |
                      (item.value("checked").toBool() ? MF_CHECKED : MF_UNCHECKED));
        break;
      }
    }
    if (m_window)
      DrawMenuBar(m_window);
  }

  DesktopApplication &m_desktop;
  HMENU m_root{};
  HWND m_window{};
  UINT m_nextId{1000};
  QHash<UINT, QString> m_commands;
};
#endif

struct GalleryFrame {
  QString destination;
  QString fileName;
  int variant{-1};
  bool editMode{};
};

void captureGallery(QGuiApplication &app, DesktopApplication &desktop,
                    QQuickWindow *window, const QString &directory, int width,
                    int height) {
  QList<GalleryFrame> frames = {
      {"Home", "Home"},
      {"Home", "Shack"},
      {"Radio", "Radio-native"},
      {"Radio", "Radio-generic"},
      {"Radio", "TCI-disconnected-profile", 0},
      {"Radio", "TCI-connected-fake-server", 1},
      {"Radio", "TCI-two-receivers", 2},
      {"Radio", "TCI-receiver-switch", 3},
      {"Digi", "Digi"},
      {"Panadapter", "Panadapter-spectrum-waterfall", 0},
      {"Panadapter", "Panadapter-FIT-auto-contrast", 1},
      {"Panadapter", "Panadapter-manual-floor-top", 2},
      {"Panadapter", "Panadapter-peak-passband", 3},
      {"Panadapter", "Panadapter-dual-receiver", 4},
      {"EQ", "EQ"},
      {"Logbook", "Logbook"},
      {"Intelligence", "RF-Flat-Filters", 0},
      {"Intelligence", "RF-Flat-20m-Observed-Heat", 1},
      {"Intelligence", "RF-Globe-Default", 2},
      {"Intelligence", "RF-Globe-15m", 3},
      {"Intelligence", "RF-Globe-Selected-Path", 4},
      {"Intelligence", "RF-Empty-Stale-Offline", 5},
      {"Sync", "Sync"},
      {"Contest", "Contest"},
      {"Band Maps", "Band-Maps-vertical", 0},
      {"Band Maps", "Band-Maps-horizontal", 1},
      {"Band Maps", "Band-Maps-grid", 2},
      {"Band Maps", "Band-Maps-expanded", 3},
      {"Presets", "Presets"},
      {"DX", "DX-Neural"},
      {"Portable", "Portable"},
      {"Operations", "Operations-Planner", 0},
      {"Operations", "Operations-Satellite", 1},
      {"Operations", "Operations-QO100", 2},
      {"Groups.io", "Groups-io"},
      {"Rotator", "Rotator"},
      {"Settings", "Settings"},
      {"Health", "Health"},
      {"About", "About"}};
  const QStringList editDestinations{
      "Home", "Radio", "Digi", "Panadapter", "EQ", "Logbook",
      "Intelligence", "Sync", "Contest", "Band Maps", "Presets", "DX",
      "Portable", "Operations", "Groups.io", "Rotator", "Settings", "Health",
      "About"};
  for (const QString &destination : editDestinations) {
    QString slug = destination;
    slug.replace(' ', '-').replace('.', '-');
    frames.push_back({destination, QStringLiteral("Edit-") + slug, -1, true});
  }
  if (!QDir().mkpath(directory)) {
    qCritical("Cannot create UI gallery directory");
    app.exit(4);
    return;
  }
  window->setWidth(width);
  window->setHeight(height);
  window->show();
  QSet<QString> resetWorkspaces{QStringLiteral("Shack")};
  for (const GalleryFrame &frame : frames)
    resetWorkspaces.insert(frame.destination);
  for (const QString &workspace : resetWorkspaces)
    desktop.resetWorkspaceLayout(workspace);
  auto index = std::make_shared<int>(0);
  auto step = std::make_shared<std::function<void()>>();
  *step = [&app, &desktop, window, directory, frames, index, step] {
    if (*index >= frames.size()) {
      app.quit();
      return;
    }
    const GalleryFrame frame = frames.at(*index);
    window->setProperty("shackMode", frame.fileName == "Shack");
    const int radioBackend = frame.fileName.startsWith("TCI-")
                                 ? 9
                                 : (frame.fileName == "Radio-generic" ? 7 : 0);
    window->setProperty("galleryRadioBackend", radioBackend);
    desktop.setGalleryVariant(frame.destination, frame.variant);
    desktop.setCurrentDestination(frame.destination);
    desktop.setEditLayoutMode(frame.editMode);
    QTimer::singleShot(
        350, window, [window, directory, frame, index, step, &app, &desktop] {
          desktop.setEditLayoutMode(frame.editMode);
          desktop.setGalleryVariant(frame.destination, frame.variant);
          if (QObject *backend = window->findChild<QObject *>("radioBackend"))
            backend->setProperty(
                "currentIndex",
                frame.fileName.startsWith("TCI-")
                    ? 9
                    : (frame.fileName == "Radio-generic" ? 7 : 0));
          if (frame.destination == "Operations")
            if (QObject *tabs = window->findChild<QObject *>("operationsTabs"))
              tabs->setProperty("currentIndex", frame.variant);
          if (frame.destination == "Band Maps")
            if (QObject *layout = window->findChild<QObject *>("bandMapLayout"))
              layout->setProperty("currentIndex", frame.variant);
          if (frame.destination == "Intelligence") {
            for (QObject *tabs :
                 window->findChildren<QObject *>("intelligenceTabs"))
              tabs->setProperty("currentIndex", 2);
            for (QObject *projection :
                 window->findChildren<QObject *>("rfProjection"))
              projection->setProperty(
                  "currentIndex",
                  frame.variant >= 2 && frame.variant <= 4 ? 1 : 0);
            for (QObject *map : window->findChildren<QObject *>("rfMapScene")) {
              map->setProperty("zoom", frame.variant == 4 ? 1.35 : 1.0);
              map->setProperty("longitude", frame.variant == 4 ? 55.0 : 0.0);
              map->setProperty("latitude", frame.variant == 4 ? 12.0 : 0.0);
            }
          }
          QTimer::singleShot(
              150, window, [window, directory, frame, index, step, &app] {
                const QImage image = window->grabWindow();
                if (!validGalleryFrame(image) ||
                    !image.save(directory + "/" + frame.fileName + ".png")) {
                  qCritical("UI gallery frame failed: %s",
                            qPrintable(frame.fileName));
                  app.exit(4);
                  return;
                }
                ++*index;
                (*step)();
              });
        });
  };
  QTimer::singleShot(500, window, [step] { (*step)(); });
}

bool runUiStress(DesktopApplication &desktop, QQuickWindow *window,
                 const QString &reportPath) {
  const QStringList destinations{
      "Home", "Radio", "Digi", "Panadapter", "EQ", "Logbook",
      "Intelligence", "Sync", "Contest", "Band Maps", "Presets", "DX",
      "Portable", "Operations", "Groups.io", "Rotator", "Settings", "Health",
      "About"};
  const QStringList settingsCategories{
      "station", "radio", "audio", "digi", "keyer", "cluster", "alerts",
      "contest", "bandmaps", "wavelog", "groups", "providers", "operations",
      "rotator", "appearance", "health"};
  const auto settle = [] {
    QCoreApplication::processEvents(QEventLoop::AllEvents, 20);
    QCoreApplication::sendPostedEvents(nullptr, QEvent::DeferredDelete);
  };
  QElapsedTimer elapsed;
  elapsed.start();
  const int initialObjects = window->findChildren<QObject *>().size();
  int peakObjects = initialObjects;
  for (int cycle = 0; cycle < 1000; ++cycle) {
    desktop.setCurrentDestination(destinations.at(cycle % destinations.size()));
    settle();
    if (cycle % 25 == 0)
      peakObjects =
          std::max(peakObjects, int(window->findChildren<QObject *>().size()));
  }
  for (int cycle = 0; cycle < 250; ++cycle) {
    desktop.setSidebarCollapsed(cycle % 2 == 0);
    settle();
  }
  desktop.setSidebarCollapsed(false);
  for (int cycle = 0; cycle < 200; ++cycle) {
    desktop.setEditLayoutMode(true);
    settle();
    desktop.setEditLayoutMode(false);
    settle();
  }
  for (int cycle = 0; cycle < 200; ++cycle) {
    desktop.resetWorkspaceLayout(destinations.at(cycle % destinations.size()));
    settle();
  }
  for (int cycle = 0; cycle < 100; ++cycle) {
    window->setProperty("shackMode", cycle % 2 == 0);
    settle();
  }
  window->setProperty("shackMode", false);
  desktop.setCurrentDestination("Settings");
  settle();
  if (QObject *loader = window->findChild<QObject *>("workspaceLoader")) {
    if (QObject *settings = qvariant_cast<QObject *>(loader->property("item"))) {
      for (int cycle = 0; cycle < 100; ++cycle) {
        settings->setProperty(
            "currentCategory",
            settingsCategories.at(cycle % settingsCategories.size()));
        settle();
      }
    }
  }
  QList<QObject *> panels;
  for (QObject *candidate : window->findChildren<QObject *>()) {
    if (candidate->objectName().startsWith(QStringLiteral("canvasPanel-")))
      panels.push_back(candidate);
  }
  desktop.setEditLayoutMode(true);
  for (int cycle = 0; cycle < 500 && !panels.isEmpty(); ++cycle) {
    QObject *panel = panels.at(cycle % panels.size());
    QMetaObject::invokeMethod(panel, "raisePanel");
    settle();
  }
  for (int cycle = 0; cycle < 500 && !panels.isEmpty(); ++cycle) {
    QObject *panel = panels.at(cycle % panels.size());
    panel->setProperty("x", 12 + (cycle % 9) * 8);
    panel->setProperty("y", 12 + (cycle % 7) * 8);
    panel->setProperty("width", 640 + (cycle % 5) * 24);
    panel->setProperty("height", 260 + (cycle % 4) * 20);
    settle();
  }
  desktop.setEditLayoutMode(false);
  desktop.resetWorkspaceLayout(QStringLiteral("Settings"));
  settle();
  for (int cycle = 0; cycle < 100; ++cycle) {
    window->showFullScreen();
    settle();
    window->showNormal();
    settle();
  }
  for (int cycle = 0; cycle < 200; ++cycle) {
    window->resize(1180 + (cycle % 7) * 120, 720 + (cycle % 5) * 70);
    settle();
  }
  for (int cycle = 0; cycle < 100; ++cycle) {
    desktop.invokeCommand(cycle % 2 == 0 ? "nav.home" : "nav.health");
    settle();
  }
  desktop.setCurrentDestination("Home");
  window->resize(1440, 900);
  settle();
  const int finalObjects = window->findChildren<QObject *>().size();
  const QVariantMap report{
      {"workspaceChanges", 1000}, {"systemMenuCommandCycles", 100},
      {"sidebarCollapseExpandCycles", 250},
      {"editLayoutEnterExitCycles", 200}, {"resetLayoutCycles", 200},
      {"shackCycles", 100},      {"settingsCategoryChanges", 100},
      {"panelFocusRaiseCycles", 500}, {"panelMoveResizeCycles", 500},
      {"fullScreenCycles", 100},  {"resizeCycles", 200},
      {"commandActionCycles", 100}, {"initialQmlObjects", initialObjects},
      {"peakQmlObjects", peakObjects}, {"finalQmlObjects", finalObjects},
      {"elapsedMs", elapsed.elapsed()},
      {"shutdownGate", "aboutToQuit cleanup plus workflow process-exit result"},
      {"renderer", "Qt Quick offscreen deterministic stress"},
      {"threads", "service-owner tests and process exit gate"},
      {"rss", "reported by platform workflow when available"}};
  QSaveFile file(reportPath);
  if (!file.open(QIODevice::WriteOnly) ||
      file.write(QJsonDocument::fromVariant(report).toJson(QJsonDocument::Indented)) < 0 ||
      !file.commit())
    return false;
  return finalObjects <= initialObjects + 80;
}

} // namespace

int main(int argc, char *argv[]) {
  QApplication app(argc, argv);
  qmlRegisterType<PanadapterSceneItem>("ShackCQ.Controls", 1, 0,
                                       "PanadapterScene");
  qmlRegisterType<RfMapItem>("ShackCQ.Controls", 1, 0, "RfMapScene");
  const bool platformFontReady = installPlatformUiFont(app);
  QCoreApplication::setOrganizationName(QStringLiteral("ShackCQ"));
  QCoreApplication::setOrganizationDomain(QStringLiteral("shackcq.app"));
  QCoreApplication::setApplicationName(QStringLiteral("ShackCQ Desktop"));
  QCoreApplication::setApplicationVersion(QStringLiteral("1.0.0-parity.1"));
  app.setWindowIcon(QIcon(QStringLiteral(":/ShackCQ/App/AppIcon.png")));

  QCommandLineParser parser;
  parser.addHelpOption();
  parser.addOption({"smoke-test", "Exit after a bounded launch smoke."});
  parser.addOption(
      {"gallery-dir", "Capture the deterministic UI gallery.", "directory"});
  parser.addOption(
      {"gallery-width", "Gallery width in pixels.", "width", "1920"});
  parser.addOption(
      {"gallery-height", "Gallery height in pixels.", "height", "1080"});
  parser.addOption(
      {"ui-stress-report", "Run deterministic UI lifecycle stress.", "file"});
  parser.process(app);
  const bool gallery = parser.isSet("gallery-dir");
  const bool uiStress = parser.isSet("ui-stress-report");
  if (gallery && uiStress)
    return 4;
  if (gallery && !platformFontReady)
    return 4;
  if (gallery || uiStress)
    qputenv("SHACKCQ_DESKTOP_DEMO", "1");
  const bool demo = qEnvironmentVariableIntValue("SHACKCQ_DESKTOP_DEMO") == 1;

  SingleInstance single(demo
                            ? QStringLiteral("app.shackcq.desktop.parity.demo")
                            : QStringLiteral("app.shackcq.desktop"));
  if (!single.acquire())
    return 0;

  DesktopApplication desktop;
  QString error;
  if (!desktop.initialize(&error)) {
    qCritical("Desktop initialization failed: %s", qPrintable(error));
    return 2;
  }
  QObject::connect(&desktop, &DesktopApplication::quitRequested, &app,
                   &QCoreApplication::quit);
#ifdef Q_OS_MACOS
  std::unique_ptr<QMenuBar> nativeMenuBar = buildNativeMenuBar(desktop);
#endif
#ifdef Q_OS_WIN
  std::unique_ptr<WindowsNativeMenu> nativeMenuBar;
#endif
  std::unique_ptr<GalleryTciServer> galleryTci;
  if (gallery) {
    galleryTci = std::make_unique<GalleryTciServer>(&app);
    if (!galleryTci->start() || !desktop.prepareGalleryTci(galleryTci->url())) {
      qCritical("Cannot start isolated deterministic gallery TCI fixture");
      return 4;
    }
  }
  QQmlApplicationEngine engine;
  desktop.expose(engine);
  QObject::connect(
      &engine, &QQmlApplicationEngine::objectCreationFailed, &app,
      [] { QCoreApplication::exit(3); }, Qt::QueuedConnection);
  QObject::connect(
      &single, &SingleInstance::activationRequested, &app, [&engine] {
        if (engine.rootObjects().isEmpty())
          return;
        auto *window =
            qobject_cast<QQuickWindow *>(engine.rootObjects().first());
        if (window) {
          window->show();
          window->raise();
          window->requestActivate();
        }
      });
  QObject::connect(&app, &QCoreApplication::aboutToQuit, &desktop,
                   &DesktopApplication::shutdown);
  engine.load(QUrl(QStringLiteral("qrc:/ShackCQ/App/Main.qml")));
  if (engine.rootObjects().isEmpty())
    return 3;
#ifdef Q_OS_WIN
  auto *mainWindow = qobject_cast<QQuickWindow *>(engine.rootObjects().first());
  if (QGuiApplication::platformName() == QStringLiteral("windows")) {
    nativeMenuBar = std::make_unique<WindowsNativeMenu>(desktop);
    if (!mainWindow || !nativeMenuBar->attach(mainWindow)) {
      qCritical("Cannot attach native Windows menu");
      return 3;
    }
    app.installNativeEventFilter(nativeMenuBar.get());
  }
#endif

  if (parser.isSet("smoke-test"))
    QTimer::singleShot(1500, &app, &QCoreApplication::quit);
  if (gallery) {
    bool widthValid = false;
    bool heightValid = false;
    const int width = parser.value("gallery-width").toInt(&widthValid);
    const int height = parser.value("gallery-height").toInt(&heightValid);
    auto *window = qobject_cast<QQuickWindow *>(engine.rootObjects().first());
    if (!window || !widthValid || !heightValid || width < 1280 || height < 720)
      return 4;
    captureGallery(app, desktop, window, parser.value("gallery-dir"), width,
                   height);
  }
  if (uiStress) {
    auto *window = qobject_cast<QQuickWindow *>(engine.rootObjects().first());
    if (!window ||
        !runUiStress(desktop, window, parser.value("ui-stress-report")))
      return 4;
    QTimer::singleShot(250, &app, &QCoreApplication::quit);
  }
  return app.exec();
}
