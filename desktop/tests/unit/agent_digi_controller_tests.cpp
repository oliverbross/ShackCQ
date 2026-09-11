// SPDX-License-Identifier: GPL-3.0-only
#include "shackcq/desktop/AgentDigiController.hpp"
#include "shackcq/desktop/DesktopRadioController.hpp"
#include <QDateTime>
#include <QTemporaryDir>
#include <QtTest>

using namespace shackcq::desktop;

class AgentDigiControllerTests final : public QObject {
  Q_OBJECT
  static QJsonObject frame(const QString &action,const QJsonObject &parameters={}) {
    static quint64 sequence=0;
    return {{"type","digi.command"},{"protocol",QJsonObject{{"major",1},{"minor",2}}},
      {"commandId",QStringLiteral("command-%1-%2").arg(action).arg(++sequence)},{"agentId","agent-1"},{"deviceId","hamlib:1"},
      {"expectedGeneration",1},{"browserSessionId","session-1"},{"controlInstanceId","tab-1"},
      {"validUntilUtc",QDateTime::currentDateTimeUtc().addSecs(30).toString(Qt::ISODateWithMs)},
      {"action",action},{"parameters",parameters}};
  }
  static QJsonObject run(AgentDigiController &digi,const QString &action,const QJsonObject &parameters={}) {
    return digi.processCommand(frame(action,parameters),"agent-1","hamlib:1",1);
  }
  QTemporaryDir m_sessions;
private slots:
  void initTestCase() {
    QVERIFY(m_sessions.isValid());
    qputenv("SHACKCQ_DIGI_SESSION_DIR",m_sessions.path().toUtf8());
  }

  void retainedSessionsPersistExportAndDeleteWithoutRawAudio() {
    {
      DesktopRadioController radio;AgentDigiController digi(&radio);
      digi.m_sessionId="retained-fixture";digi.m_mode="FT8";digi.beginRetainedSession();
      digi.appendRetainedSamples(QVector<qint16>{100,-100,200,-200});
      digi.appendRetainedDecode(QJsonObject{{"id","decode-fixture"},{"slotStartMillis",QDateTime::currentMSecsSinceEpoch()},{"source","LIVE_CAPTURE"},{"exactSlotTiming",true},{"snr",-8},{"dt",0.1},{"audioHz",1500},{"text","CQ TEST"}});
      digi.finishRetainedSession();
      QCOMPARE(digi.snapshot("agent-1","hamlib:1",1).value("retainedSessions").toArray().size(),1);
    }
    DesktopRadioController radio;AgentDigiController restored(&radio);
    QCOMPARE(restored.snapshot("agent-1","hamlib:1",1).value("retainedSessions").toArray().size(),1);
    QVERIFY(run(restored,"digi.control.acquire").value("ok").toBool());
    const QJsonObject exported=run(restored,"digi.history.export",{{"sessionId","retained-fixture"}});
    QVERIFY(exported.value("ok").toBool());QVERIFY(exported.value("exportJson").toString().contains("CQ TEST"));QVERIFY(!exported.value("exportJson").toString().contains(".pcm"));
    QVERIFY(run(restored,"digi.history.delete",{{"sessionId","retained-fixture"},{"confirmed",true}}).value("ok").toBool());
    QVERIFY(restored.snapshot("agent-1","hamlib:1",1).value("retainedSessions").toArray().isEmpty());
  }

  void ftSequenceUsesOnlyExactLiveRowsAndBuildsCanonicalExchangeMessages() {
    DesktopRadioController radio;AgentDigiController digi(&radio);QString error;
    QVERIFY(run(digi,"digi.control.acquire")["ok"].toBool());
    QVERIFY(run(digi,"digi.configure",{{"mode","FT8"},{"submode",QJsonValue::Null},{"rxAudioHz",1500},{"txAudioHz",1500}})["ok"].toBool());
    digi.setAudioReadyForTest();digi.m_sessionId="live-session";
    digi.m_decodes.prepend(QJsonObject{{"id","live-cq"},{"slotStartMillis",15'000},{"source","LIVE_CAPTURE"},{"exactSlotTiming",true},{"snr",-12},{"dt",0.1},{"audioHz",1500},{"text","CQ K1ABC FN31"}});
    const QJsonObject parameters{{"role","SEARCH_AND_POUNCE"},{"stationCallsign","OM0RX"},{"stationGrid","JN88TQ"},{"decodeId","live-cq"},{"autoCq",false},{"autoCqLimit",3},{"retryLimit",3}};
    QVERIFY2(digi.startFtSequence(parameters,&error),qPrintable(error));
    QCOMPARE(digi.m_ftSequence["state"].toString(),QString("CALL_TX_PENDING"));
    QCOMPARE(digi.m_ftSequence["pendingMessage"].toString(),QString("K1ABC OM0RX JN88"));
    QCOMPARE(digi.m_ftSequence["holdReason"].toString(),QString("CLOCK_QUALITY_UNVERIFIED"));

    digi.resetPrepared();digi.m_state=AgentDigiController::State::RxVerified;digi.m_ftSequence["state"]="WAIT_REPORT";
    digi.advanceFtSequence(QJsonObject{{"id","report"},{"slotStartMillis",45'000},{"source","REFERENCE_RECORDING"},{"exactSlotTiming",false},{"snr",-7},{"text","OM0RX K1ABC -07"}});
    QCOMPARE(digi.m_ftSequence["state"].toString(),QString("WAIT_REPORT"));
    digi.advanceFtSequence(QJsonObject{{"id","report-live"},{"slotStartMillis",45'000},{"source","LIVE_CAPTURE"},{"exactSlotTiming",true},{"snr",-7},{"text","OM0RX K1ABC -07"}});
    QCOMPARE(digi.m_ftSequence["state"].toString(),QString("R_REPORT_TX_PENDING"));
    QCOMPARE(digi.m_ftSequence["pendingMessage"].toString(),QString("K1ABC OM0RX R-12"));
  }

  void defaultsAreInertAndRuntimeStateCannotRestore() {
    DesktopRadioController radio;AgentDigiController digi(&radio);QString error;
    QVERIFY(!digi.restoreConfiguration({{"schemaVersion",1},{"armed",true}},&error));
    QVERIFY(!error.isEmpty());
    const QJsonObject snapshot=digi.snapshot("agent-1","hamlib:1",1);
    QCOMPARE(snapshot["state"].toString(),QString("SAFE"));
    const QJsonObject tx=snapshot["tx"].toObject();
    QVERIFY(tx["implemented"].toBool());QVERIFY(!tx["serverPermitted"].toBool());
    QVERIFY(!tx["locallyPermitted"].toBool());QVERIFY(!tx["hardwareAccepted"].toBool());
  }

  void passiveSnapshotDoesNotStartAnotherHardwareReadback() {
    DesktopRadioController radio;
    AgentDigiController digi(&radio);
    int readbacks = 0;
    digi.setSafetyHooksForTest(
        [](bool) { return false; },
        [&] {
          ++readbacks;
          return std::optional<bool>(false);
        });
    (void)digi.snapshot("agent-1", "hamlib:1", 1);
    QCOMPARE(readbacks, 0);
  }

  void idleCloudDisconnectDoesNotQuarantineReceiveOnlyRadio() {
    DesktopRadioController radio;
    radio.setHamlibSnapshotForTest(14'280'580, "CW");
    AgentDigiController digi(&radio);
    QCOMPARE(digi.stop("cloud transport disconnected", false),
             AgentDigiController::StopOutcome::RxVerified);
    QVERIFY(radio.state().startsWith("Connected"));
  }

  void idleDigiStopUsesCurrentRxReadbackWithoutQuarantine() {
    DesktopRadioController radio;
    radio.setHamlibSnapshotForTest(14'280'580, "CW", false);
    AgentDigiController digi(&radio);
    const QJsonObject stopped = run(digi, "digi.stop");
    QVERIFY(stopped.value("ok").toBool());
    QVERIFY(radio.state().startsWith("Connected"));
  }

  void idleLeaseExpiryDoesNotQuarantineReceiveOnlyRadio() {
    DesktopRadioController radio;
    radio.setHamlibSnapshotForTest(14'280'580, "CW");
    AgentDigiController digi(&radio);
    QVERIFY(run(digi, "digi.control.acquire")["ok"].toBool());
    digi.m_leaseExpiryMono = digi.m_monotonic.elapsed();
    digi.expireLease();
    QVERIFY(radio.state().startsWith("Connected"));
    QCOMPARE(digi.m_state, AgentDigiController::State::Safe);
  }

  void leasePrepareAndThreeTxGatesFailClosed() {
    DesktopRadioController radio;AgentDigiController digi(&radio);QString error;
    QVERIFY(digi.restoreConfiguration({{"schemaVersion",1},{"audioProfile",QVariantMap{{"id","fixture-audio"},{"inputDeviceId","memory-in"},{"outputDeviceId","memory-out"},{"sampleRate",48000},{"inputChannel",0},{"outputChannel",0}}},{"localTxPermitted",false},{"hardwareAccepted",false},{"acceptedRadioIdentity",QString{}}},&error));
    QVERIFY(run(digi,"digi.control.acquire")["ok"].toBool());
    QVERIFY(run(digi,"digi.configure",{{"mode","FT4"},{"submode",QJsonValue::Null},{"rxAudioHz",1500},{"txAudioHz",1500}})["ok"].toBool());
    digi.setAudioReadyForTest();
    const QJsonObject prepared=run(digi,"digi.prepare",{{"message","CQ OM0RX JN88"},{"targetCallsign",""},{"parity",0},{"maxRepeats",1}});
    QVERIFY2(prepared["ok"].toBool(),qPrintable(prepared["code"].toString()));
    digi.setSafetyHooksForTest([](bool){return true;},[]{return std::optional<bool>(false);});
    QCOMPARE(run(digi,"digi.arm",{{"messageRevision",prepared["messageRevision"]},{"validWindowMillis",15000}})["code"].toString(),QString("SERVER_TX_DISABLED"));
    digi.setServerTxPermitted(true);
    QCOMPARE(run(digi,"digi.arm",{{"messageRevision",prepared["messageRevision"]},{"validWindowMillis",15000}})["code"].toString(),QString("LOCAL_TX_DISABLED"));
    digi.setLocalAcceptanceForTest(true,false);
    QCOMPARE(run(digi,"digi.arm",{{"messageRevision",prepared["messageRevision"]},{"validWindowMillis",15000}})["code"].toString(),QString("HARDWARE_ACCEPTANCE_REQUIRED"));
    digi.setLocalAcceptanceForTest(true,true);
    QCOMPARE(run(digi,"digi.arm",{{"messageRevision",prepared["messageRevision"]},{"validWindowMillis",15000}})["code"].toString(),QString("CLOCK_QUALITY_UNVERIFIED"));
    QVERIFY(run(digi,"digi.stop")["ok"].toBool());
  }

  void armRejectsManualPttAndInvalidSubmode() {
    DesktopRadioController radio;AgentDigiController digi(&radio);QString error;
    QVERIFY(digi.restoreConfiguration({{"schemaVersion",1},{"audioProfile",QVariantMap{{"id","fixture-audio"},{"inputDeviceId","memory-in"},{"outputDeviceId","memory-out"},{"sampleRate",48000},{"inputChannel",0},{"outputChannel",0}}},{"localTxPermitted",true},{"hardwareAccepted",true},{"acceptedRadioIdentity","fixture"}},&error));
    QVERIFY(run(digi,"digi.control.acquire")["ok"].toBool());
    QCOMPARE(run(digi,"digi.configure",{{"mode","FST4"},{"submode",QJsonValue::Null},{"rxAudioHz",1500},{"txAudioHz",1500}})["code"].toString(),QString("INVALID_PARAMETERS"));
    QVERIFY(run(digi,"digi.configure",{{"mode","FT8"},{"submode",QJsonValue::Null},{"rxAudioHz",1500},{"txAudioHz",1500}})["ok"].toBool());
    digi.setAudioReadyForTest();
    const auto prepared=run(digi,"digi.prepare",{{"message","CQ TEST"},{"targetCallsign",""},{"parity",0},{"maxRepeats",1}});
    digi.setServerTxPermitted(true);digi.setLocalAcceptanceForTest(true,true);digi.setSafetyHooksForTest([](bool){return true;},[]{return std::optional<bool>(true);});
    QCOMPARE(run(digi,"digi.arm",{{"messageRevision",prepared["messageRevision"]},{"validWindowMillis",15000}})["code"].toString(),QString("PTT_NOT_VERIFIED_OFF"));
  }

  void failedPttReleaseRemainsOwnedAndRetriesBeforeRadioClose() {
    DesktopRadioController radio;AgentDigiController digi(&radio);int offAttempts=0;
    digi.setSafetyHooksForTest([&](bool enabled){if(!enabled)++offAttempts;return enabled;},[]{return std::optional<bool>{};});
    digi.m_pttOwned=true;digi.m_pttReleaseRequired=true;digi.m_state=AgentDigiController::State::Transmitting;
    digi.stop("first");QCOMPARE(offAttempts,1);QVERIFY(digi.m_pttReleaseRequired);QCOMPARE(digi.m_state,AgentDigiController::State::RxUnconfirmed);
    digi.stop("second");QCOMPARE(offAttempts,2);QVERIFY(digi.m_pttReleaseRequired);
    radio.disconnectRadio();QVERIFY(offAttempts>=3);QVERIFY(digi.m_pttReleaseRequired);const int beforeConfirmed=offAttempts;
    digi.setSafetyHooksForTest([&](bool enabled){if(!enabled)++offAttempts;return true;},[]{return std::optional<bool>(false);});
    digi.stop("confirmed");QCOMPARE(offAttempts,beforeConfirmed+1);QVERIFY(!digi.m_pttReleaseRequired);QCOMPARE(digi.m_state,AgentDigiController::State::RxVerified);
  }

  void stopNeverClaimsRxVerifiedWhenHelperIsUnavailable() {
    DesktopRadioController radio;
    radio.setHamlibSnapshotForTest(14'074'000, "DATA");
    AgentDigiController digi(&radio);
    const QJsonObject stopped = run(digi, "digi.stop");
    QVERIFY(!stopped.value("ok").toBool());
    QCOMPARE(stopped.value("code").toString(), QString("RX_UNCONFIRMED"));
    QCOMPARE(digi.m_state, AgentDigiController::State::RxUnconfirmed);
  }

  void reentrantStopReportsInProgressUnconfirmed() {
    DesktopRadioController radio;
    AgentDigiController digi(&radio);
    digi.m_stopInProgress = true;
    QCOMPARE(digi.stop("direct reentrant"),
             AgentDigiController::StopOutcome::InProgress);
    const QJsonObject stopped = run(digi, "digi.stop");
    QVERIFY(!stopped.value("ok").toBool());
    QCOMPARE(stopped.value("code").toString(),
             QString("STOP_IN_PROGRESS_RX_UNCONFIRMED"));
    digi.m_stopInProgress = false;
  }

  void failedPostTransmitReleaseArmsAutonomousRetry() {
    DesktopRadioController radio;
    AgentDigiController digi(&radio);
    int offAttempts = 0;
    digi.setSafetyHooksForTest(
        [&](bool enabled) { if (!enabled) ++offAttempts; return enabled; },
        [] { return std::optional<bool>{}; });
    digi.m_pttOwned = true;
    digi.m_pttReleaseRequired = true;
    digi.m_state = AgentDigiController::State::Transmitting;
    digi.finishTransmit();
    QCOMPARE(digi.m_state, AgentDigiController::State::RxUnconfirmed);
    QVERIFY(digi.m_nextStopRetryMono > 0);
    QTest::qWait(2);
    digi.m_nextStopRetryMono = digi.m_monotonic.elapsed();
    digi.expireLease();
    QCOMPARE(offAttempts, 2);
    QCOMPARE(digi.m_stopRetryAttempts, 1);
  }

  void autonomousStopRetriesRecoverAndRemainBounded() {
    DesktopRadioController radio;
    AgentDigiController digi(&radio);
    int offAttempts = 0;
    digi.setSafetyHooksForTest(
        [&](bool enabled) {
          if (!enabled) ++offAttempts;
          return enabled || offAttempts >= 2;
        },
        [&] { return offAttempts >= 2 ? std::optional<bool>(false)
                                     : std::optional<bool>{}; });
    digi.m_pttReleaseRequired = true;
    digi.m_state = AgentDigiController::State::Transmitting;
    digi.stop("initial failure");
    QCOMPARE(digi.m_state, AgentDigiController::State::RxUnconfirmed);
    QTest::qWait(2);
    digi.m_nextStopRetryMono = digi.m_monotonic.elapsed();
    digi.expireLease();
    QCOMPARE(offAttempts, 2);
    QCOMPARE(digi.m_state, AgentDigiController::State::RxVerified);
    QVERIFY(!digi.m_pttReleaseRequired);

    digi.setSafetyHooksForTest(
        [&](bool enabled) { if (!enabled) ++offAttempts; return enabled; },
        [] { return std::optional<bool>{}; });
    digi.m_pttReleaseRequired = true;
    digi.m_state = AgentDigiController::State::Transmitting;
    const int before = offAttempts;
    digi.stop("bounded failure");
    QTest::qWait(2);
    for (int attempt = 0; attempt < 8; ++attempt) {
      if (digi.m_nextStopRetryMono > 0)
        digi.m_nextStopRetryMono = digi.m_monotonic.elapsed();
      digi.expireLease();
    }
    QCOMPARE(offAttempts - before, 4);
    QCOMPARE(digi.m_stopRetryAttempts, 3);
    QCOMPARE(digi.m_nextStopRetryMono, qint64(0));
    QCOMPARE(digi.m_state, AgentDigiController::State::RxUnconfirmed);
  }

#ifdef SHACKCQ_AGENT_HAMLIB_FIXTURE
  void emergencyHelperRxProofClearsDigiPttOwnership() {
    DesktopRadioController radio;
    radio.hamlibHelperForTest()->setProgramForTest(
        QStringLiteral(SHACKCQ_AGENT_HAMLIB_FIXTURE));
    radio.hamlibHelperForTest()->setTimeoutsForTest(500, 250);
    QVERIFY(radio.connectRadio(1, "fixture", 0));
    AgentDigiController digi(&radio);
    digi.m_pttOwned = true;
    digi.m_pttReleaseRequired = true;
    digi.m_state = AgentDigiController::State::Transmitting;
    digi.stop("emergency helper proof");
    QCOMPARE(digi.m_state, AgentDigiController::State::RxVerified);
    QVERIFY(!digi.m_pttOwned);
    QVERIFY(!digi.m_pttReleaseRequired);
  }


  void keyedHelperCrashCancelsDigiAndRunsEmergencyRecovery() {
    DesktopRadioController radio;
    radio.hamlibHelperForTest()->setProgramForTest(
        QStringLiteral(SHACKCQ_AGENT_HAMLIB_FIXTURE),
        {QStringLiteral("crash-after-key")});
    radio.hamlibHelperForTest()->setTimeoutsForTest(500, 250);
    QVERIFY(radio.connectRadio(1, "fixture", 0));
    AgentDigiController digi(&radio);
    digi.m_pttOwned = true;
    digi.m_pttReleaseRequired = true;
    digi.m_state = AgentDigiController::State::Transmitting;
    (void)radio.requestDigiPtt(true);
    QTRY_COMPARE_WITH_TIMEOUT(digi.m_state,
                              AgentDigiController::State::RxVerified, 1'000);
    QVERIFY(!digi.m_pttOwned);
    QVERIFY(!digi.m_pttReleaseRequired);
    QVERIFY(radio.state().contains("emergency RX verified"));
    QVERIFY(radio.hamlibHelperForTest()->quarantined());
  }
#endif

  void takeoverDiscardsPreparedWorkAndExpiredCommandsNeverRun() {
    DesktopRadioController radio;AgentDigiController digi(&radio);
    QVERIFY(run(digi,"digi.control.acquire")["ok"].toBool());
    QJsonObject takeover=frame("digi.control.acquire");takeover["browserSessionId"]="session-2";takeover["controlInstanceId"]="tab-2";
    QVERIFY(digi.processCommand(takeover,"agent-1","hamlib:1",1)["ok"].toBool());
    QJsonObject expired=takeover;expired["commandId"]="expired-1";expired["action"]="digi.stop";expired["validUntilUtc"]="2020-01-01T00:00:00.000Z";
    QCOMPARE(digi.processCommand(expired,"agent-1","hamlib:1",1)["code"].toString(),QString("COMMAND_EXPIRED"));
  }
};

QTEST_MAIN(AgentDigiControllerTests)
#include "agent_digi_controller_tests.moc"
