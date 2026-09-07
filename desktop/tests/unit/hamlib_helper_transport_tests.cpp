#include "shackcq/desktop/HamlibHelperTransport.hpp"

#include <QElapsedTimer>
#include <QTimer>
#include <QtTest>

using namespace shackcq::desktop;

class HamlibHelperTransportTests final : public QObject {
  Q_OBJECT
private slots:
  void blockedOperationIsPreemptedAndQuarantined() {
    HamlibHelperTransport transport;
    transport.setProgramForTest(QStringLiteral(SHACKCQ_HAMLIB_FIXTURE),
                                {QStringLiteral("block")});
    transport.setTimeoutsForTest(2'000, 40);
    QVERIFY(transport.open(1, "fixture", 0));
    const quint64 before = transport.epoch();
    QTimer::singleShot(10, &transport, [&transport] {
      QVERIFY(transport.priorityStop());
    });
    QElapsedTimer elapsed;
    elapsed.start();
    const QJsonObject result = transport.mutate(
        "radio.set.frequency", {{"frequencyHz", 14'076'000}});
    QVERIFY(elapsed.elapsed() < 1'000);
    QCOMPARE(result.value("code").toString(), QString("OPERATION_CANCELLED"));
    QVERIFY(transport.quarantined());
    QVERIFY(transport.epoch() > before);
    QCOMPARE(transport.mutate("radio.set.mode", {{"mode", "USB"}})
                 .value("code").toString(),
             QString("HELPER_QUARANTINED"));
  }

  void stopWithoutAnOwnedRouteFailsClosed() {
    HamlibHelperTransport transport;
    transport.setProgramForTest(QStringLiteral(SHACKCQ_HAMLIB_FIXTURE));
    QVERIFY(!transport.priorityStop());
    QVERIFY(transport.quarantined());
  }

  void lateResultIsRejectedAfterVerifiedPriorityStop() {
    HamlibHelperTransport transport;
    transport.setProgramForTest(QStringLiteral(SHACKCQ_HAMLIB_FIXTURE),
                                {QStringLiteral("late")});
    transport.setTimeoutsForTest(1'000, 250);
    QVERIFY(transport.open(1, "fixture", 0));
    QTimer::singleShot(10, &transport, [&transport] {
      QVERIFY(transport.priorityStop());
    });
    const QJsonObject result = transport.mutate(
        "radio.set.frequency", {{"frequencyHz", 14'076'000}});
    QCOMPARE(result.value("code").toString(), QString("OPERATION_CANCELLED"));
    QVERIFY(transport.quarantined());
    QCOMPARE(transport.mutate("radio.set.mode", {{"mode", "USB"}})
                 .value("code").toString(),
             QString("HELPER_QUARANTINED"));
    QVERIFY(transport.open(1, "fixture", 0));
    QCOMPARE(transport.snapshot().value("transmitting").toBool(), false);
  }

  void queuedMutationCannotPassAnExecutingOperation() {
    HamlibHelperTransport transport;
    transport.setProgramForTest(QStringLiteral(SHACKCQ_HAMLIB_FIXTURE),
                                {QStringLiteral("late")});
    transport.setTimeoutsForTest(1'000, 250);
    QVERIFY(transport.open(1, "fixture", 0));
    QJsonObject queued;
    QTimer::singleShot(10, &transport, [&] {
      queued = transport.mutate("radio.set.mode", {{"mode", "USB"}});
    });
    const QJsonObject first = transport.mutate(
        "radio.set.frequency", {{"frequencyHz", 14'076'000}});
    QVERIFY(first.value("ok").toBool());
    QCOMPARE(queued.value("code").toString(), QString("OPERATION_BUSY"));
  }

  void recoveryOpenRequiresFreshRxProof() {
    HamlibHelperTransport transport;
    transport.setProgramForTest(QStringLiteral(SHACKCQ_HAMLIB_FIXTURE));
    QVERIFY(transport.open(1, "fixture", 0));
    QVERIFY(!transport.quarantined());
    const QJsonObject result = transport.mutate(
        "radio.set.frequency", {{"frequencyHz", 14'076'000}});
    QVERIFY(result.value("ok").toBool());
    QCOMPARE(result.value("frequencyHz").toInt(), 14'076'000);
    QCOMPARE(transport.snapshot().value("meters").toObject().value("signal").toInt(),
             -73);
  }

  void failedRecoveryReadbackRemainsQuarantined() {
    HamlibHelperTransport transport;
    transport.setProgramForTest(QStringLiteral(SHACKCQ_HAMLIB_FIXTURE),
                                {QStringLiteral("rx-fail")});
    transport.setTimeoutsForTest(500, 100);
    QVERIFY(!transport.open(1, "fixture", 0));
    QVERIFY(transport.quarantined());
    QCOMPARE(transport.mutate("radio.set.mode", {{"mode", "USB"}})
                 .value("code").toString(),
             QString("HELPER_QUARANTINED"));
  }
};

QTEST_MAIN(HamlibHelperTransportTests)
#include "hamlib_helper_transport_tests.moc"
