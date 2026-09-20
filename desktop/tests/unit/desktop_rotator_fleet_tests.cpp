// SPDX-License-Identifier: GPL-3.0-only
#include "shackcq/desktop/DesktopRotatorFleet.hpp"

#include <QJsonObject>
#include <QTcpServer>
#include <QTcpSocket>
#include <QUuid>
#include <QtTest>
#include <memory>

#ifdef Q_OS_UNIX
#include <fcntl.h>
#include <unistd.h>
#ifdef Q_OS_MACOS
#include <util.h>
#else
#include <pty.h>
#endif
#endif

using shackcq::desktop::DesktopRotatorFleet;

namespace {
QVariantMap profile(const QString &id, const QString &route) {
  return {{"id", id}, {"name", id}, {"backend", "GS232"},
          {"model", "fixture"}, {"protocol", "SERIAL"}, {"route", route},
          {"baudRate", 9600}, {"minimumAzimuth", 0}, {"maximumAzimuth", 450},
          {"minimumElevation", 0}, {"maximumElevation", 180},
          {"forbiddenSectors", QVariantList{}}};
}

#ifdef Q_OS_UNIX
QJsonObject command(const QString &id, const QString &action,
                    const QJsonObject &parameters = {},
                    const QString &control = "browser-1") {
  return {{"commandId", QUuid::createUuid().toString(QUuid::WithoutBraces)},
          {"agentId", "agent-1"}, {"deviceId", id},
          {"expectedGeneration", 5}, {"controlInstanceId", control},
          {"validUntilUtc", QDateTime::currentDateTimeUtc().addSecs(10).toString(Qt::ISODateWithMs)},
          {"parameters", parameters}, {"action", action}};
}

QJsonObject snapshot(DesktopRotatorFleet &fleet, const QString &id) {
  for (const QJsonValue &value : fleet.snapshots("agent-1", 5))
    if (value.toObject().value("deviceId").toString() == id)
      return value.toObject();
  return {};
}
#endif

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
                           {"controlInstanceId", "browser-1"},
                           {"validUntilUtc", QDateTime::currentDateTimeUtc().addSecs(10).toString(Qt::ISODateWithMs)},
                           {"parameters", QJsonObject{}}, {"action", "rotator.stop"}};
    QCOMPARE(fleet.processCommand(base, "agent-1", 1).value("code").toString(), QStringLiteral("ROTATOR_COMMAND_SCOPE_REJECTED"));
    QJsonObject prepare = base;
    prepare.insert("expectedGeneration", 1);
    prepare.insert("action", "rotator.target.prepare");
    prepare.insert("parameters", QJsonObject{{"azimuth", 120}, {"elevation", 0}});
    QCOMPARE(fleet.processCommand(prepare, "agent-1", 1).value("code").toString(), QStringLiteral("ROTATOR_OFFLINE"));
  }

  void runsIndependentSerialAndTcpFixturesWithDeadmanAndLeaseIsolation() {
#ifndef Q_OS_UNIX
    QSKIP("PTY-backed serial fixture is Unix-only; Windows exercises the same fleet in packaging CI");
#else
    int master = -1, slave = -1;
    char slaveName[256]{};
    QVERIFY(::openpty(&master, &slave, slaveName, nullptr, nullptr) == 0);
    ::close(slave);
    QVERIFY(::fcntl(master, F_SETFL, ::fcntl(master, F_GETFL) | O_NONBLOCK) == 0);

    QTcpServer server;
    QVERIFY(server.listen(QHostAddress::LocalHost, 0));
    QVariantMap serial = profile("rotor-serial", QString::fromLocal8Bit(slaveName));
    serial.insert("antennas", QVariantList{"yagi-1"});
    serial.insert("radioIds", QVariantList{"radio-1"});
    serial.insert("bands", QVariantList{"20m", "15m"});
    serial.insert("automationPolicy", "PROMPT");
    QVariantMap tcp = profile("rotor-tcp", QStringLiteral("tcp://127.0.0.1:%1").arg(server.serverPort()));
    tcp.insert("backend", "ROTCTLD");
    tcp.insert("protocol", "TCP");
    tcp.insert("minimumElevation", -10);
    tcp.insert("automationPolicy", "MANUAL");

    DesktopRotatorFleet fleet;
    QString error;
    QVERIFY2(fleet.restoreConfiguration({{"schemaVersion", 3}, {"profiles", QVariantList{serial, tcp}}}, &error), qPrintable(error));
    QVERIFY2(fleet.connectProfile("rotor-serial", &error), qPrintable(error));
    QVERIFY2(fleet.connectProfile("rotor-tcp", &error), qPrintable(error));
    QTRY_VERIFY(server.hasPendingConnections());
    std::unique_ptr<QTcpSocket> tcpPeer(server.nextPendingConnection());
    QVERIFY(tcpPeer);

    QVERIFY(::write(master, "AZ=100 EL=0\r", 13) == 13);
    tcpPeer->write("200\n20\n");
    tcpPeer->flush();
    QTRY_VERIFY(snapshot(fleet, "rotor-serial").value("positionFresh").toBool());
    QTRY_VERIFY(snapshot(fleet, "rotor-tcp").value("positionFresh").toBool());
    QCOMPARE(snapshot(fleet, "rotor-serial").value("azimuth").toDouble(), 100.0);
    QCOMPARE(snapshot(fleet, "rotor-tcp").value("elevation").toDouble(), 20.0);
    QCOMPARE(snapshot(fleet, "rotor-serial").value("selectedAntenna").toString(), QStringLiteral("yagi-1"));

    auto prepared = fleet.processCommand(command("rotor-serial", "rotator.target.prepare", {{"azimuth", 110}, {"elevation", 0}}), "agent-1", 5);
    QCOMPARE(prepared.value("code").toString(), QStringLiteral("ROTATOR_TARGET_PREPARED"));
    auto confirmed = fleet.processCommand(command("rotor-serial", "rotator.move.confirm", {{"preparationId", prepared.value("preparationId").toString()}}), "agent-1", 5);
    QCOMPARE(confirmed.value("code").toString(), QStringLiteral("ROTATOR_MOVE_DISPATCHED_READBACK_PENDING"));

    auto jog = fleet.processCommand(command("rotor-serial", "rotator.jog.start", {{"direction", "LEFT"}, {"speed", 50}, {"deadmanMs", 150}}), "agent-1", 5);
    QCOMPARE(jog.value("code").toString(), QStringLiteral("ROTATOR_JOG_STARTED_DEADMAN_ARMED"));
    QCOMPARE(fleet.processCommand(command("rotor-serial", "rotator.jog.start", {{"direction", "RIGHT"}, {"speed", 50}, {"deadmanMs", 150}}, "browser-2"), "agent-1", 5).value("code").toString(), QStringLiteral("ROTATOR_CONTROL_LEASE_BUSY"));
    QTRY_VERIFY_WITH_TIMEOUT(snapshot(fleet, "rotor-serial").value("state").toString() == "IDLE", 750);

    auto tcpPrepared = fleet.processCommand(command("rotor-tcp", "rotator.target.prepare", {{"azimuth", 220}, {"elevation", 30}}), "agent-1", 5);
    QCOMPARE(tcpPrepared.value("code").toString(), QStringLiteral("ROTATOR_TARGET_PREPARED"));
    auto tcpConfirmed = fleet.processCommand(command("rotor-tcp", "rotator.move.confirm", {{"preparationId", tcpPrepared.value("preparationId").toString()}}), "agent-1", 5);
    QCOMPARE(tcpConfirmed.value("code").toString(), QStringLiteral("ROTATOR_MOVE_DISPATCHED_READBACK_PENDING"));
    QByteArray tcpFrames;
    QTRY_VERIFY_WITH_TIMEOUT(([&] {
      tcpFrames += tcpPeer->readAll();
      return tcpFrames.contains("P 220.0 30.0");
    })(), 1'000);

    tcpPeer->abort();
    QCOMPARE(fleet.processCommand(command("rotor-serial", "rotator.stop", {}, "browser-2"), "agent-1", 5).value("ok").toBool(), true);
    QCOMPARE(fleet.count(), 2);
    ::close(master);
#endif
  }
};
} // namespace

QTEST_MAIN(DesktopRotatorFleetTests)
#include "desktop_rotator_fleet_tests.moc"
