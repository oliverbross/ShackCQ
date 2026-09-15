// SPDX-License-Identifier: GPL-3.0-only
#include "shackcq/desktop/DesktopRotatorFleet.hpp"

#include <QJsonObject>
#include <QtTest>

using shackcq::desktop::DesktopRotatorFleet;

namespace {
QVariantMap profile(const QString &id, const QString &route) {
  return {{"id", id}, {"name", id}, {"backend", "GS232"},
          {"model", "fixture"}, {"protocol", "SERIAL"}, {"route", route},
          {"baudRate", 9600}, {"minimumAzimuth", 0}, {"maximumAzimuth", 450},
          {"minimumElevation", 0}, {"maximumElevation", 180},
          {"forbiddenSectors", QVariantList{}}};
}

class DesktopRotatorFleetTests final : public QObject {
  Q_OBJECT
private slots:
  void keepsEightIndependentStableDevices() {
    DesktopRotatorFleet fleet;
    QVariantList profiles;
    for (int index = 0; index < 8; ++index)
      profiles.push_back(profile(QStringLiteral("rotor-%1").arg(index + 1),
                                 QStringLiteral("127.0.0.1:%1").arg(4500 + index)));
    QString error;
    QVERIFY2(fleet.restoreConfiguration({{"schemaVersion", 2}, {"profiles", profiles}}, &error), qPrintable(error));
    QCOMPARE(fleet.count(), 8);
    const QJsonArray snapshots = fleet.snapshots("agent-1", 4);
    QCOMPARE(snapshots.size(), 8);
    QSet<QString> ids;
    for (const QJsonValue &value : snapshots) {
      const QJsonObject row = value.toObject();
      ids.insert(row.value("deviceId").toString());
      QCOMPARE(row.value("generation").toInt(), 4);
      QCOMPARE(row.value("connection").toString(), QStringLiteral("offline"));
      QVERIFY(row.value("positionFresh").toBool() == false);
    }
    QCOMPARE(ids.size(), 8);
  }

  void rejectsDuplicateRoutesWithoutReplacingGoodConfiguration() {
    DesktopRotatorFleet fleet;
    QString error;
    QVERIFY(fleet.restoreConfiguration({{"schemaVersion", 2}, {"profiles", QVariantList{profile("rotor-a", "127.0.0.1:4501")}}}, &error));
    const QVariantList duplicate{profile("rotor-a", "127.0.0.1:4502"), profile("rotor-b", "127.0.0.1:4502")};
    QVERIFY(!fleet.restoreConfiguration({{"schemaVersion", 2}, {"profiles", duplicate}}, &error));
    QCOMPARE(fleet.count(), 1);
    QCOMPARE(fleet.descriptors().front().toMap().value("id").toString(), QStringLiteral("rotor-a"));
  }

  void scopesCommandsAndRequiresAnExplicitConnection() {
    DesktopRotatorFleet fleet;
    QString error;
    QVERIFY(fleet.restoreConfiguration({{"schemaVersion", 2}, {"profiles", QVariantList{profile("rotor-a", "127.0.0.1:4501")}}}, &error));
    const QJsonObject base{{"commandId", "command-1"}, {"agentId", "agent-1"},
                           {"deviceId", "rotor-a"}, {"expectedGeneration", 2},
                           {"validUntilUtc", QDateTime::currentDateTimeUtc().addSecs(10).toString(Qt::ISODateWithMs)},
                           {"parameters", QJsonObject{}}, {"action", "rotator.stop"}};
    QCOMPARE(fleet.processCommand(base, "agent-1", 1).value("code").toString(), QStringLiteral("ROTATOR_COMMAND_SCOPE_REJECTED"));
    QJsonObject prepare = base;
    prepare.insert("expectedGeneration", 1);
    prepare.insert("action", "rotator.target.prepare");
    prepare.insert("parameters", QJsonObject{{"azimuth", 120}, {"elevation", 0}});
    QCOMPARE(fleet.processCommand(prepare, "agent-1", 1).value("code").toString(), QStringLiteral("ROTATOR_OFFLINE"));
  }
};
} // namespace

QTEST_MAIN(DesktopRotatorFleetTests)
#include "desktop_rotator_fleet_tests.moc"
