#include "shackcq/desktop/CloudAgentClient.hpp"

#include <QDir>
#include <QFile>
#include <QJsonDocument>
#include <QJsonObject>
#include <QtTest>

using namespace shackcq::desktop;

class CloudAgentClientTests final : public QObject {
  Q_OBJECT
private slots:
  void configurationNeverAcceptsSecretsOrEndpoints() {
    FakeCredentialVault vault;
    DesktopRadioController radio;
    CloudAgentClient client(&vault, &radio);
    QString error;
    QVERIFY(!client.restoreConfiguration(
        {{"enabled", true}, {"credential", "must-not-live-here"}}, &error));
    QVERIFY(!error.isEmpty());
    const QVariantMap expected{{"enabled", false}};
    QCOMPARE(client.configuration(), expected);
  }

  void unpairedClientIsOfflineAndHasNoDangerousAuthority() {
    FakeCredentialVault vault;
    DesktopRadioController radio;
    CloudAgentClient client(&vault, &radio);
    QVERIFY(client.restoreConfiguration({{"enabled", true}}));
    client.start();
    const QVariantMap health = client.health();
    QCOMPARE(health.value("state").toString(), QString("Unpaired"));
    QCOMPARE(health.value("offlineQueue").toBool(), false);
    QCOMPARE(health.value("ptt").toBool(), false);
    QCOMPARE(health.value("tune").toBool(), false);
    QCOMPARE(health.value("txAudio").toBool(), false);
    QCOMPARE(health.value("rotatorMovement").toBool(), false);
  }

  void hamlibProfilePersistsForExplicitSafeAutoconnect() {
    DesktopRadioController radio;
    QVERIFY(radio.saveHamlibProfile(1, "/dev/shackcq-dummy", 38400, true));
    const QVariantMap stored = radio.configuration();
    QCOMPARE(stored.value("schemaVersion").toInt(), 3);
    DesktopRadioController restored;
    QString error;
    QVERIFY2(restored.restoreConfiguration(stored, &error), qPrintable(error));
    QCOMPARE(restored.hamlibProfile().value("modelId").toInt(), 1);
    QCOMPARE(restored.hamlibProfile().value("route").toString(),
             QString("/dev/shackcq-dummy"));
    QCOMPARE(restored.hamlibProfile().value("autoConnect").toBool(), true);
    restored.clearHamlibProfile();
    QVERIFY(restored.hamlibProfile().isEmpty());
  }

  void unpairRemovesVaultCredentialAndDisablesClient() {
    FakeCredentialVault vault;
    QString error;
    QVERIFY(vault.write("shackcq-cloud-agent-v1", "fixture", "secret", &error));
    DesktopRadioController radio;
    CloudAgentClient client(&vault, &radio);
    QVERIFY(client.restoreConfiguration({{"enabled", true}}));
    QVERIFY2(client.unpair(&error), qPrintable(error));
    QCOMPARE(client.configuration().value("enabled").toBool(), false);
    QVERIFY(!vault.read("shackcq-cloud-agent-v1").has_value());
  }

  void pairedCredentialRestoresItsAccountAndStationAssociation() {
    FakeCredentialVault vault;
    const QJsonObject stored{
        {"agentId", "agent-fixture"},
        {"connectUrl", "wss://127.0.0.1/api/v1/agent/connect"},
        {"credential", QString(48, QLatin1Char('s'))},
        {"userId", "11111111-1111-4111-8111-111111111111"},
        {"stationProfileId", "22222222-2222-4222-8222-222222222222"},
        {"accountLabel", "Operator AA1A"},
        {"stationLabel", "Home station · AA1A"}};
    QString error;
    QVERIFY(vault.write(
        "shackcq-cloud-agent-v1", "ShackCQ cloud Agent",
        QString::fromUtf8(QJsonDocument(stored).toJson(QJsonDocument::Compact)),
        &error));

    DesktopRadioController radio;
    CloudAgentClient client(&vault, &radio);
    QVERIFY(client.restoreConfiguration({{"enabled", true}}));
    client.start();
    const QVariantMap health = client.health();
    QCOMPARE(health.value("paired").toBool(), true);
    QCOMPARE(health.value("userId").toString(),
             QString("11111111-1111-4111-8111-111111111111"));
    QCOMPARE(health.value("stationProfileId").toString(),
             QString("22222222-2222-4222-8222-222222222222"));
    QCOMPARE(health.value("accountLabel").toString(),
             QString("Operator AA1A"));
    QCOMPARE(health.value("stationLabel").toString(),
             QString("Home station · AA1A"));
    client.stop();
  }

  void staleAndProhibitedFramesFailClosed() {
    FakeCredentialVault vault;
    DesktopRadioController radio;
    CloudAgentClient client(&vault, &radio);
    const QJsonObject frame{{"type", "radio.command"},
                            {"protocol", QJsonObject{{"major", 1}, {"minor", 0}}},
                            {"commandId", "command-1"},
                            {"agentId", ""},
                            {"deviceId", "hamlib:1"},
                            {"expectedGeneration", 1},
                            {"action", "radio.ptt"},
                            {"parameters", QJsonObject{{"enabled", true}}}};
    const QJsonObject result = client.processControlFrame(frame);
    QCOMPARE(result.value("ok").toBool(), false);
    QVERIFY(result.value("code").toString() == "COMMAND_SCOPE_REJECTED" ||
            result.value("code").toString() == "STALE_AGENT_GENERATION");
    QCOMPARE(client.health().value("offlineQueue").toBool(), false);
  }

  void sharedProtocolFixturesAreExactAndFailClosedWithoutADevice() {
    const QDir root(QStringLiteral(SHACKCQ_RADIO_AGENT_FIXTURES_DIR));
    QCOMPARE(root.entryList({QStringLiteral("*.json")}, QDir::Files,
                            QDir::Name),
             QStringList({QStringLiteral("agent-hello.json"),
                          QStringLiteral("command-result.json"),
                          QStringLiteral("device-capabilities.json"),
                          QStringLiteral("error.json"),
                          QStringLiteral("radio-command.json"),
                          QStringLiteral("radio-delta.json"),
                          QStringLiteral("radio-snapshot.json")}));
    QFile command(root.filePath(QStringLiteral("radio-command.json")));
    QVERIFY(command.open(QIODevice::ReadOnly));
    QJsonParseError parse;
    const QJsonObject frame =
        QJsonDocument::fromJson(command.readAll(), &parse).object();
    QCOMPARE(parse.error, QJsonParseError::NoError);
    QCOMPARE(frame.value("type").toString(), QString("radio.command"));
    QCOMPARE(frame.value("action").toString(),
             QString("radio.set.frequency"));

    FakeCredentialVault vault;
    DesktopRadioController radio;
    CloudAgentClient client(&vault, &radio);
    const QJsonObject result = client.processControlFrame(frame);
    QCOMPARE(result.value("ok").toBool(), false);
    QCOMPARE(result.value("code").toString(),
             QString("COMMAND_SCOPE_REJECTED"));
  }
};

QTEST_MAIN(CloudAgentClientTests)
#include "cloud_agent_client_tests.moc"
