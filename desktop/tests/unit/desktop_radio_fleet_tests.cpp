// SPDX-License-Identifier: GPL-3.0-only
#include "shackcq/desktop/DesktopRadioFleet.hpp"

#include <QtTest>

using shackcq::desktop::DesktopRadioFleet;

namespace {
QVariantMap profile(const QString &id, const QString &route) {
  return {{"id",id},{"name",id},{"backend","HAMLIB"},{"route",route},
          {"modelId",1},{"baudRate",38400},{"nativeProfileId","elecraft-ascii"}};
}

class DesktopRadioFleetTests final : public QObject {
  Q_OBJECT
private slots:
  void retainsIndependentFiniteProfiles() {
    DesktopRadioFleet fleet;QString error;
    QVariantList profiles{profile("radio-a","127.0.0.1:4532"),profile("radio-b","127.0.0.1:4533")};
    QVERIFY2(fleet.restoreConfiguration({{"schemaVersion",1},{"profiles",profiles}},&error),qPrintable(error));
    QCOMPARE(fleet.count(),2);
    QCOMPARE(fleet.descriptors().at(0).toMap().value("connected").toBool(),false);
    QCOMPARE(fleet.descriptors().at(0).toMap().value("connection").toString(),QStringLiteral("offline"));
    const QJsonArray snapshots=fleet.snapshots("agent-1",3);
    QCOMPARE(snapshots.size(),2);
    QCOMPARE(snapshots.at(0).toObject().value("generation").toInt(),3);
    QVERIFY(snapshots.at(0).toObject().value("deviceId")!=snapshots.at(1).toObject().value("deviceId"));
  }
  void rejectsParallelOwnershipOfOneRoute() {
    DesktopRadioFleet fleet;QString error;
    QVariantList profiles{profile("radio-a","127.0.0.1:4532"),profile("radio-b","127.0.0.1:4532")};
    QVERIFY(!fleet.restoreConfiguration({{"schemaVersion",1},{"profiles",profiles}},&error));
    QCOMPARE(fleet.count(),0);
  }
  void rejectsWrongDeviceAndOfflineMutation() {
    DesktopRadioFleet fleet;QString error;
    QVERIFY(fleet.restoreConfiguration({{"schemaVersion",1},{"profiles",QVariantList{profile("radio-a","127.0.0.1:4532")}}},&error));
    QJsonObject command{{"commandId","command-1"},{"agentId","agent-1"},{"deviceId","radio-b"},{"expectedGeneration",1},{"action","radio.set.frequency"},{"parameters",QJsonObject{{"frequencyHz",14074000}}}};
    QCOMPARE(fleet.processCommand(command,"agent-1",1).value("code").toString(),QStringLiteral("STALE_AGENT_GENERATION"));
    command.insert("deviceId","radio-a");
    QCOMPARE(fleet.processCommand(command,"agent-1",1).value("code").toString(),QStringLiteral("RADIO_OFFLINE"));
  }
  void confirmsFrequencyModeAndPresetMutations() {
    DesktopRadioFleet fleet;QString error;
    QVERIFY(fleet.restoreConfiguration({{"schemaVersion",1},{"profiles",QVariantList{profile("radio-a","/dev/fixture")}}},&error));
    QVERIFY(fleet.configureHamlibHelperForTest("radio-a",QStringLiteral(SHACKCQ_HAMLIB_FIXTURE)));
    QVERIFY2(fleet.connectProfile("radio-a",&error),qPrintable(error));
    const auto command=[](const QString &id,const QString &action,const QJsonObject &parameters){return QJsonObject{{"commandId",id},{"agentId","agent-1"},{"deviceId","radio-a"},{"expectedGeneration",7},{"action",action},{"parameters",parameters}};};
    QCOMPARE(fleet.processCommand(command("frequency","radio.set.frequency",{{"frequencyHz",14'271'199}}),"agent-1",7).value("code").toString(),QStringLiteral("READBACK_CONFIRMED"));
    QCOMPARE(fleet.processCommand(command("mode","radio.set.mode",{{"mode","CW"}}),"agent-1",7).value("code").toString(),QStringLiteral("READBACK_CONFIRMED"));
    QCOMPARE(fleet.processCommand(command("preset","preset.recall",{{"frequencyHz",7'074'000},{"mode","DATA"},{"filterHz",3'000}}),"agent-1",7).value("code").toString(),QStringLiteral("READBACK_CONFIRMED"));
    const QJsonObject snapshot=fleet.snapshots("agent-1",7).first().toObject();
    QCOMPARE(snapshot.value("frequencyHz").toDouble(),7'074'000.0);
    QCOMPARE(snapshot.value("mode").toString(),QStringLiteral("DATA"));
    QCOMPARE(snapshot.value("filterHz").toInt(),3'000);
  }
};
} // namespace

QTEST_MAIN(DesktopRadioFleetTests)
#include "desktop_radio_fleet_tests.moc"
