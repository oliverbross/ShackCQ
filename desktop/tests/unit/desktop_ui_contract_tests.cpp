#include "shackcq/desktop/DesktopApplication.hpp"

#include <QFile>
#include <QDirIterator>
#include <QSet>
#include <QtTest>

#include <algorithm>

using namespace shackcq::desktop;

class DesktopUiContractTests final : public QObject {
  Q_OBJECT
private slots:
  void commandRegistryIsCompleteAndUnique();
  void shellUsesCanonicalCommandsAndNativeMenus();
  void routedWorkspacesUseOfficialLayoutsWithExplicitEditing();
  void originalIconFamilyCoversEveryWorkspaceDestination();
  void macosAgentUsesUnambiguousRuntimeAndRadioStates();
};

void DesktopUiContractTests::commandRegistryIsCompleteAndUnique() {
  DesktopApplication desktop;
  const QVariantList commands = desktop.commands();
  QSet<QString> ids;
  QSet<QString> destinations;
  int workspaceCount = 0;
  for (const QVariant &value : commands) {
    const QVariantMap command = value.toMap();
    const QString id = command.value("id").toString();
    QVERIFY2(!id.isEmpty(), "Every command needs a stable ID");
    QVERIFY2(!ids.contains(id), qPrintable(QString("Duplicate command: %1").arg(id)));
    ids.insert(id);
    QVERIFY(!command.value("label").toString().isEmpty());
    QVERIFY(!command.value("icon").toString().isEmpty());
    QVERIFY(!command.value("description").toString().isEmpty());
    if (command.value("workspace").toBool()) {
      ++workspaceCount;
      destinations.insert(command.value("destination").toString());
      QVERIFY(!command.value("category").toString().isEmpty());
    }
  }
  QCOMPARE(workspaceCount, 19);
  QCOMPARE(destinations.size(), 19);
  for (const QString &required : {QStringLiteral("radio.stop"),
                                  QStringLiteral("tools.palette"),
                                  QStringLiteral("file.fastEntry"),
                                  QStringLiteral("view.fullScreen"),
                                  QStringLiteral("view.editLayout"),
                                  QStringLiteral("view.resetLayout")})
    QVERIFY2(ids.contains(required), qPrintable(required));
  const auto edit = std::find_if(commands.cbegin(), commands.cend(),
                                 [](const QVariant &value) {
    return value.toMap().value("id").toString() == "view.editLayout";
  });
  QVERIFY(edit != commands.cend());
  QVERIFY(edit->toMap().value("checkable").toBool());
  QVERIFY(!edit->toMap().value("checked").toBool());
  desktop.invokeCommand("view.editLayout");
  QVERIFY(desktop.editLayoutMode());
  desktop.setEditLayoutMode(false);
}

void DesktopUiContractTests::shellUsesCanonicalCommandsAndNativeMenus() {
  QFile file(QStringLiteral(SHACKCQ_DESKTOP_QML_DIR "/App/Main.qml"));
  QVERIFY(file.open(QIODevice::ReadOnly));
  const QByteArray qml = file.readAll();
  QVERIFY(qml.contains("Desktop.invokeCommand"));
  QVERIFY(qml.contains("Qt.platform.os === \"osx\""));
  QVERIFY(!qml.contains("menuBar: MenuBar"));
  QVERIFY(qml.contains("WorkspaceSidebar"));
  QVERIFY(qml.contains("EDIT LAYOUT"));
  QVERIFY(qml.contains("Done Editing"));
  QVERIFY(!qml.contains("SplitView"));
  QFile appSource(QStringLiteral(SHACKCQ_DESKTOP_APP_DIR "/main.cpp"));
  QVERIFY(appSource.open(QIODevice::ReadOnly));
  const QByteArray nativeMenus = appSource.readAll();
  QVERIFY(nativeMenus.contains("buildNativeMenuBar"));
  QVERIFY(nativeMenus.contains("WindowsNativeMenu"));
  QVERIFY(nativeMenus.contains("addMenu(L\"&File\")"));
  QVERIFY(nativeMenus.contains("addMenu(L\"&Navigate\")"));
  QVERIFY(nativeMenus.contains("view.editLayout"));
  QVERIFY(qml.contains("Accessible.name"));
  QVERIFY(!qml.contains("ShackCQ Windows Desktop"));
}

void DesktopUiContractTests::routedWorkspacesUseOfficialLayoutsWithExplicitEditing() {
  QFile canvas(QStringLiteral(SHACKCQ_DESKTOP_QML_DIR
                              "/Components/WorkspaceCanvas.qml"));
  QFile panel(QStringLiteral(SHACKCQ_DESKTOP_QML_DIR
                             "/Components/CanvasPanel.qml"));
  QVERIFY(canvas.open(QIODevice::ReadOnly));
  QVERIFY(panel.open(QIODevice::ReadOnly));
  const QByteArray canvasQml = canvas.readAll();
  const QByteArray panelQml = panel.readAll();
  QVERIFY(canvasQml.contains("workspaceKey"));
  QVERIFY(canvasQml.contains("onWorkspaceLayoutReset"));
  QVERIFY(canvasQml.contains("official workspace layout"));
  QVERIFY(canvasQml.contains("panelOverlaps"));
  QVERIFY(canvasQml.contains("gridSize: 8"));
  QVERIFY(panelQml.contains("readonly property bool editable"));
  QVERIFY(panelQml.contains("root.editable ? ["));
  QVERIFY(panelQml.contains("layoutVersion"));
  QVERIFY(panelQml.contains("widthRatio"));
  QVERIFY(panelQml.contains("Desktop.savePanelGeometry"));
  QVERIFY(panelQml.contains("usingSavedGeometry"));
  QVERIFY(panelQml.contains("intendedGeometry"));
  QVERIFY(panelQml.contains("applyUserGeometry"));
  QVERIFY(panelQml.contains("saved.stored === true"));
  QVERIFY(panelQml.contains("edges.left"));
  QVERIFY(panelQml.contains("edges.right"));
  QVERIFY(panelQml.contains("edges.top"));
  QVERIFY(panelQml.contains("edges.bottom"));

  const QStringList routedPages{
      "Home/HomePage.qml",       "Radio/RadioPage.qml",
      "Digi/DigiPage.qml",       "Panadapter/PanadapterPage.qml",
      "EQ/EqPage.qml",           "Logbook/LogbookPage.qml",
      "Intelligence/IntelligencePage.qml", "Sync/SyncPage.qml",
      "Contest/ContestPage.qml", "BandMaps/BandMapsPage.qml",
      "Presets/PresetsPage.qml", "DX/DxPage.qml",
      "Portable/PortablePage.qml", "Operations/OperationsPage.qml",
      "Groups/GroupsPage.qml",   "Rotator/RotatorPage.qml",
      "Settings/SettingsPage.qml", "Health/HealthPage.qml",
      "Settings/AboutPage.qml",  "Home/ShackDisplay.qml"};
  for (const QString &relative : routedPages) {
    QFile page(QStringLiteral(SHACKCQ_DESKTOP_QML_DIR "/") + relative);
    QVERIFY2(page.open(QIODevice::ReadOnly), qPrintable(relative));
    const QByteArray source = page.readAll();
    QVERIFY2(source.contains("WorkspaceCanvas"), qPrintable(relative));
    QVERIFY2(source.contains("CanvasPanel"), qPrintable(relative));
  }

  QFile application(QStringLiteral(SHACKCQ_DESKTOP_APP_DIR
                                   "/DesktopApplication.cpp"));
  QVERIFY(application.open(QIODevice::ReadOnly));
  const QByteArray applicationSource = application.readAll();
  QVERIFY(applicationSource.contains("savePanelGeometry"));
  QVERIFY(applicationSource.contains("resetWorkspaceLayout"));
  QVERIFY(applicationSource.contains("desktopLayouts"));
  QVERIFY(applicationSource.contains("layoutVersion"));
  QVERIFY(applicationSource.contains("setEditLayoutMode"));
  QVERIFY(applicationSource.contains(
      "saved[QStringLiteral(\"stored\")] = true"));
}

void DesktopUiContractTests::originalIconFamilyCoversEveryWorkspaceDestination() {
  DesktopApplication desktop;
  for (const QVariant &value : desktop.commands()) {
    const QVariantMap command = value.toMap();
    if (!command.value("workspace").toBool())
      continue;
    const QString path = QStringLiteral(SHACKCQ_DESKTOP_ICON_DIR "/") +
                         command.value("icon").toString() + QStringLiteral(".svg");
    QFile file(path);
    QVERIFY2(file.open(QIODevice::ReadOnly), qPrintable(path));
    const QByteArray svg = file.readAll();
    QVERIFY(svg.contains("<svg"));
    QVERIFY(svg.contains("viewBox=\"0 0 24 24\""));
    QVERIFY(!svg.contains("emoji"));
  }
}

void DesktopUiContractTests::macosAgentUsesUnambiguousRuntimeAndRadioStates() {
  QFile gui(QStringLiteral(SHACKCQ_DESKTOP_APP_DIR "/agent_gui_main.cpp"));
  QVERIFY(gui.open(QIODevice::ReadOnly));
  const QByteArray source = gui.readAll();
  QVERIFY(source.contains("agentStateBadge"));
  QVERIFY(source.contains("radioConnectionSummary"));
  QVERIFY(source.contains("setAgentRuntimeState"));
  QVERIFY(source.contains("Radio not connected"));
  QVERIFY(source.contains("Check that the radio is powered on"));
  QCOMPARE(source.count("new QPushButton(QStringLiteral(\"Starting…\")"), 1);
  QVERIFY(!source.contains("new QPushButton(QStringLiteral(\"Start Agent\")"));
  QVERIFY(!source.contains("new QPushButton(QStringLiteral(\"Stop Agent\")"));

  QFile cloud(QStringLiteral(SHACKCQ_DESKTOP_APP_DIR
                             "/../network/CloudAgentClient.cpp"));
  QVERIFY(cloud.open(QIODevice::ReadOnly));
  const QByteArray cloudSource = cloud.readAll();
  const qsizetype snapshot = cloudSource.indexOf("sendSnapshot();", cloudSource.indexOf("const QJsonObject result = processControlFrame"));
  const qsizetype result = cloudSource.indexOf("sendObject(result);", snapshot);
  QVERIFY(snapshot >= 0);
  QVERIFY(result > snapshot);
}

QTEST_GUILESS_MAIN(DesktopUiContractTests)
#include "desktop_ui_contract_tests.moc"
