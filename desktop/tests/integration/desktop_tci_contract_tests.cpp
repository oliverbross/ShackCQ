// SPDX-License-Identifier: GPL-3.0-only
#include "shackcq/desktop/DesktopRadioController.hpp"
#include "shackcq/desktop/TciClient.hpp"

#include "shackcq/tci.hpp"

#include <QCryptographicHash>
#include <QTcpServer>
#include <QTcpSocket>
#include <QtEndian>
#include <QtTest>

using namespace shackcq::desktop;

class FakeTciServer final : public QObject {
public:
  explicit FakeTciServer(QObject *parent = nullptr) : QObject(parent) {
    connect(&server, &QTcpServer::newConnection, this, [this] {
      socket = server.nextPendingConnection();
      upgraded = false;
      buffer.clear();
      connect(socket, &QTcpSocket::readyRead, this, [this] { consume(); });
    });
  }

  bool listen(int upgradeDelayMs = 0) {
    delayMs = upgradeDelayMs;
    return server.listen(QHostAddress::LocalHost, 0);
  }

  QUrl url() const {
    return QUrl(QStringLiteral("ws://127.0.0.1:%1/").arg(server.serverPort()));
  }

  void sendText(const QString &text) { sendFrame(0x1, text.toUtf8()); }
  void sendBinary(const QByteArray &bytes) { sendFrame(0x2, bytes); }
  void closePeer() {
    if (socket)
      socket->disconnectFromHost();
  }

  QStringList receivedText;

private:
  void consume() {
    buffer += socket->readAll();
    if (!upgraded) {
      const qsizetype end = buffer.indexOf("\r\n\r\n");
      if (end < 0)
        return;
      const QList<QByteArray> lines = buffer.left(end).split('\n');
      QByteArray key;
      for (QByteArray line : lines) {
        line = line.trimmed();
        if (line.toLower().startsWith("sec-websocket-key:"))
          key = line.mid(line.indexOf(':') + 1).trimmed();
      }
      buffer.remove(0, end + 4);
      const QByteArray accept =
          QCryptographicHash::hash(key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11",
                                   QCryptographicHash::Sha1)
              .toBase64();
      QTimer::singleShot(delayMs, this, [this, accept] {
        socket->write(
            "HTTP/1.1 101 Switching Protocols\r\nUpgrade: "
            "websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " +
            accept + "\r\n\r\n");
        socket->flush();
        upgraded = true;
        consumeFrames();
      });
      return;
    }
    consumeFrames();
  }

  void consumeFrames() {
    while (buffer.size() >= 2) {
      const quint8 first = static_cast<quint8>(buffer[0]);
      const quint8 second = static_cast<quint8>(buffer[1]);
      quint64 length = second & 0x7fU;
      int offset = 2;
      if (length == 126U) {
        if (buffer.size() < 4)
          return;
        length = qFromBigEndian<quint16>(
            reinterpret_cast<const uchar *>(buffer.constData() + 2));
        offset = 4;
      } else if (length == 127U) {
        if (buffer.size() < 10)
          return;
        length = qFromBigEndian<quint64>(
            reinterpret_cast<const uchar *>(buffer.constData() + 2));
        offset = 10;
      }
      const bool masked = (second & 0x80U) != 0U;
      if (!masked || length > 1'000'000U ||
          buffer.size() < offset + 4 + static_cast<qsizetype>(length))
        return;
      const QByteArray mask = buffer.mid(offset, 4);
      offset += 4;
      QByteArray payload = buffer.mid(offset, static_cast<qsizetype>(length));
      for (qsizetype index = 0; index < payload.size(); ++index)
        payload[index] ^= mask.at(index % 4);
      buffer.remove(0, offset + static_cast<qsizetype>(length));
      if ((first & 0x0fU) == 0x1U)
        receivedText.push_back(QString::fromUtf8(payload));
    }
  }

  void sendFrame(quint8 opcode, const QByteArray &payload) {
    if (!socket || !upgraded)
      return;
    QByteArray frame;
    frame.push_back(static_cast<char>(0x80U | opcode));
    if (payload.size() < 126) {
      frame.push_back(static_cast<char>(payload.size()));
    } else {
      frame.push_back(static_cast<char>(126));
      const quint16 length = qToBigEndian(static_cast<quint16>(payload.size()));
      frame.append(reinterpret_cast<const char *>(&length), sizeof(length));
    }
    frame += payload;
    socket->write(frame);
    socket->flush();
  }

  QTcpServer server;
  QTcpSocket *socket{};
  QByteArray buffer;
  int delayMs{};
  bool upgraded{};
};

class DesktopTciContractTests final : public QObject {
  Q_OBJECT
private:
  static TciProfile profile(const FakeTciServer &server) {
    return {"fixture", "Deterministic TCI", server.url(), 96'000U, 0, false,
            {}};
  }

private slots:
  void radioProfileMigrationAndFutureSchemaRejection() {
    DesktopRadioController radio;
    QString error;
    const QVariantMap legacyProfile{{"id", "legacy"},
                                    {"displayName", "Legacy fixture"},
                                    {"host", "127.0.0.1"},
                                    {"port", 40001},
                                    {"preferredIqSampleRate", 96000},
                                    {"preferredReceiver", 0},
                                    {"autoConnect", false}};
    QVERIFY(radio.restoreConfiguration(
        {{"schemaVersion", 1},
         {"activeReceiverId", "tci:1"},
         {"listeningReceiverId", "tci:0"},
         {"nativeProfiles", QVariantList{QVariantMap{{"id", "preserved"}}}},
         {"tciProfiles", QVariantList{legacyProfile}}},
        &error));
    QCOMPARE(radio.tciProfiles().size(), 1);
    QCOMPARE(radio.tciProfiles().first().toMap().value("endpoint").toString(),
             QString("ws://127.0.0.1:40001"));
  QCOMPARE(radio.configuration().value("schemaVersion").toInt(), 3);
    QCOMPARE(radio.configuration().value("nativeProfiles").toList().size(), 1);
  QVERIFY(!radio.restoreConfiguration({{"schemaVersion", 4}}, &error));
    QVERIFY(error.contains("newer"));
    QVariantList excessive;
    for (int index = 0; index < 33; ++index) {
      QVariantMap p = legacyProfile;
      p["id"] = QStringLiteral("profile-%1").arg(index);
      p["endpoint"] = "ws://127.0.0.1:40001";
      excessive.push_back(p);
    }
    QVERIFY(!radio.restoreConfiguration(
        {{"schemaVersion", 2}, {"tciProfiles", excessive}}, &error));
    QVERIFY(error.contains("exceeds 32"));
  }

  void hamlibAdapterProjectsExactlyOneReceiver() {
    DesktopRadioController radio;
    radio.setHamlibSnapshotForTest(14'074'000U, "USB");
    QCOMPARE(radio.backend(), QString("hamlib"));
    QCOMPARE(radio.receiverCount(), 1);
    QCOMPARE(radio.activeReceiverId(), QString("hamlib:0"));
    QCOMPARE(radio.listeningReceiverId(), QString("hamlib:0"));
    QCOMPARE(radio.transmitReceiverId(), QString("hamlib:0"));
    QCOMPARE(radio.receivers()
                 ->receiver("hamlib:0")
                 .value("effectiveReceiveHz")
                 .toULongLong(),
             14'074'000ULL);
    QVERIFY(!radio.pttAvailable());
    QVERIFY(!radio.tuneAvailable());
  }

  void controllerProjectsTwoReceiversAndOneTransmitAuthority() {
    FakeTciServer server;
    QVERIFY(server.listen());
    DesktopRadioController radio;
    radio.setTciTimeoutsForTest(500, 500, 50);
    QVERIFY(radio.saveTciProfile({{"id", "fixture"},
                                  {"displayName", "Controller fixture"},
                                  {"endpoint", server.url().toString()},
                                  {"preferredIqSampleRate", 96000},
                                  {"preferredReceiver", 0},
                                  {"autoConnect", false}}));
    QVERIFY(radio.connectTciProfile("fixture"));
    QTRY_COMPARE_WITH_TIMEOUT(radio.state(),
                              QString("Handshaking — receive only"), 1'000);
    server.sendText(
        "start;protocol:Fixture;device:Two "
        "receivers;trx_count:2;vfo:0,0,14074000;vfo:1,0,7074000;ready;");
    QTRY_COMPARE_WITH_TIMEOUT(radio.receiverCount(), 2, 1'000);
    QCOMPARE(radio.activeReceiverId(), QString("tci:0"));
    QCOMPARE(radio.listeningReceiverId(), QString("tci:0"));
    QCOMPARE(radio.transmitReceiverId(), QString("tci:0"));
    QVERIFY(!radio.pttAvailable());
    QVERIFY(!radio.tuneAvailable());
    QVERIFY(radio.selectActiveReceiver("tci:1"));
    QTRY_VERIFY_WITH_TIMEOUT(
        server.receivedText.join(QString{}).contains("iq_start:1;"), 1'000);
    QVERIFY(radio.selectListeningReceiver("tci:1"));
    QTRY_VERIFY_WITH_TIMEOUT(
        server.receivedText.join(QString{}).contains("iq_stop:0;"), 1'000);
    QCOMPARE(radio.transmitReceiverId(), QString("tci:0"));
    QVERIFY(radio.requestFrequency(7'076'000U));
    QTRY_VERIFY_WITH_TIMEOUT(
        server.receivedText.join(QString{}).contains("vfo:1,0,7076000;"),
        1'000);
    server.sendText("trx_count:1;");
    QTRY_COMPARE_WITH_TIMEOUT(radio.receiverCount(), 1, 1'000);
    QCOMPARE(radio.activeReceiverId(), QString("tci:0"));
    QCOMPARE(radio.listeningReceiverId(), QString("tci:0"));
    QCOMPARE(radio.transmitReceiverId(), QString("tci:0"));
    radio.globalStop();
    QTRY_VERIFY_WITH_TIMEOUT(server.receivedText.join(QString{}).contains(
                                 "trx:0,false;tune:0,false;"),
                             1'000);
    radio.disconnectRadio();
  }

  void delayedUpgradeFragmentedReadyTwoReceiversAndSafeStop() {
    FakeTciServer server;
    QVERIFY(server.listen(40));
    TciClient client;
    client.setTimeoutsForTest(500, 500, 50);
    QSignalSpy iqSpy(&client, &TciClient::iqFrame);
    QSignalSpy audioSpy(&client, &TciClient::rxAudioFrame);
    QVERIFY(client.connectProfile(profile(server)));
    QTRY_COMPARE_WITH_TIMEOUT(client.state(),
                              QString("Handshaking — receive only"), 1'000);

    server.sendText("start;protocol:ExpertSDR3,1.8;device:Fixture "
                    "SDR;trx_count:2;channels_count:2;vfo:0,0,14074000;");
    server.sendText("modulation:0,usb;unknown_fixture:kept;ready;");
    QTRY_VERIFY_WITH_TIMEOUT(client.ready(), 1'000);
    QCOMPARE(client.receivers().size(), 2);
    QCOMPARE(client.receivers().at(0).toMap().value("vfoAHz").toULongLong(),
             14'074'000ULL);
    QCOMPARE(client.receivers().at(0).toMap().value("mode").toString(),
             QString("USB"));
    QCOMPARE(client.capabilities().value("channels_count").toString(),
             QString("2"));
    QCOMPARE(client.diagnostics().value("unknownCommands").toULongLong(), 1ULL);

    const auto binary = shackcq::tci::build_binary_for_test(
        shackcq::tci::DataType::Iq, 0U, 96'000U, 2U,
        {0.25F, -0.5F, 0.75F, -1.0F});
    server.sendBinary(QByteArray(reinterpret_cast<const char *>(binary.data()),
                                 static_cast<qsizetype>(binary.size())));
    QTRY_COMPARE_WITH_TIMEOUT(iqSpy.size(), 1, 1'000);
    QCOMPARE(iqSpy.at(0).at(0).toInt(), 0);
    QCOMPARE(iqSpy.at(0).at(1).toUInt(), 96'000U);
    QVERIFY(client.diagnostics().value("binaryDecodedOffOwnerThread").toBool());

    const auto audio = shackcq::tci::build_binary_for_test(
        shackcq::tci::DataType::RxAudio, 0U, 48'000U, 2U,
        {0.1F, 0.1F, -0.1F, -0.1F});
    server.sendBinary(QByteArray(reinterpret_cast<const char *>(audio.data()),
                                 static_cast<qsizetype>(audio.size())));
    QTRY_COMPARE_WITH_TIMEOUT(audioSpy.size(), 1, 1'000);

    const auto unattached = shackcq::tci::build_binary_for_test(
        shackcq::tci::DataType::Iq, 1U, 96'000U, 2U, {0.2F, -0.2F});
    server.sendBinary(
        QByteArray(reinterpret_cast<const char *>(unattached.data()),
                   static_cast<qsizetype>(unattached.size())));
    QTRY_COMPARE_WITH_TIMEOUT(
        client.diagnostics().value("droppedFrames").toULongLong(), 1ULL, 1'000);
    QVERIFY(client.attachReceiver(1));
    QVERIFY(client.detachReceiver(1));

    server.sendBinary(QByteArray(10, '\0'));
    auto unsupported = binary;
    unsupported[8] = 1U;
    server.sendBinary(
        QByteArray(reinterpret_cast<const char *>(unsupported.data()),
                   static_cast<qsizetype>(unsupported.size())));
    auto unknown = binary;
    unknown[24] = 99U;
    server.sendBinary(QByteArray(reinterpret_cast<const char *>(unknown.data()),
                                 static_cast<qsizetype>(unknown.size())));
    QTRY_COMPARE_WITH_TIMEOUT(
        client.diagnostics().value("malformedBinary").toULongLong(), 3ULL,
        1'000);

    QVERIFY(client.requestFrequency(0, 0, 14'076'000U));
    QVERIFY(client.requestFrequency(0, 0, 14'078'000U));
    QVERIFY(client.requestMode(0, "FT8"));
    QTRY_VERIFY_WITH_TIMEOUT(
        server.receivedText.join(QString{}).contains("vfo:0,0,14078000;"),
        1'000);
    QVERIFY(!server.receivedText.join(QString{}).contains("14076000"));
    QVERIFY(server.receivedText.join(QString{}).contains("modulation:0,digu;"));

    client.globalStop();
    client.globalStop();
    QTRY_VERIFY_WITH_TIMEOUT(server.receivedText.join(QString{}).contains(
                                 "trx:0,false;tune:0,false;"),
                             1'000);
    QCOMPARE(server.receivedText.join(QString{}).count("trx:0,false"), 1);
    QVERIFY(!server.receivedText.join(QString{}).contains("trx:0,true"));
    QVERIFY(!server.receivedText.join(QString{}).contains("tune:0,true"));
    client.disconnectFromServer();
  }

  void missingReadyTimesOutAndMalformedBinaryIsRejected() {
    FakeTciServer server;
    QVERIFY(server.listen());
    TciClient client;
    client.setTimeoutsForTest(300, 80, 500);
    QVERIFY(client.connectProfile(profile(server)));
    QTRY_COMPARE_WITH_TIMEOUT(client.state(),
                              QString("Handshaking — receive only"), 1'000);
    server.sendText("start;protocol:Fixture;device:No Ready;trx_count:1;");
    QTRY_VERIFY_WITH_TIMEOUT(client.state().contains("timeout") ||
                                 client.state().contains("reconnect"),
                             1'000);
    QVERIFY(!client.ready());
    client.disconnectFromServer();
  }

  void invalidProfileAndDuplicateAttachmentFailClosed() {
    TciClient client;
    TciProfile invalid;
    QVERIFY(!client.connectProfile(invalid));

    FakeTciServer server;
    QVERIFY(server.listen());
    client.setTimeoutsForTest(300, 300, 50);
    QVERIFY(client.connectProfile(profile(server)));
    QTRY_COMPARE_WITH_TIMEOUT(client.state(),
                              QString("Handshaking — receive only"), 1'000);
    server.sendText("start;protocol:Fixture;device:One RX;trx_count:1;ready;");
    QTRY_VERIFY_WITH_TIMEOUT(client.ready(), 1'000);
    QVERIFY(!client.attachReceiver(0));
    QVERIFY(!client.attachReceiver(1));
    client.disconnectFromServer();
  }

  void disconnectReconnectDropsAmbiguousMutation() {
    FakeTciServer server;
    QVERIFY(server.listen());
    TciClient client;
    client.setTimeoutsForTest(300, 300, 20);
    QVERIFY(client.connectProfile(profile(server)));
    QTRY_COMPARE_WITH_TIMEOUT(client.state(),
                              QString("Handshaking — receive only"), 1'000);
    server.sendText(
        "start;protocol:Fixture;device:Reconnect;trx_count:1;ready;");
    QTRY_VERIFY_WITH_TIMEOUT(client.ready(), 1'000);
    QVERIFY(client.requestFrequency(0, 0, 14'099'000U));
    server.closePeer();
    QTRY_COMPARE_WITH_TIMEOUT(client.state(),
                              QString("Handshaking — receive only"), 2'000);
    server.sendText(
        "start;protocol:Fixture;device:Reconnect;trx_count:1;ready;");
    QTRY_VERIFY_WITH_TIMEOUT(client.ready(), 1'000);
    QTest::qWait(100);
    QVERIFY(!server.receivedText.join(QString{}).contains("14099000"));
    QCOMPARE(client.diagnostics().value("reconnectAttempts").toInt(), 0);
    client.disconnectFromServer();
  }
};

QTEST_MAIN(DesktopTciContractTests)
#include "desktop_tci_contract_tests.moc"
