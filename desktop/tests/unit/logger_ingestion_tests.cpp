#include "shackcq/desktop/LoggerIngestion.hpp"

#include <QFile>
#include <QJsonDocument>
#include <QJsonObject>
#include <QSignalSpy>
#include <QTemporaryDir>
#include <QUdpSocket>
#include <QtTest>

using namespace shackcq::desktop;

namespace {
quint16 freePort() {
  QUdpSocket socket;
  if (!socket.bind(QHostAddress::LocalHost, 0))
    return 0;
  return socket.localPort();
}
QJsonObject profile(quint16 port,
                    const QString &source = QStringLiteral("N1MM")) {
  return {{"id", "11111111-1111-4111-8111-111111111111"},
          {"source", source},
          {"instanceId", source.toLower() + "-main"},
          {"loopbackPort", int(port)},
          {"mode", "review"},
          {"enabled", true},
          {"destinationAuthority", "WEB_LOCAL"},
          {"authorityRevision", 1},
          {"stationProfileId", "22222222-2222-4222-8222-222222222222"}};
}
void u8(QByteArray &out, quint8 value) { out.append(char(value)); }
void u32(QByteArray &out, quint32 value) {
  for (int shift = 24; shift >= 0; shift -= 8)
    u8(out, quint8(value >> shift));
}
void u64(QByteArray &out, quint64 value) {
  u32(out, quint32(value >> 32));
  u32(out, quint32(value));
}
void bytes(QByteArray &out, const QByteArray &value) {
  u32(out, quint32(value.size()));
  out.append(value);
}
void qdateTime(QByteArray &out, qint64 epochMilliseconds) {
  const QDateTime value =
      QDateTime::fromMSecsSinceEpoch(epochMilliseconds, QTimeZone::UTC);
  u64(out, quint64(value.date().toJulianDay()));
  u32(out, quint32(value.time().msecsSinceStartOfDay()));
  u8(out, 1);
}
QByteArray wsjtHeader(quint32 type) {
  QByteArray out;
  u32(out, 0xadbccbdaU);
  u32(out, 3);
  u32(out, type);
  bytes(out, "WSJT-X");
  return out;
}
QByteArray wsjtQsoLogged(qint64 on) {
  QByteArray out = wsjtHeader(5);
  qdateTime(out, on + 60000);
  bytes(out, "VK8ABC");
  bytes(out, "PH57KP");
  u64(out, 14074000);
  bytes(out, "FT8");
  bytes(out, "-08");
  bytes(out, "-12");
  bytes(out, "25");
  bytes(out, "fixture");
  bytes(out, "Alice");
  qdateTime(out, on);
  return out;
}
QByteArray wsjtLoggedAdif() {
  const QByteArray adif =
      "<CALL:6>VK8ABC<BAND:3>20M<MODE:3>FT8<SUBMODE:3>FT8<FREQ:6>14.074<"
      "GRIDSQUARE:6>PH57KP<QSO_DATE:8>20260101<TIME_ON:6>010203<STATION_"
      "CALLSIGN:5>OM0RX<EOR>";
  QByteArray out = wsjtHeader(12);
  bytes(out, adif);
  return out;
}
} // namespace

class LoggerIngestionTests final : public QObject {
  Q_OBJECT
private slots:
  void n1mmJournalPersistsInVaultAndReceiptsDeleteIt() {
    QTemporaryDir dir;
    QVERIFY(dir.isValid());
    FakeCredentialVault vault;
    const QString path = dir.filePath("journal.json");
    const quint16 port = freePort();
    QVERIFY(port > 0);
    QString eventId, alias;
    {
      LoggerIngestion logger(&vault, path);
      QCOMPARE(logger.applyProfile(profile(port)).value("ok").toBool(), true);
      QSignalSpy ready(&logger, &LoggerIngestion::eventsReady);
      QUdpSocket sender;
      const QByteArray packet =
          "<contactinfo><call>VK8ABC</call><timestamp>2026-09-11 "
          "01:02:03</timestamp><freq>1407400</freq><band>20M</band><mode>FT8</"
          "mode><ID>fixture-guid</ID></contactinfo>";
      QCOMPARE(sender.writeDatagram(packet, QHostAddress::LocalHost, port),
               qint64(packet.size()));
      QTRY_COMPARE_WITH_TIMEOUT(ready.count(), 1, 2000);
      const QJsonArray events = logger.pendingEvents();
      QCOMPARE(events.size(), 1);
      const QJsonObject event = events[0].toObject();
      QCOMPARE(event.value("source").toString(), QString("N1MM"));
      QCOMPARE(event.value("kind").toString(), QString("create"));
      QCOMPARE(event.value("contact").toObject().value("callsign").toString(),
               QString("VK8ABC"));
      QCOMPARE(event.value("contact")
                   .toObject()
                   .value("frequencyHz")
                   .toVariant()
                   .toLongLong(),
               14074000LL);
      eventId = event.value("eventId").toString();
      QFile journal(path);
      QVERIFY(journal.open(QIODevice::ReadOnly));
      const QByteArray metadata = journal.readAll();
      QVERIFY(!metadata.contains("VK8ABC"));
      alias = QJsonDocument::fromJson(metadata)
                  .array()[0]
                  .toObject()
                  .value("alias")
                  .toString();
      QVERIFY(vault.read(alias).has_value());
    }
    LoggerIngestion restarted(&vault, path);
    QCOMPARE(restarted.pendingEvents().size(), 1);
    restarted.acknowledge(
        QJsonArray{QJsonObject{{"eventId", eventId}, {"accepted", true}}});
    QCOMPARE(restarted.pendingEvents().size(), 0);
    QVERIFY(!vault.read(alias).has_value());
  }

  void unsafeXmlIsRejectedWithoutQueueing() {
    QTemporaryDir dir;
    FakeCredentialVault vault;
    LoggerIngestion logger(&vault, dir.filePath("journal.json"));
    const quint16 port = freePort();
    QVERIFY(logger.applyProfile(profile(port)).value("ok").toBool());
    QUdpSocket sender;
    const QByteArray packet =
        "<!DOCTYPE x [<!ENTITY y SYSTEM "
        "'file:///etc/passwd'>]><contactinfo><call>&y;</call></contactinfo>";
    sender.writeDatagram(packet, QHostAddress::LocalHost, port);
    QTest::qWait(100);
    QCOMPARE(logger.pendingEvents().size(), 0);
  }

  void failedBindDoesNotPersistAnEnabledProfile() {
    QTemporaryDir dir;
    FakeCredentialVault vault;
    const QString path = dir.filePath("journal.json");
    QUdpSocket blocker;
    QVERIFY(
        blocker.bind(QHostAddress::LocalHost, 0, QUdpSocket::DontShareAddress));
    {
      LoggerIngestion logger(&vault, path);
      QVERIFY(!logger.applyProfile(profile(blocker.localPort()))
                   .value("ok")
                   .toBool());
    }
    QVERIFY(!QFile::exists(path + ".profiles"));
    blocker.close();
    LoggerIngestion restarted(&vault, path);
    QCOMPARE(restarted.health().value("profiles").toInt(), 0);
  }

  void wsjtRepresentationsCorrelateInEitherOrderAndDeduplicateRetries() {
    const qint64 on =
        QDateTime::fromString(QStringLiteral("2026-01-01T01:02:03.456Z"),
                              Qt::ISODateWithMs)
            .toMSecsSinceEpoch();
    for (const bool adifFirst : {false, true}) {
      QTemporaryDir dir;
      FakeCredentialVault vault;
      LoggerIngestion logger(&vault, dir.filePath("journal.json"));
      const quint16 port = freePort();
      QVERIFY(logger.applyProfile(profile(port, "WSJTX")).value("ok").toBool());
      QUdpSocket sender;
      QSignalSpy ready(&logger, &LoggerIngestion::eventsReady);
      const QByteArray type5 = wsjtQsoLogged(on), type12 = wsjtLoggedAdif();
      const QByteArray first = adifFirst ? type12 : type5,
                       second = adifFirst ? type5 : type12;
      QCOMPARE(sender.writeDatagram(first, QHostAddress::LocalHost, port),
               qint64(first.size()));
      QTRY_COMPARE_WITH_TIMEOUT(ready.count(), 1, 2000);
      QCOMPARE(sender.writeDatagram(second, QHostAddress::LocalHost, port),
               qint64(second.size()));
      QTRY_COMPARE_WITH_TIMEOUT(ready.count(), 2, 2000);
      QCOMPARE(sender.writeDatagram(second, QHostAddress::LocalHost, port),
               qint64(second.size()));
      QTest::qWait(100);
      const QJsonArray events = logger.pendingEvents();
      QCOMPARE(events.size(), 2);
      QCOMPARE(events[0].toObject().value("sourceContactId"),
               events[1].toObject().value("sourceContactId"));
      QVERIFY(events[0].toObject().value("eventId") !=
              events[1].toObject().value("eventId"));
      const QJsonObject richer = (adifFirst ? events[0] : events[1])
                                     .toObject()
                                     .value("contact")
                                     .toObject();
      QCOMPARE(richer.value("ownCallsign").toString(), QString("OM0RX"));
    }
  }
};

QTEST_MAIN(LoggerIngestionTests)
#include "logger_ingestion_tests.moc"
