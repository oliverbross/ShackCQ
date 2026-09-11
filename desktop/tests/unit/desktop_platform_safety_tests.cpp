#include "shackcq/desktop/DesktopPanadapter.hpp"
#include "shackcq/desktop/DesktopEngagementControllers.hpp"
#include "shackcq/desktop/DesktopPlatform.hpp"
#include "shackcq/desktop/DesktopRadioController.hpp"
#include "shackcq/desktop/DesktopRotatorController.hpp"

#include <QJsonDocument>
#include <QTcpServer>
#include <QTcpSocket>
#include <QTemporaryDir>
#include <QtTest>
#include <cmath>

using namespace shackcq::desktop;

class DesktopPlatformSafetyTests final : public QObject {
  Q_OBJECT
private slots:
  void credentialFakeUpdatesDeletesWithoutPersistence() {
    FakeCredentialVault vault;
    QVERIFY(vault.write("a", "label", "secret"));
    QCOMPARE(vault.read("a").value(), QString("secret"));
    QVERIFY(vault.write("a", "label", "new"));
    QCOMPARE(vault.read("a").value(), QString("new"));
    QVERIFY(vault.remove("a"));
    QVERIFY(!vault.read("a"));
  }
  void configurationWhitelistRestoresNoAuthority() {
    QTemporaryDir dir;
    DesktopConfigurationManager config(dir.filePath("config.json"));
    QString error;
    QVERIFY(config.load(&error));
    config.setSection("radioProfiles",
                      {{"model", 1},
                       {"connected", true},
                       {"ptt", true},
                       {"networkArmed", true},
                       {"pendingCommands", QStringList{"set_freq"}}});
    config.setSection("wavelogBinding", {{"server", "https://example.test"},
                                         {"credentialAlias", "private"}});
    QVERIFY(config.exportBundle(dir.filePath("export.json"), &error));
    QFile file(dir.filePath("export.json"));
    QVERIFY(file.open(QIODevice::ReadOnly));
    const QByteArray bytes = file.readAll();
    QVERIFY(!bytes.contains("credential"));
    QVERIFY(!bytes.contains("pendingCommands"));
    QVERIFY(!bytes.contains("connected"));
    QVERIFY(!bytes.contains("networkArmed"));
    QVERIFY(!bytes.contains("ptt"));
  }
  void configurationPersistsOwnedAgentSections() {
    QTemporaryDir dir;
    const QString path = dir.filePath("config.json");
    DesktopConfigurationManager config(path);
    QString error;
    QVERIFY(config.load(&error));
    config.setSection("cloudAgent", {{"enabled", true}});
    config.setSection("digiAgent",
                      {{"schemaVersion", 1},
                       {"localTxPermitted", false},
                       {"hardwareAccepted", false}});

    DesktopConfigurationManager restored(path);
    QVERIFY(restored.load(&error));
    QCOMPARE(restored.section("cloudAgent").value("enabled").toBool(), true);
    QCOMPARE(restored.section("digiAgent").value("schemaVersion").toInt(), 1);
    QCOMPARE(restored.section("digiAgent").value("localTxPermitted").toBool(),
             false);
    QCOMPARE(restored.section("digiAgent").value("hardwareAccepted").toBool(),
             false);
  }
  void unknownConfigurationSectionsRequireExplicitReview() {
    QTemporaryDir dir;
    DesktopConfigurationManager config(dir.filePath("config.json"));
    QString error;
    QVERIFY(config.load(&error));
    QFile input(dir.filePath("future.json"));
    QVERIFY(input.open(QIODevice::WriteOnly));
    input.write("{\"version\":2,\"navigation\":{\"lastDestination\":\"DX\"},"
                "\"futureAuthority\":{\"armed\":true}}");
    input.close();
    const auto preview = config.previewImport(input.fileName());
    QVERIFY(preview.value("valid").toBool());
    QVERIFY(preview.value("requiresReview").toBool());
    QVERIFY(preview.value("unknownSections")
                .toStringList()
                .contains("futureAuthority"));
    QVERIFY(!config.applyImport(input.fileName(), {"futureAuthority"}, &error));
    QVERIFY(error.contains("Unknown or unsafe"));
  }
  void supportBundleCannotRunAfterClose() {
    DesktopPaths paths;
    SupportBundle support(&paths);
    support.close();
    QString error;
    QVERIFY(support.create({}, &error).isEmpty());
    QCOMPARE(error, QString("Support bundle service is closed"));
  }
  void supportBundleRemovesRadioNetworkLocationAndRawStreamData() {
    QTemporaryDir root;
    DesktopPaths paths;
    paths.setEphemeralRoot(root.path());
    QString error;
    QVERIFY(paths.create(&error));
    SupportBundle support(&paths);
    const QString bundle = support.create(
        {{"radio",
          QVariantMap{{"hostname", "private.example"},
                      {"endpoint", "ws://user:pass@192.168.1.22:40001"},
                      {"deviceIdentity", "safe-device"},
                      {"callsign", "OM0SECRET"},
                      {"configPath", "/Users/private/ShackCQ/config.json"}}},
         {"stationLatitude", 48.1234},
         {"stationLongitude", 17.1234},
         {"rawIqSamples", "RAW-IQ-MARKER"},
         {"rawAudioSamples", "RAW-AUDIO-MARKER"},
         {"webSocketPayload", "RAW-WS-MARKER"}},
        &error);
    QVERIFY2(!bundle.isEmpty(), qPrintable(error));
    QFile file(bundle);
    QVERIFY(file.open(QIODevice::ReadOnly));
    const QByteArray bytes = file.readAll();
    QVERIFY(!bytes.contains("private.example"));
    QVERIFY(!bytes.contains("192.168.1.22"));
    QVERIFY(!bytes.contains("OM0SECRET"));
    QVERIFY(!bytes.contains("/Users/private"));
    QVERIFY(!bytes.contains("RAW-IQ-MARKER"));
    QVERIFY(!bytes.contains("RAW-AUDIO-MARKER"));
    QVERIFY(!bytes.contains("RAW-WS-MARKER"));
    QVERIFY(bytes.contains("safe-device"));
  }
  void radioAndRotatorRestoreSafe() {
    DesktopRadioController radio;
    DesktopRotatorController rotator;
    QCOMPARE(radio.state(), QString("Disconnected"));
    QVERIFY(!radio.pttAvailable());
    QVERIFY(!radio.tuneAvailable());
    QCOMPARE(rotator.state(), QString("Disconnected / automation disarmed"));
    QVERIFY(!rotator.automationArmed());
  }
  void keyerPreviewWorksWhileSendAndRestoreAuthorityStayClosed() {
    DesktopKeyerController keyer;
    QString error;
    QVERIFY(keyer.restoreConfiguration(
        {{"schemaVersion", 1},
         {"templates", QVariantMap{{"F1", "CQ {MYCALL}"}}},
         {"queue", QStringList{"unsafe"}},
         {"repeatCq", true},
         {"armed", true}},
        &error));
    QCOMPARE(keyer.queueDepth(), 0);
    QVERIFY(!keyer.sendAvailable());
    QVERIFY(keyer.previewMacro("F1", {{"MYCALL", "N0TEST"}}));
    QCOMPARE(keyer.lastPreview(), QString("CQ N0TEST"));
    QVERIFY(!keyer.enqueueSend("F1", {{"MYCALL", "N0TEST"}}));
    QCOMPARE(keyer.queueDepth(), 0);
    keyer.stop();
    QVERIFY(keyer.state().startsWith("STOPPED"));
  }
  void nativeRadioTcpFixtureRequiresReadbackAndRejectsTransmitAuthority() {
    QTcpServer server;
    QVERIFY(server.listen(QHostAddress::LocalHost, 0));
    DesktopRadioController radio;
    const QString route =
        QStringLiteral("tcp://127.0.0.1:%1").arg(server.serverPort());
    QVERIFY(radio.connectNativeProfile("QMX", route, 38400));
    QVERIFY(server.waitForNewConnection(1000));
    QTcpSocket *peer = server.nextPendingConnection();
    QVERIFY(peer);
    QTRY_VERIFY_WITH_TIMEOUT(peer->bytesAvailable() > 0, 1000);
    QVERIFY(peer->readAll().contains("FA;"));
    peer->write("FA00014074000;MD6;");
    peer->flush();
    QTRY_COMPARE_WITH_TIMEOUT(radio.frequencyHz(), quint64(14074000), 1000);
    QCOMPARE(radio.mode(), QString("DIGU"));
    QVERIFY(radio.requestFrequency(14062000));
    QTRY_VERIFY_WITH_TIMEOUT(peer->bytesAvailable() > 0, 1000);
    QVERIFY(peer->readAll().contains("FA00014062000;"));
    QVERIFY(!radio.pttAvailable());
    QVERIFY(!radio.tuneAvailable());
    radio.disconnectRadio();
  }
  void nativeRgoV6FixtureMustProveExactIdentity() {
    QTcpServer server;
    QVERIFY(server.listen(QHostAddress::LocalHost, 0));
    DesktopRadioController radio;
    const QString route =
        QStringLiteral("tcp://127.0.0.1:%1").arg(server.serverPort());
    QVERIFY(radio.connectNativeProfile("RGO-V6", route, 38400));
    QVERIFY(server.waitForNewConnection(1000));
    QTcpSocket *peer = server.nextPendingConnection();
    QVERIFY(peer);
    QTRY_VERIFY_WITH_TIMEOUT(peer->bytesAvailable() > 0, 1000);
    QVERIFY(peer->readAll().contains("ID;"));
    QVERIFY(!radio.requestFrequency(14062000));
    peer->write("ID006;");
    peer->flush();
    QTRY_VERIFY_WITH_TIMEOUT(radio.state().startsWith("Connected"), 1000);
    QVERIFY(radio.requestFrequency(14062000));
  }
  void nativeRotatorFixturePollsAndEnforcesForbiddenPath() {
    QTcpServer server;
    QVERIFY(server.listen(QHostAddress::LocalHost, 0));
    DesktopRotatorController rotator;
    QVERIFY(rotator.configureSafety(
        0, 450, -10, 180,
        {QVariantMap{{"startDeg", 200.0}, {"endDeg", 210.0},
                     {"reason", "feedline"}}}));
    const QString route =
        QStringLiteral("tcp://127.0.0.1:%1").arg(server.serverPort());
    QVERIFY(rotator.connectNative("GS232", route, 9600));
    QVERIFY(server.waitForNewConnection(1000));
    QTcpSocket *peer = server.nextPendingConnection();
    QVERIFY(peer);
    QTRY_VERIFY_WITH_TIMEOUT(peer->bytesAvailable() > 0, 1000);
    QVERIFY(peer->readAll().contains("C2\r"));
    peer->write("AZ180.0 EL25.0\r");
    peer->flush();
    QTRY_COMPARE_WITH_TIMEOUT(rotator.azimuth(), 180.0, 1000);
    QVERIFY(!rotator.prepareTarget(250, 30));
    QVERIFY(rotator.prepareTarget(190, 30));
    QVERIFY(rotator.confirmMove());
    QTRY_VERIFY_WITH_TIMEOUT(peer->bytesAvailable() > 0, 1000);
    QVERIFY(peer->readAll().contains("W190 030\r"));
    rotator.stop();
    QTRY_VERIFY_WITH_TIMEOUT(peer->bytesAvailable() > 0, 1000);
    QVERIFY(peer->readAll().contains("S\r"));
  }
  void panadapterRejectsMissingRouteAndAcceptsDeterministicStereoTone() {
    DesktopPanadapter pan;
    QVERIFY(!pan.start());
    QByteArray pcm;
    pcm.resize(96000 / 10 * 4);
    auto *samples = reinterpret_cast<qint16 *>(pcm.data());
    for (int i = 0; i < 9600; i++) {
      const qint16 value = qint16(
          std::sin(2.0 * 3.141592653589793 * 1000.0 * i / 96000.0) * 12000);
      samples[2 * i] = value;
      samples[2 * i + 1] = qint16(
          std::cos(2.0 * 3.141592653589793 * 1000.0 * i / 96000.0) * 12000);
    }
    QVERIFY(pan.processPcmForTest(pcm));
    QVERIFY(!pan.trace().isEmpty());
    QVERIFY(pan.peakDb() > -80.0);
  }
  void panadapterMaintainsBoundedIndependentFloatIqContexts() {
    DesktopPanadapter pan;
    pan.setWaterfallRows(72);
    pan.setFftSize(1024);
    QVector<float> iq(2048);
    for (int frame = 0; frame < 1024; ++frame) {
      const float phase =
          float(2.0 * 3.141592653589793 * 64.0 * frame / 1024.0);
      iq[2 * frame] = std::cos(phase) * .4F;
      iq[2 * frame + 1] = std::sin(phase) * .4F;
    }
    pan.pushFloatIq("tci:0", 96000, iq, 14074000, false);
    pan.pushFloatIq("tci:1", 48000, iq, 7074000, true);
    QVERIFY(pan.waitForIdleForTest());
    QCOMPARE(pan.receiverIds().size(), 2);
    pan.setCurrentReceiverId("tci:0");
    QCOMPARE(pan.renderFrame("tci:0").trace.size(), 1024);
    QCOMPARE(pan.renderFrame("tci:0").waterfall.height(), 72);
    QCOMPARE(pan.frequencyAt(.5, 1, 0), 14074000ULL);
    const QImage before = pan.renderFrame("tci:0").waterfall;
    pan.setPaused(true);
    pan.pushFloatIq("tci:0", 96000, iq, 14074000, false);
    QVERIFY(pan.waitForIdleForTest());
    QCOMPARE(pan.renderFrame("tci:0").waterfall, before);
    QVERIFY(pan.health().value("pausedDisplay").toBool());
    QVERIFY(pan.health().value("fftWorker").toBool());
    QVERIFY(!pan.health().value("captureStopped").toBool());
    QString error;
    QVERIFY(!pan.restoreConfiguration({{"schemaVersion", 2}}, &error));
    QVERIFY(error.contains("newer"));
  }
};
QTEST_MAIN(DesktopPlatformSafetyTests)
#include "desktop_platform_safety_tests.moc"
