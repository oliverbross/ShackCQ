#include "shackcq/desktop/LoggerIngestion.hpp"

#include <QFile>
#include <QDir>
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
QJsonObject nativeProfile(const QString &authority = QStringLiteral("WEB_LOCAL")) {
  QJsonObject value = profile(0, "NEXUS_NATIVE");
  value.insert("destinationAuthority", authority);
  if (authority == "WAVELOG")
    value.insert("mappingRevision", 7);
  return value;
}
QJsonObject nativeIntent(const QString &operation = QStringLiteral("nexus-qso-1")) {
  return {{"profileId", "11111111-1111-4111-8111-111111111111"},
          {"operationIdentity", operation},
          {"sourceRevision", 1},
          {"capturedUtc", "2026-09-12T02:03:04.000Z"},
          {"destinationAuthority", "WEB_LOCAL"},
          {"authorityRevision", 1},
          {"stationProfileId", "22222222-2222-4222-8222-222222222222"},
          {"contact", QJsonObject{{"callsign", "VK8ABC"},
                                   {"contactStartUtc", "2026-09-12T02:02:00.000Z"},
                                   {"frequencyHz", 14074000},
                                   {"mode", "FT8"}}}};
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
    restarted.setAccountScope("account-other");
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

  void nativeContactIsAccountScopedDurableAndIdempotent() {
    QTemporaryDir dir;
    FakeCredentialVault vault;
    const QString path = dir.filePath("journal.json");
    QString eventId;
    {
      LoggerIngestion logger(&vault, path);
      logger.setAccountScope("account-one");
      QCOMPARE(logger.applyProfile(nativeProfile()).value("code").toString(),
               QString("LOGGER_NATIVE_READY"));
      const QJsonObject first = logger.submitNativeContact(nativeIntent());
      QVERIFY(first.value("ok").toBool());
      QCOMPARE(first.value("code").toString(), QString("LOGGER_NATIVE_QUEUED"));
      eventId = first.value("eventId").toString();
      const QJsonObject duplicate = logger.submitNativeContact(nativeIntent());
      QCOMPARE(duplicate.value("eventId").toString(), eventId);
      QCOMPARE(duplicate.value("code").toString(),
               QString("LOGGER_NATIVE_DUPLICATE"));
      QCOMPARE(logger.pendingEvents().size(), 1);
      QCOMPARE(logger.pendingEvents()[0].toObject().value("source"),
               QJsonValue("NEXUS_NATIVE"));
      QFile metadata(path);
      QVERIFY(metadata.open(QIODevice::ReadOnly));
      QVERIFY(!metadata.readAll().contains("VK8ABC"));
    }
    LoggerIngestion restarted(&vault, path);
    QCOMPARE(restarted.pendingEvents().size(), 0);
    restarted.setAccountScope("account-two");
    QCOMPARE(restarted.pendingEvents().size(), 0);
    restarted.setAccountScope("account-one");
    QCOMPARE(restarted.pendingEvents().size(), 1);
    QCOMPARE(restarted.pendingEvents()[0].toObject().value("eventId").toString(),
             eventId);
  }

  void nativeAuthorityChangeAndOperationReuseFailClosed() {
    QTemporaryDir dir;
    FakeCredentialVault vault;
    LoggerIngestion logger(&vault, dir.filePath("journal.json"));
    logger.setAccountScope("account-one");
    QVERIFY(logger.applyProfile(nativeProfile()).value("ok").toBool());
    QJsonObject stale = nativeIntent();
    stale.insert("authorityRevision", 2);
    QCOMPARE(logger.submitNativeContact(stale).value("code").toString(),
             QString("LOGGER_DESTINATION_CHANGED"));
    QCOMPARE(logger.pendingEvents().size(), 0);
    QVERIFY(logger.submitNativeContact(nativeIntent()).value("ok").toBool());
    QJsonObject changed = nativeIntent();
    QJsonObject contact = changed.value("contact").toObject();
    contact.insert("callsign", "VK9XYZ");
    changed.insert("contact", contact);
    QCOMPARE(logger.submitNativeContact(changed).value("code").toString(),
             QString("LOGGER_EVENT_ID_REUSED"));
    QVERIFY(logger.applyProfile(nativeProfile("WAVELOG")).value("ok").toBool());
    QJsonObject migrated = nativeIntent();
    migrated.insert("destinationAuthority", "WAVELOG");
    migrated.insert("mappingRevision", 7);
    QCOMPARE(logger.submitNativeContact(migrated).value("code").toString(),
             QString("LOGGER_EVENT_ID_REUSED"));
    QCOMPARE(logger.pendingEvents().size(), 1);
  }

  void nativeContactRequiresUtcFiniteFrequencyAndKnownFields() {
    QTemporaryDir dir;
    FakeCredentialVault vault;
    LoggerIngestion logger(&vault, dir.filePath("journal.json"));
    logger.setAccountScope("account-one");
    QVERIFY(logger.applyProfile(nativeProfile()).value("ok").toBool());
    for (const QString &field :
         {QString("capturedUtc"), QString("contact")}) {
      QJsonObject invalid = nativeIntent();
      if (field == "capturedUtc")
        invalid.insert(field, "2026-09-12T02:03:04");
      else {
        QJsonObject contact = invalid.value("contact").toObject();
        contact.insert("frequencyHz", -1);
        invalid.insert("contact", contact);
      }
      QCOMPARE(logger.submitNativeContact(invalid).value("code").toString(),
               QString("LOGGER_NATIVE_EVENT_INVALID"));
    }
    QJsonObject unknown = nativeIntent();
    QJsonObject contact = unknown.value("contact").toObject();
    contact.insert("shell", "not permitted");
    unknown.insert("contact", contact);
    QCOMPARE(logger.submitNativeContact(unknown).value("code").toString(),
             QString("LOGGER_NATIVE_EVENT_INVALID"));
    QCOMPARE(logger.pendingEvents().size(), 0);
  }

  void nativeAmbiguityRequiresExplicitRetryOrDiscard() {
    QTemporaryDir dir;
    FakeCredentialVault vault;
    LoggerIngestion logger(&vault, dir.filePath("journal.json"));
    logger.setAccountScope("account-one");
    QVERIFY(logger.applyProfile(nativeProfile()).value("ok").toBool());
    const QString eventId =
        logger.submitNativeContact(nativeIntent()).value("eventId").toString();
    logger.acknowledge(QJsonArray{QJsonObject{{"eventId", eventId},
                                              {"accepted", false},
                                              {"disposition", "delivery_unknown"},
                                              {"code", "CANONICAL_RESULT_UNKNOWN"}}});
    QCOMPARE(logger.pendingEvents().size(), 0);
    QCOMPARE(logger.resolveNativeEvent(eventId, "retry").value("code"),
             QJsonValue("LOGGER_NATIVE_RETRY_QUEUED"));
    QCOMPARE(logger.pendingEvents().size(), 1);
    QCOMPARE(logger.resolveNativeEvent(eventId, "discard").value("code"),
             QJsonValue("LOGGER_NATIVE_DISCARDED"));
    QCOMPARE(logger.pendingEvents().size(), 0);
  }

  void failedDiscardKeepsMetadataAndEncryptedPayload() {
    QTemporaryDir dir;
    FakeCredentialVault vault;
    const QString queueDir = dir.filePath("queue");
    QVERIFY(QDir().mkpath(queueDir));
    const QString path = queueDir + "/journal.json";
    LoggerIngestion logger(&vault, path);
    logger.setAccountScope("account-one");
    QVERIFY(logger.applyProfile(nativeProfile()).value("ok").toBool());
    const QString eventId =
        logger.submitNativeContact(nativeIntent()).value("eventId").toString();
    QFile journal(path);
    QVERIFY(journal.open(QIODevice::ReadOnly));
    const QString alias = QJsonDocument::fromJson(journal.readAll())
                              .array()[0]
                              .toObject()
                              .value("alias")
                              .toString();
    journal.close();
    QVERIFY(vault.read(alias).has_value());
    QVERIFY(QDir().rename(queueDir, dir.filePath("moved")));
    QCOMPARE(logger.resolveNativeEvent(eventId, "discard").value("code"),
             QJsonValue("LOGGER_JOURNAL_WRITE_FAILED"));
    QCOMPARE(logger.pendingEvents().size(), 1);
    QVERIFY(vault.read(alias).has_value());
  }

  void failedAcceptedReceiptKeepsMetadataAndEncryptedPayload() {
    QTemporaryDir dir;
    FakeCredentialVault vault;
    const QString queueDir = dir.filePath("queue");
    QVERIFY(QDir().mkpath(queueDir));
    const QString path = queueDir + "/journal.json";
    LoggerIngestion logger(&vault, path);
    logger.setAccountScope("account-one");
    QVERIFY(logger.applyProfile(nativeProfile()).value("ok").toBool());
    const QString eventId =
        logger.submitNativeContact(nativeIntent()).value("eventId").toString();
    QFile journal(path);
    QVERIFY(journal.open(QIODevice::ReadOnly));
    const QString alias = QJsonDocument::fromJson(journal.readAll())
                              .array()[0]
                              .toObject()
                              .value("alias")
                              .toString();
    journal.close();
    QVERIFY(QDir().rename(queueDir, dir.filePath("moved")));
    logger.acknowledge(
        QJsonArray{QJsonObject{{"eventId", eventId}, {"accepted", true}}});
    QCOMPARE(logger.pendingEvents().size(), 1);
    QVERIFY(vault.read(alias).has_value());
    QCOMPARE(logger.health().value("lastError"),
             QVariant("LOGGER_JOURNAL_WRITE_FAILED"));
  }

  void failedRetryKeepsAmbiguousStateAndEncryptedPayload() {
    QTemporaryDir dir;
    FakeCredentialVault vault;
    const QString queueDir = dir.filePath("queue");
    QVERIFY(QDir().mkpath(queueDir));
    const QString path = queueDir + "/journal.json";
    LoggerIngestion logger(&vault, path);
    logger.setAccountScope("account-one");
    QVERIFY(logger.applyProfile(nativeProfile()).value("ok").toBool());
    const QString eventId =
        logger.submitNativeContact(nativeIntent()).value("eventId").toString();
    logger.acknowledge(QJsonArray{QJsonObject{{"eventId", eventId},
                                              {"accepted", false},
                                              {"disposition", "delivery_unknown"},
                                              {"code", "CANONICAL_RESULT_UNKNOWN"}}});
    QFile journal(path);
    QVERIFY(journal.open(QIODevice::ReadOnly));
    const QString alias = QJsonDocument::fromJson(journal.readAll())
                              .array()[0]
                              .toObject()
                              .value("alias")
                              .toString();
    journal.close();
    QVERIFY(QDir().rename(queueDir, dir.filePath("moved")));
    QCOMPARE(logger.resolveNativeEvent(eventId, "retry").value("code"),
             QJsonValue("LOGGER_JOURNAL_WRITE_FAILED"));
    QCOMPARE(logger.pendingEvents().size(), 0);
    QVERIFY(vault.read(alias).has_value());
  }
};

QTEST_MAIN(LoggerIngestionTests)
#include "logger_ingestion_tests.moc"
