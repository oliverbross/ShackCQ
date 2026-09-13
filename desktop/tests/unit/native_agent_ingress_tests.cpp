// SPDX-License-Identifier: GPL-3.0-only
#include "shackcq/desktop/LoggerIngestion.hpp"
#include "shackcq/desktop/NativeAgentIngress.hpp"

#include <QJsonDocument>
#include <QElapsedTimer>
#include <QLocalServer>
#include <QLocalSocket>
#include <QTemporaryDir>
#include <QtTest>
#include <array>

using namespace shackcq::desktop;

namespace {
const QByteArray Secret(32, 's');
const QDateTime Now = QDateTime::fromString("2026-09-12T03:30:00.000Z", Qt::ISODate);

QJsonObject profile() {
  return {{"id", "native-profile-1"},
          {"source", "NEXUS_NATIVE"},
          {"instanceId", "nexus-native-main"},
          {"loopbackPort", 0},
          {"enabled", true},
          {"destinationAuthority", "WEB_LOCAL"},
          {"authorityRevision", 4},
          {"stationProfileId", "station-1"}};
}
QJsonObject intent() {
  return {{"profileId", "native-profile-1"},
          {"operationIdentity", "operation-1"},
          {"sourceRevision", 1},
          {"capturedUtc", "2026-09-12T03:29:58.000Z"},
          {"destinationAuthority", "WEB_LOCAL"},
          {"authorityRevision", 4},
          {"stationProfileId", "station-1"},
          {"contact", QJsonObject{{"callsign", "VK8ABC"},
                                   {"contactStartUtc", "2026-09-12T03:29:00.000Z"},
                                   {"frequencyHz", 14074000},
                                   {"mode", "FT8"}}}};
}
QJsonObject envelope(QString nonce = QString(64, '1')) {
  QJsonObject value{{"action", "nexus-contact.submit"},
                    {"protocol", QJsonObject{{"major", 1}, {"minor", 0}}},
                    {"requestId", "request-1"},
                    {"nonce", nonce},
                    {"sentUtc", "2026-09-12T03:30:00.000Z"},
                    {"payloadBase64", QString::fromLatin1(
                                          QJsonDocument(intent())
                                              .toJson(QJsonDocument::Compact)
                                              .toBase64())}};
  value.insert("mac", NativeAgentIngress::signForTest(Secret, value));
  return value;
}
QJsonObject roundTrip(QLocalServer &server, const QList<QByteArray> &chunks) {
  QLocalSocket client;
  client.connectToServer(server.serverName());
  if (!client.waitForConnected(1000))
    return {{"code", "CONNECT_FAILED"}};
  for (const QByteArray &chunk : chunks) {
    client.write(chunk);
    QElapsedTimer writeTimer;
    writeTimer.start();
    while (client.bytesToWrite() && writeTimer.elapsed() < 1000) {
      QCoreApplication::processEvents(QEventLoop::AllEvents, 10);
      QTest::qWait(1);
    }
    if (client.bytesToWrite())
      return {{"code", "WRITE_FAILED"}};
  }
  QElapsedTimer timer;
  timer.start();
  while (!client.canReadLine() && timer.elapsed() < 1000) {
    QCoreApplication::processEvents(QEventLoop::AllEvents, 10);
    QTest::qWait(1);
  }
  if (!client.canReadLine())
    return {{"code", "RESPONSE_FAILED"}};
  return QJsonDocument::fromJson(client.readLine())
      .object()
      .value("result")
      .toObject();
}
QJsonObject roundTrip(QLocalServer &server, const QByteArray &line) {
  return roundTrip(server, QList<QByteArray>{line});
}
} // namespace

class NativeAgentIngressTests final : public QObject {
  Q_OBJECT
private slots:
  void socketAcceptsValidAndRejectsTamperReplayStaleAndWrongKey() {
    QTemporaryDir dir;
    FakeCredentialVault vault;
    LoggerIngestion logger(&vault, dir.filePath("journal.json"));
    logger.setAccountScope("account-1");
    QVERIFY(logger.applyProfile(profile()).value("ok").toBool());
    NativeAgentIngress ingress(&vault, &logger, [] { return Now; },
                               [] { return Secret; });
    QVERIFY(ingress.initialize());
    QLocalServer server;
    server.setSocketOptions(QLocalServer::UserAccessOption);
    QVERIFY(server.listen(dir.filePath("native-ingress.sock")));
    NativeAgentIngressServer ingressServer(&server, &ingress);
    QJsonObject bindingRequest = envelope(QString(64, 'b'));
    bindingRequest.insert("action", "native-ingress.binding");
    bindingRequest.insert("payloadBase64", "e30=");
    bindingRequest.insert("mac",
                          NativeAgentIngress::signForTest(Secret, bindingRequest));
    const QJsonObject binding = roundTrip(
        server, QJsonDocument(bindingRequest).toJson(QJsonDocument::Compact) + '\n');
    QCOMPARE(binding.value("code"), QJsonValue("LOGGER_NATIVE_BINDING_READY"));
    QCOMPARE(binding.value("accountId"), QJsonValue("account-1"));
    QCOMPARE(binding.value("profileId"), QJsonValue("native-profile-1"));
    const QByteArray valid = QJsonDocument(envelope()).toJson(QJsonDocument::Compact) + '\n';
    const QJsonObject accepted = roundTrip(server, valid);
    QCOMPARE(accepted.value("code"), QJsonValue("LOGGER_NATIVE_QUEUED"));
    QCOMPARE(accepted.value("requestId"), QJsonValue("request-1"));
    QCOMPARE(roundTrip(server, valid).value("code"),
             QJsonValue("AGENT_NATIVE_INGRESS_REPLAYED"));
    QJsonObject tampered = envelope(QString(64, '2'));
    tampered.insert("payloadBase64", QString::fromLatin1("e30="));
    QCOMPARE(roundTrip(server,
                       QJsonDocument(tampered).toJson(QJsonDocument::Compact) + '\n')
                 .value("code"),
             QJsonValue("AGENT_NATIVE_INGRESS_AUTH_FAILED"));
    QJsonObject stale = envelope(QString(64, '3'));
    stale.insert("sentUtc", "2026-09-12T03:00:00.000Z");
    stale.insert("mac", NativeAgentIngress::signForTest(Secret, stale));
    QCOMPARE(roundTrip(server,
                       QJsonDocument(stale).toJson(QJsonDocument::Compact) + '\n')
                 .value("code"),
             QJsonValue("AGENT_NATIVE_INGRESS_INVALID"));
    QJsonObject wrong = envelope(QString(64, '4'));
    wrong.insert("mac", NativeAgentIngress::signForTest(QByteArray(32, 'x'), wrong));
    QCOMPARE(roundTrip(server,
                       QJsonDocument(wrong).toJson(QJsonDocument::Compact) + '\n')
                 .value("code"),
             QJsonValue("AGENT_NATIVE_INGRESS_AUTH_FAILED"));
    QJsonObject actionBound = envelope(QString(64, '6'));
    actionBound.insert("action", "wrong.action");
    actionBound.insert("mac", NativeAgentIngress::signForTest(Secret, actionBound));
    actionBound.insert("action", "nexus-contact.submit");
    QCOMPARE(roundTrip(server, QJsonDocument(actionBound).toJson(QJsonDocument::Compact) + '\n')
                 .value("code"),
             QJsonValue("AGENT_NATIVE_INGRESS_AUTH_FAILED"));
    QJsonObject protocolBound = envelope(QString(64, '7'));
    protocolBound.insert("protocol", QJsonObject{{"major", 2}, {"minor", 0}});
    protocolBound.insert("mac", NativeAgentIngress::signForTest(Secret, protocolBound));
    protocolBound.insert("protocol", QJsonObject{{"major", 1}, {"minor", 0}});
    QCOMPARE(roundTrip(server, QJsonDocument(protocolBound).toJson(QJsonDocument::Compact) + '\n')
                 .value("code"),
             QJsonValue("AGENT_NATIVE_INGRESS_AUTH_FAILED"));
    QJsonObject uppercase = envelope(QString(64, '8'));
    uppercase.insert("mac", uppercase.value("mac").toString().toUpper());
    QCOMPARE(roundTrip(server, QJsonDocument(uppercase).toJson(QJsonDocument::Compact) + '\n')
                 .value("code"),
             QJsonValue("AGENT_NATIVE_INGRESS_INVALID"));
    QJsonObject localTime = envelope(QString(64, '9'));
    localTime.insert("sentUtc", "2026-09-12T03:30:00.000");
    localTime.insert("mac", NativeAgentIngress::signForTest(Secret, localTime));
    QCOMPARE(roundTrip(server, QJsonDocument(localTime).toJson(QJsonDocument::Compact) + '\n')
                 .value("code"),
             QJsonValue("AGENT_NATIVE_INGRESS_INVALID"));
  }

  void socketRejectsOversizeBeforeJsonAllocation() {
    QTemporaryDir dir;
    FakeCredentialVault vault;
    LoggerIngestion logger(&vault, dir.filePath("journal.json"));
    NativeAgentIngress ingress(&vault, &logger, [] { return Now; },
                               [] { return Secret; });
    QVERIFY(ingress.initialize());
    QLocalServer server;
    QVERIFY(server.listen(dir.filePath("oversize.sock")));
    NativeAgentIngressServer ingressServer(&server, &ingress);
    const QByteArray exact =
        QByteArray(NativeAgentIngress::MaxRequestBytes - 1, 'x') + '\n';
    QCOMPARE(roundTrip(server,
                       {exact.left(7000), exact.mid(7000)})
                 .value("code"),
             QJsonValue("AGENT_NATIVE_INGRESS_INVALID"));
    const QByteArray over =
        QByteArray(NativeAgentIngress::MaxRequestBytes, 'x') + '\n';
    QCOMPARE(roundTrip(server,
                       {over.left(5000), over.mid(5000, 5000), over.mid(10000)})
                 .value("code"),
             QJsonValue("AGENT_NATIVE_INGRESS_TOO_LARGE"));
  }

  void reconnectRecoversEncryptedQueueWithoutDuplicate() {
    QTemporaryDir dir;
    FakeCredentialVault vault;
    const QString path = dir.filePath("journal.json");
    {
      LoggerIngestion logger(&vault, path);
      logger.setAccountScope("account-1");
      QVERIFY(logger.applyProfile(profile()).value("ok").toBool());
      NativeAgentIngress ingress(&vault, &logger, [] { return Now; },
                                 [] { return Secret; });
      QVERIFY(ingress.initialize());
      QLocalServer server;
      QVERIFY(server.listen(dir.filePath("first.sock")));
      NativeAgentIngressServer ingressServer(&server, &ingress);
      QCOMPARE(roundTrip(server,
                         QJsonDocument(envelope()).toJson(QJsonDocument::Compact) + '\n')
                   .value("code"),
               QJsonValue("LOGGER_NATIVE_QUEUED"));
    }
    LoggerIngestion logger(&vault, path);
    logger.setAccountScope("account-1");
    QCOMPARE(logger.pendingEvents().size(), 1);
    NativeAgentIngress ingress(&vault, &logger, [] { return Now; });
    QVERIFY(ingress.initialize());
    QLocalServer server;
    QVERIFY(server.listen(dir.filePath("second.sock")));
    NativeAgentIngressServer ingressServer(&server, &ingress);
    QCOMPARE(roundTrip(server,
                       QJsonDocument(envelope(QString(64, '5')))
                               .toJson(QJsonDocument::Compact) + '\n')
                 .value("code"),
             QJsonValue("LOGGER_NATIVE_DUPLICATE"));
    QCOMPARE(logger.pendingEvents().size(), 1);
  }

  void authenticatedRequestFailsClosedWithoutLogger() {
    QTemporaryDir dir;
    FakeCredentialVault vault;
    NativeAgentIngress ingress(&vault, nullptr, [] { return Now; },
                               [] { return Secret; });
    QVERIFY(ingress.initialize());
    QLocalServer server;
    QVERIFY(server.listen(dir.filePath("no-logger.sock")));
    NativeAgentIngressServer ingressServer(&server, &ingress);
    QCOMPARE(roundTrip(server, QJsonDocument(envelope(QString(64, 'a')))
                                   .toJson(QJsonDocument::Compact) + '\n')
                 .value("code"),
             QJsonValue("AGENT_NATIVE_INGRESS_UNAVAILABLE"));
  }

  void partialConnectionTimesOutAndConnectionCountIsBounded() {
    QTemporaryDir dir;
    FakeCredentialVault vault;
    LoggerIngestion logger(&vault, dir.filePath("journal.json"));
    NativeAgentIngress ingress(&vault, &logger, [] { return Now; },
                               [] { return Secret; });
    QVERIFY(ingress.initialize());
    QLocalServer server;
    QVERIFY(server.listen(dir.filePath("bounded.sock")));
    NativeAgentIngressServer ingressServer(&server, &ingress, {}, nullptr, 50);
    std::array<QLocalSocket, NativeAgentIngressServer::MaxActiveConnections>
        held;
    for (QLocalSocket &client : held) {
      client.connectToServer(server.serverName());
      QVERIFY(client.waitForConnected(1000));
      client.write("{");
      QCoreApplication::processEvents();
    }
    QLocalSocket excess;
    excess.connectToServer(server.serverName());
    QVERIFY(excess.waitForConnected(1000));
    QElapsedTimer timer;
    timer.start();
    while (!excess.canReadLine() && timer.elapsed() < 1000) {
      QCoreApplication::processEvents(QEventLoop::AllEvents, 10);
      QTest::qWait(1);
    }
    QVERIFY(excess.canReadLine());
    QCOMPARE(QJsonDocument::fromJson(excess.readLine())
                 .object()
                 .value("result")
                 .toObject()
                 .value("code"),
             QJsonValue("AGENT_NATIVE_INGRESS_BUSY"));
    timer.restart();
    while (!held[0].canReadLine() && timer.elapsed() < 1000) {
      QCoreApplication::processEvents(QEventLoop::AllEvents, 10);
      QTest::qWait(1);
    }
    QVERIFY(held[0].canReadLine());
    QCOMPARE(QJsonDocument::fromJson(held[0].readLine())
                 .object()
                 .value("result")
                 .toObject()
                 .value("code"),
             QJsonValue("AGENT_NATIVE_INGRESS_TIMEOUT"));
  }
};

QTEST_MAIN(NativeAgentIngressTests)
#include "native_agent_ingress_tests.moc"
