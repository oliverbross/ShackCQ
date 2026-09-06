#include "shackcq/desktop/DesktopParityPlatform.hpp"

#include <QDateTime>
#include <QTcpServer>
#include <QTcpSocket>
#include <QTemporaryDir>
#include <QtTest>

using namespace shackcq::desktop;

class DesktopFunctionalOwnerTests final : public QObject {
    Q_OBJECT
private slots:
    void closureLedgerHasNoFoundationRows();
    void nativeProtocolContractsFailClosed();
    void presetsEqAndOperatingContextRestoreSafe();
    void digiChaserContestAndGroupsUseVersionedOwners();
    void groupsClientUsesVaultAliasAndReconcilesFakeService();
    void scpCacheAndN1mmRuntimeAreBoundedAndFailClosed();
    void empiricalBandMapAndSatelliteUseProductionAlgorithms();
};

void DesktopFunctionalOwnerTests::closureLedgerHasNoFoundationRows() {
    QTemporaryDir root;
    DesktopParityPlatform platform;
    QString error;
    QVERIFY2(platform.open(root.filePath("db"), root.filePath("cache"), false, &error), qPrintable(error));
    QCOMPARE(platform.closureLedger()->rowCount(), 17);
    QCOMPARE(platform.ownerHealth()->rowCount(), 17);
    for (int index = 0; index < platform.closureLedger()->rowCount(); ++index) {
        const QString state = platform.closureLedger()->item(index).value("state").toString();
        QVERIFY(!state.contains("FOUNDATION_WIRED"));
        QVERIFY(state.startsWith("SOURCE_COMPLETE"));
    }
}

void DesktopFunctionalOwnerTests::nativeProtocolContractsFailClosed() {
    QTemporaryDir root;
    DesktopParityPlatform platform;
    QString error;
    QVERIFY2(platform.open(root.filePath("db"), root.filePath("cache"), false, &error), qPrintable(error));
    QCOMPARE(platform.nativeRadioProfiles()->rowCount(), 7);
    QCOMPARE(platform.nativeRotatorProtocols()->rowCount(), 6);
    QCOMPARE(platform.nativeRadioFrame("KX3", "vfoa").value("frame").toByteArray(), QByteArray("FA;"));
    const QVariantMap setter = platform.nativeRadioFrame("KX3", "setFrequency", 14062000ULL);
    QCOMPARE(setter.value("frame").toByteArray(), QByteArray("FA00014062000;"));
    QVERIFY(!setter.value("transmitAccepted").toBool());
    QCOMPARE(platform.nativeRadioFrame("RGO-UNKNOWN", "filter").value("state").toString(),
             QString("UNKNOWN_REMAINS_UNKNOWN"));
    QVERIFY(platform.nativeRadioFrame("KX3", "ptt", true).value("frame").toByteArray().isEmpty());

    QCOMPARE(platform.nativeRotatorFrame("GS232", "query").value("frame").toByteArray(), QByteArray("C2\r"));
    const QVariantMap move = platform.nativeRotatorFrame("EASYCOMM", "move", 180.0, 25.5);
    QCOMPARE(move.value("state").toString(), QString("CONFIRMATION_AND_ACCEPTANCE_REQUIRED"));
    QVERIFY(!move.value("movementAccepted").toBool());
    QVERIFY(platform.nativeRotatorFrame("GS232", "move", 999.0, 0.0).isEmpty());
}

void DesktopFunctionalOwnerTests::presetsEqAndOperatingContextRestoreSafe() {
    QTemporaryDir root;
    DesktopParityPlatform platform;
    QString error;
    QVERIFY2(platform.open(root.filePath("db"), root.filePath("cache"), false, &error), qPrintable(error));
    QVERIFY(platform.savePreset({{"id", "new"}, {"title", "15 m CW"},
                                 {"frequencyHz", 21062000ULL}, {"detail", "CW · 400 Hz"}}));
    QVERIFY(platform.reviewPresetRecall("new"));
    QVERIFY(platform.activeReview().contains("no connect/PTT/TUNE"));
    QVERIFY(platform.saveEqDraft({0, 1, 2, 3, 2, 1, 0, -1}, {-1, 0, 1, 2, 3, 2, 1, 0}));
    QVERIFY(platform.reviewEqApply());
    QVERIFY(platform.activeReview().contains("no command sent"));
    QVERIFY(platform.updateOperatingContext({{"station", "station-test"}, {"mode", "CW"},
                                             {"frequencyHz", 21062000ULL}}));
    QVERIFY(!platform.operatingContext().value("transmitAccepted").toBool());
    QVERIFY(!platform.updateOperatingContext({{"ptt", true}}));
    platform.globalStop();
    QVERIFY(!platform.operatingContext().value("rotatorMovementAccepted").toBool());
}

void DesktopFunctionalOwnerTests::digiChaserContestAndGroupsUseVersionedOwners() {
    QTemporaryDir root;
    DesktopParityPlatform platform;
    QString error;
    QVERIFY2(platform.open(root.filePath("db"), root.filePath("cache"), false, &error), qPrintable(error));
    QVERIFY(platform.startDigiReceive("FT8", "test:stereo:0", 12000));
    QVERIFY(platform.digiState().startsWith("RX_RUNNING"));
    const qint64 now = QDateTime::currentSecsSinceEpoch();
    const QVariantMap decode{{"id", "decode-1"}, {"callsign", "K1ABC"}, {"mode", "FT8"},
                             {"slotUtc", now}, {"message", "CQ K1ABC FN31"}, {"snr", -8},
                             {"source", "LIVE_CAPTURE"}};
    QVERIFY(platform.ingestLocalDecode(decode));
    QCOMPARE(platform.digiDecodes()->rowCount(), 1);
    QVariantMap chase = decode;
    chase["band"] = "20m"; chase["needed"] = true; chase["watchlisted"] = true; chase["evidence"] = .8;
    QVERIFY2(platform.startDxChaser(chase, true), qPrintable(platform.activeReview()));
    chase["source"] = "CLUSTER";
    QVERIFY(!platform.startDxChaser(chase, false));

    QVERIFY(platform.startContest("CQ-WW", "station-test"));
    QVERIFY(platform.stageContestQso({{"callsign", "DL1AAA"}, {"band", "20m"}, {"mode", "CW"},
                                      {"exchangeReceived", "5NN 14"}, {"points", 3}, {"multiplier", "DL"}}));
    const QVariantMap score = platform.contestScore();
    QCOMPARE(score.value("qsos").toInt(), 1);
    QCOMPARE(score.value("score").toInt(), 3);
    const QVariantMap n1mm = platform.parseN1mmPacket(
        "<contactinfo><app>Test</app><call>K1ABC</call><band>14</band></contactinfo>");
    QCOMPARE(n1mm.value("type").toString(), QString("contactinfo"));
    QVERIFY(!n1mm.value("trusted").toBool());
    QVERIFY(!n1mm.value("armed").toBool());

    QVERIFY(platform.queueGroupsDraft({{"groupId", "group-1"}, {"subject", "Field test"}, {"body", "Draft body"}}));
    QCOMPARE(platform.groupsOutbox()->rowCount(), 1);
    const QString outbox = platform.groupsOutbox()->item(0).value("key").toString();
    QVERIFY(platform.reconcileGroupsDelivery(outbox, "AMBIGUOUS"));
    QCOMPARE(platform.groupsOutbox()->item(0).value("state").toString(), QString("AMBIGUOUS"));
    platform.stopDigi();
    QVERIFY(platform.startDigiReceive("CW", "tci:0", 48000));
    QVERIFY(platform.digiState().contains("CW"));
    platform.stopDigi();
}

void DesktopFunctionalOwnerTests::groupsClientUsesVaultAliasAndReconcilesFakeService() {
    QTemporaryDir root;
    DesktopParityPlatform platform;
    QString error;
    QVERIFY2(platform.open(root.filePath("db"), root.filePath("cache"), false, &error), qPrintable(error));
    QTcpServer server;
    QVERIFY(server.listen(QHostAddress::LocalHost, 0));
    int requests = 0;
    bool bearerSeen = true;
    connect(&server, &QTcpServer::newConnection, this, [&] {
        while (QTcpSocket *socket = server.nextPendingConnection()) {
            connect(socket, &QTcpSocket::readyRead, socket, [&, socket] {
                const QByteArray request = socket->readAll();
                if (!request.contains("\r\n\r\n")) return;
                bearerSeen = bearerSeen &&
                    request.contains("Authorization: Bearer test-token\r\n");
                ++requests;
                QByteArray body;
                if (request.startsWith("GET /api/v1/getsubs"))
                    body = R"({"data":[{"group_id":42,"group_name":"ShackCQ Test","status":"member"}]})";
                else if (request.startsWith("POST /api/v1/newdraft"))
                    body = R"({"id":91})";
                else if (request.startsWith("POST /api/v1/updatedraft"))
                    body = R"({"object":"draft"})";
                else if (request.startsWith("POST /api/v1/postdraft"))
                    body = R"({"message_id":"server-123"})";
                else
                    body = R"({"object":"error"})";
                const QByteArray response =
                    "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nConnection: close\r\nContent-Length: " +
                    QByteArray::number(body.size()) + "\r\n\r\n" + body;
                socket->write(response);
                socket->disconnectFromHost();
            });
        }
    });
    platform.setGroupsEndpointForTest(
        QUrl(QStringLiteral("http://127.0.0.1:%1/api/v1").arg(server.serverPort())));
    platform.setCredentialResolver([](const QString &alias) {
        return alias == "groups-test" ? QStringLiteral("test-token") : QString{};
    });
    QVERIFY(platform.setGroupsCredentialAlias("groups-test"));
    QVERIFY(platform.refreshGroupsMemberships());
    QTRY_COMPARE_WITH_TIMEOUT(platform.groupsMemberships()->rowCount(), 1, 3000);
    QCOMPARE(platform.groupsMemberships()->item(0).value("key").toString(), QString("42"));

    QVERIFY(platform.queueGroupsDraft({{"groupId", "42"}, {"subject", "Reviewed topic"},
                                       {"body", "Bounded fake delivery"}}));
    const QString outbox = platform.groupsOutbox()->item(0).value("key").toString();
    QVERIFY(platform.sendGroupsOutbox(outbox));
    QTRY_COMPARE_WITH_TIMEOUT(platform.groupsOutbox()->item(0).value("state").toString(),
                              QString("DELIVERED"), 5000);
    QCOMPARE(requests, 4);
    QVERIFY(bearerSeen);
}

void DesktopFunctionalOwnerTests::scpCacheAndN1mmRuntimeAreBoundedAndFailClosed() {
    QTemporaryDir root;
    DesktopParityPlatform platform;
    QString error;
    QVERIFY2(platform.open(root.filePath("db"), root.filePath("cache"), false, &error), qPrintable(error));

    QTcpServer server;
    QVERIFY(server.listen(QHostAddress::LocalHost, 0));
    connect(&server, &QTcpServer::newConnection, this, [&] {
        while (QTcpSocket *socket = server.nextPendingConnection()) {
            connect(socket, &QTcpSocket::readyRead, socket, [socket] {
                socket->readAll();
                const QByteArray body = "K1ABC\nK1ABD\nDL1AAA\nOM0RX\n";
                socket->write("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nConnection: close\r\nContent-Length: " +
                              QByteArray::number(body.size()) + "\r\n\r\n" + body);
                socket->disconnectFromHost();
            });
        }
    });
    platform.setScpEndpointForTest(QUrl(QStringLiteral("http://127.0.0.1:%1/MASTER.SCP").arg(server.serverPort())));
    QVERIFY(platform.refreshScp());
    QTRY_COMPARE_WITH_TIMEOUT(platform.scpStatus().value("rowCount").toInt(), 4, 3000);
    const QVariantMap lookup = platform.scpLookup("K1A", 10);
    QVERIFY(!lookup.value("exact").toBool());
    QVERIFY(lookup.value("likelyBust").toBool());
    QCOMPARE(lookup.value("suggestions").toList().size(), 2);
    const QString digest = platform.scpStatus().value("digest").toString();
    QVERIFY(!platform.importScpPayloadForTest("K1ABC\nnot a call!\n", QUrl("https://invalid.test/MASTER.SCP"), 1, &error));
    QCOMPARE(platform.scpStatus().value("digest").toString(), digest);

    QVERIFY(platform.registerN1mmPeer("peer-a", "tcp://127.0.0.1:12060"));
    QVERIFY(platform.setN1mmPeerTrusted("peer-a", true));
    const QByteArray xml = "<contactinfo><app>N1MM</app><call>K1ABC</call><band>14</band><mode>CW</mode><id>qso-1</id></contactinfo>";
    const QVariantMap parsed = platform.parseN1mmPacket(xml);
    QCOMPARE(parsed.value("fieldCount").toInt(), 5);
    QCOMPARE(parsed.value("policy").toString(), QString("REVIEW_ONLY"));
    QVERIFY(!parsed.value("armed").toBool());
    const QByteArray frame = platform.frameN1mmTcpPacket(xml);
    QVERIFY(!frame.isEmpty());
    QCOMPARE(platform.parseN1mmTcpFrames(frame + frame).size(), 2);
    QVERIFY(platform.ingestN1mmPacket("peer-a", xml));
    QVERIFY(platform.ingestN1mmPacket("peer-a", xml));
    QVERIFY(platform.activeReview().contains("no radio, Keyer, Digi or Chaser action"));

    QVERIFY(platform.startN1mmDiscovery(0));
    QUdpSocket sender;
    QCOMPARE(sender.writeDatagram(xml, QHostAddress::LocalHost, platform.n1mmDiscoveryPortForTest()), qint64(xml.size()));
    QTRY_VERIFY_WITH_TIMEOUT(platform.n1mmState().startsWith("HEARTBEAT"), 3000);
    platform.stopN1mmRuntime();

    QTcpServer n1mmServer;
    QVERIFY(n1mmServer.listen(QHostAddress::LocalHost, 0));
    QVERIFY(platform.registerN1mmPeer("peer-tcp", QStringLiteral("tcp://127.0.0.1:%1").arg(n1mmServer.serverPort())));
    QVERIFY(platform.setN1mmPeerTrusted("peer-tcp", true));
    QVERIFY(platform.connectN1mmPeer("peer-tcp"));
    QVERIFY(n1mmServer.waitForNewConnection(3000));
    QTcpSocket *n1mmSocket = n1mmServer.nextPendingConnection();
    QVERIFY(n1mmSocket);
    QCOMPARE(n1mmSocket->write(frame), qint64(frame.size()));
    QVERIFY(n1mmSocket->waitForBytesWritten(3000));
    QTRY_VERIFY_WITH_TIMEOUT(platform.n1mmState().startsWith("HEARTBEAT"), 3000);
    platform.stopN1mmRuntime();
    QVERIFY(platform.updateN1mmPeerLifecycle("peer-a", "DISCONNECTED"));
    QVERIFY(platform.n1mmState().contains("unarmed"));
}

void DesktopFunctionalOwnerTests::empiricalBandMapAndSatelliteUseProductionAlgorithms() {
    QTemporaryDir root;
    DesktopParityPlatform platform;
    QString error;
    QVERIFY2(platform.open(root.filePath("db"), root.filePath("cache"), false, &error), qPrintable(error));
    const qint64 now = QDateTime::currentSecsSinceEpoch();
    const QVariantMap outlook = platform.computeEmpiricalOutlook({
        QVariantMap{{"band", "20m"}, {"observedUtc", now - 120}, {"weight", 2.0}, {"verification", "HIT"}},
        QVariantMap{{"band", "20m"}, {"observedUtc", now - 240}, {"weight", 1.0}, {"verification", "MISS"}},
        QVariantMap{{"band", "40m"}, {"observedUtc", now - 360}, {"weight", .5}, {"verification", "UNVERIFIABLE"}}
    }, 30);
    QCOMPARE(outlook.value("claim").toString(), QString("EMPIRICAL_NOT_P533"));
    QCOMPARE(outlook.value("bands").toList().size(), 2);

    const QVariantList evaluated = platform.evaluateBandMap({
        QVariantMap{{"callsign", "K1ABC"}, {"frequencyHz", 14074000ULL}, {"needed", true}, {"watchlisted", true}},
        QVariantMap{{"callsign", "DL1AAA"}, {"frequencyHz", 14062000ULL}, {"worked", true}}
    });
    QCOMPARE(evaluated.size(), 2);
    QCOMPARE(evaluated.first().toMap().value("callsign").toString(), QString("K1ABC"));

    const QString one = "1 00005U 58002B   00179.78495062  .00000023  00000-0  28098-4 0  4753";
    const QString two = "2 00005  34.2682 348.7242 1859667 331.7664  19.3264 10.82419157413667";
    const QVariantMap passes = platform.predictSatellitePasses("VANGUARD 1", one, two, 48.15, 17.11, .2,
                                                                962131819, 962131819 + 86400);
    QVERIFY(!passes.isEmpty());
    QVERIFY(passes.value("receiveOnly").toBool());
    QVERIFY(!passes.value("automaticDoppler").toBool());
}

QTEST_GUILESS_MAIN(DesktopFunctionalOwnerTests)
#include "desktop_functional_owner_tests.moc"
