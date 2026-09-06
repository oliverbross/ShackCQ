#include "shackcq/desktop/DesktopModels.hpp"
#include "shackcq/desktop/DesktopPanadapter.hpp"
#include "shackcq/desktop/DesktopParityPlatform.hpp"
#include "shackcq/desktop/RfMapItem.hpp"
#include "shackcq/desktop/RfObservationModel.hpp"

#include <QElapsedTimer>
#include <QFile>
#include <QJsonDocument>
#include <QPainter>
#include <QSaveFile>
#include <QTemporaryDir>
#include <QtTest>

#ifdef Q_OS_MACOS
#include <mach/mach.h>
#endif

using namespace shackcq::desktop;

class DesktopScaleSoakTests final : public QObject {
  Q_OBJECT
private slots:
  void domainScaleMatrix();
  void spotCapacityAndLifecycleChurn();
  void panadapterFloatIqBoundedStress();
  void rfObservationHundredThousandFilterAndAggregation();
  void continuousReplay();
};

namespace {
quint64 residentBytes() {
#ifdef Q_OS_MACOS
  mach_task_basic_info_data_t info{};
  mach_msg_type_number_t count = MACH_TASK_BASIC_INFO_COUNT;
  return task_info(mach_task_self(), MACH_TASK_BASIC_INFO,
                   reinterpret_cast<task_info_t>(&info), &count) == KERN_SUCCESS
             ? info.resident_size
             : 0;
#else
  return 0;
#endif
}
} // namespace

void DesktopScaleSoakTests::domainScaleMatrix() {
  QTemporaryDir root;
  QVERIFY(root.isValid());
  DesktopParityPlatform platform;
  QString error;
  QVERIFY2(
      platform.open(root.path() + "/db", root.path() + "/cache", false, &error),
      qPrintable(error));
  QElapsedTimer total;
  total.start();
  const QVariantMap result = platform.runDeterministicScaleProbe(&error);
  QVERIFY2(!result.isEmpty(), qPrintable(error));
  QCOMPARE(result.value("Neural").toMap().value("rows").toInt(), 2880);
  QCOMPARE(result.value("Digi").toMap().value("rows").toInt(), 20000);
  QCOMPARE(result.value("Groups.io").toMap().value("rows").toInt(), 30000);
  QCOMPARE(result.value("Contest").toMap().value("rows").toInt(), 10000);
  QCOMPARE(result.value("DX Chaser").toMap().value("rows").toInt(), 20000);
  QVERIFY2(total.elapsed() < 120000,
           "Deterministic domain scale matrix exceeded two minutes");
}

void DesktopScaleSoakTests::panadapterFloatIqBoundedStress() {
  DesktopPanadapter panadapter;
  panadapter.setFftSize(1024);
  panadapter.setWaterfallRows(96);
  QVector<float> iq(2048);
  QElapsedTimer elapsed;
  elapsed.start();
  for (int frame = 0; frame < 600; ++frame) {
    for (int sample = 0; sample < 1024; ++sample) {
      const float phase =
          float(2.0 * 3.141592653589793 * (31 + frame % 17) * sample / 1024.0);
      iq[2 * sample] = std::cos(phase) * .25F;
      iq[2 * sample + 1] = std::sin(phase) * .25F;
    }
    panadapter.pushFloatIq(frame % 2 ? "tci:1" : "tci:0", 96'000, iq,
                           frame % 2 ? 7'074'000U : 14'074'000U,
                           frame % 199 == 0);
    if (frame % 4 == 3)
      QVERIFY(panadapter.waitForIdleForTest());
  }
  QVERIFY(panadapter.waitForIdleForTest());
  QVERIFY(panadapter.health().value("fftExecutedOffOwnerThread").toBool());
  QCOMPARE(panadapter.receiverIds().size(), 2);
  for (const QString &id : panadapter.receiverIds()) {
    const auto rendered = panadapter.renderFrame(id);
    QCOMPARE(rendered.trace.size(), 1024);
    QCOMPARE(rendered.waterfall.size(), QSize(1024, 96));
    QVERIFY(rendered.waterfall.sizeInBytes() <= 1024U * 96U * 4U);
    QVERIFY(rendered.sequence > 100);
  }
  panadapter.setFftSize(8192);
  panadapter.setWorkerDelayForTest(5);
  QVector<float> overloadIq(16'384);
  for (int sample = 0; sample < 8192; ++sample) {
    const float phase =
        float(2.0 * 3.141592653589793 * 233.0 * sample / 8192.0);
    overloadIq[2 * sample] = std::cos(phase) * .2F;
    overloadIq[2 * sample + 1] = std::sin(phase) * .2F;
  }
  for (int frame = 0; frame < 200; ++frame)
    panadapter.pushFloatIq(frame % 2 ? "tci:1" : "tci:0",
                           frame % 2 ? 192'000 : 48'000, overloadIq,
                           frame % 2 ? 7'074'000U : 14'074'000U, false);
  QVERIFY(panadapter.waitForIdleForTest(30'000));
  panadapter.setWorkerDelayForTest(0);
  quint64 dropped = 0;
  for (const QVariant &entry : panadapter.health().value("contexts").toList())
    dropped += entry.toMap().value("droppedFrames").toULongLong();
  QVERIFY2(dropped > 0,
           "Bounded FFT worker overload did not account for dropped frames");
  QCOMPARE(panadapter.renderFrame("tci:0").trace.size(), 8192);
  QCOMPARE(panadapter.renderFrame("tci:1").trace.size(), 8192);
  qInfo().noquote()
      << QStringLiteral("PAN_SCALE elapsedMs=%1 dropped=%2 queueCapacity=%3 "
                        "fft=8192 rates=48000,96000,192000")
             .arg(elapsed.elapsed())
             .arg(dropped)
             .arg(panadapter.health().value("workerQueueCapacity").toInt());
  QVERIFY2(elapsed.elapsed() < 30'000,
           "Bounded dual-receiver float32 stress exceeded 30 seconds");
}

void DesktopScaleSoakTests::rfObservationHundredThousandFilterAndAggregation() {
  RfObservationModel model;
  QVariantList batch;
  batch.reserve(100'000);
  const qint64 now = QDateTime::currentSecsSinceEpoch();
  for (int index = 0; index < 100'000; ++index) {
    const double offset = (index % 601 - 300) / 100.0;
    batch.push_back(
        QVariantMap{{"id", QStringLiteral("scale-%1").arg(index)},
                    {"source", index % 2 ? "SCALE-A" : "SCALE-B"},
                    {"sourceRecordId", index},
                    {"observedUtc", now - index % 3600},
                    {"evidenceClass", "LIVE"},
                    {"callsign", QStringLiteral("T%1RX").arg(index)},
                    {"txLat", 48.0 + offset},
                    {"txLon", 17.0 - offset},
                    {"rxLat", 48.0},
                    {"rxLon", 17.0},
                    {"endpointPrecision", "COARSE"},
                    {"frequencyHz", 14'074'000ULL},
                    {"band", "20m"},
                    {"mode", "FT8"},
                    {"worked", index % 3 == 0}});
  }
  QElapsedTimer ingest;
  ingest.start();
  QCOMPARE(model.ingestBatch(batch), 100'000);
  const qint64 ingestMs = ingest.elapsed();
  QCOMPARE(model.storedCount(), 100'000);
  QVERIFY2(ingest.elapsed() < 30'000,
           "100k RF observation batch ingest exceeded 30 seconds");
  QElapsedTimer filter;
  filter.start();
  model.setFilter("source", "SCALE-A");
  const qint64 filterMs = filter.elapsed();
  QCOMPARE(model.rowCount(), 50'000);
  QVERIFY2(filter.elapsed() < 5'000,
           "100k RF observation filter exceeded five seconds");
  QVERIFY(model.renderObservations().size() <= 4097);
  RfMapItem item;
  item.setWidth(900);
  item.setHeight(520);
  item.setModel(&model);
  QImage image(900, 520, QImage::Format_ARGB32_Premultiplied);
  image.fill(Qt::transparent);
  QElapsedTimer render;
  render.start();
  QPainter painter(&image);
  item.paint(&painter);
  painter.end();
  const qint64 renderMs = render.elapsed();
  QVERIFY2(render.elapsed() < 10'000,
           "Aggregated RF map render exceeded ten seconds");
  QVERIFY(!image.isNull());
  qInfo().noquote() << QStringLiteral(
                           "RF_SCALE observations=100000 visible=50000 "
                           "rendered=%1 ingestMs=%2 filterMs=%3 mapRenderMs=%4")
                           .arg(model.renderObservations().size())
                           .arg(ingestMs)
                           .arg(filterMs)
                           .arg(renderMs);
  QVERIFY(model.ingest({{"id", "capacity-extra"},
                        {"source", "SCALE-A"},
                        {"observedUtc", now},
                        {"evidenceClass", "LIVE"},
                        {"callsign", "EXTRA"},
                        {"txLat", 48.1},
                        {"txLon", 17.1},
                        {"rxLat", 48.0},
                        {"rxLon", 17.0},
                        {"endpointPrecision", "COARSE"},
                        {"band", "20m"},
                        {"mode", "FT8"}}));
  QCOMPARE(model.storedCount(), 100'000);
  QCOMPARE(model.droppedObservations(), 1ULL);
}

void DesktopScaleSoakTests::continuousReplay() {
  bool valid = false;
  int seconds = qEnvironmentVariableIntValue("SHACKCQ_REPLAY_SECONDS", &valid);
  if (!valid)
    seconds = 2;
  seconds = std::clamp(seconds, 1, 3600);
  DesktopPanadapter panadapter;
  panadapter.setFftSize(4096);
  panadapter.setWaterfallRows(128);
  QVector<float> iq(16'384);
  QElapsedTimer elapsed;
  elapsed.start();
  quint64 submitted = 0, warmRss = 0;
  bool enlarged = false;
  while (elapsed.elapsed() < qint64(seconds) * 1000) {
    const qint64 second = elapsed.elapsed() / 1000;
    if (!enlarged && second >= seconds / 2 && seconds >= 10) {
      panadapter.setFftSize(8192);
      enlarged = true;
    }
    const int fft = enlarged ? 8192 : 4096;
    const quint32 rates[]{48'000, 96'000, 192'000};
    const quint32 rate = rates[(second / 60) % 3];
    for (int sample = 0; sample < fft; ++sample) {
      const float phase = float(2.0 * 3.141592653589793 *
                                (97 + (submitted % 29)) * sample / fft);
      iq[2 * sample] = std::cos(phase) * .24F;
      iq[2 * sample + 1] = std::sin(phase) * .24F;
    }
    const QVector<float> frame = iq.first(fft * 2);
    panadapter.pushFloatIq("tci:0", rate, frame, 14'074'000U,
                           submitted % 997 == 0);
    panadapter.pushFloatIq("tci:1", rate, frame, 7'074'000U,
                           submitted % 991 == 0);
    submitted += 2;
    QVERIFY(panadapter.waitForIdleForTest(10'000));
    if (warmRss == 0 && elapsed.elapsed() >= qint64(seconds) * 1000 / 3)
      warmRss = residentBytes();
    QTest::qWait(45);
  }
  QVERIFY(panadapter.waitForIdleForTest(30'000));
  const quint64 finalRss = residentBytes();
  const QVariantMap health = panadapter.health();
  QVERIFY(health.value("fftExecutedOffOwnerThread").toBool());
  quint64 dropped = 0, frames = 0;
  for (const QVariant &entry : health.value("contexts").toList()) {
    const auto source = entry.toMap();
    dropped += source.value("droppedFrames").toULongLong();
    frames += source.value("observedFrameSequence").toULongLong();
  }
  if (warmRss > 0 && finalRss > 0)
    QVERIFY2(finalRss <= warmRss + 64ULL * 1024ULL * 1024ULL,
             "Continuous replay RSS grew beyond the bounded warm-up allowance");
  const QVariantMap report{
      {"requestedSeconds", seconds},
      {"elapsedMs", elapsed.elapsed()},
      {"submittedFrames", QVariant::fromValue<qulonglong>(submitted)},
      {"processedFrames", QVariant::fromValue<qulonglong>(frames)},
      {"droppedFrames", QVariant::fromValue<qulonglong>(dropped)},
      {"warmRssBytes", QVariant::fromValue<qulonglong>(warmRss)},
      {"finalRssBytes", QVariant::fromValue<qulonglong>(finalRss)},
      {"fftSizes", seconds >= 10 ? "4096,8192" : "4096"},
      {"sampleRates", "48000,96000,192000"},
      {"workerQueueCapacity", health.value("workerQueueCapacity")},
      {"fftOffOwnerThread", health.value("fftExecutedOffOwnerThread")}};
  const QString reportPath = qEnvironmentVariable("SHACKCQ_REPLAY_REPORT");
  if (!reportPath.isEmpty()) {
    QSaveFile file(reportPath);
    QVERIFY(file.open(QIODevice::WriteOnly));
    file.write(
        QJsonDocument::fromVariant(report).toJson(QJsonDocument::Indented));
    QVERIFY(file.commit());
  }
  qInfo().noquote() << "CONTINUOUS_REPLAY"
                    << QString::fromUtf8(
                           QJsonDocument::fromVariant(report).toJson(
                               QJsonDocument::Compact));
}

void DesktopScaleSoakTests::spotCapacityAndLifecycleChurn() {
  SpotRepository spots;
  const qint64 now = QDateTime::currentSecsSinceEpoch();
  for (int index = 0; index < 20001; ++index) {
    spots.ingest({quint64(14000000 + index), QStringLiteral("T%1DX").arg(index),
                  "SCALE", "bounded spot", "20m", "FT8", "SCALE", now - index,
                  false, false, false});
  }
  QCOMPARE(spots.rowCount(), 20000);

  QTemporaryDir root;
  QVERIFY(root.isValid());
  for (int cycle = 0; cycle < 25; ++cycle) {
    DesktopParityPlatform platform;
    QString error;
    QVERIFY2(platform.open(root.path() + "/db", root.path() + "/cache",
                           cycle % 2, &error),
             qPrintable(error));
    QCOMPARE(platform.databaseHealth().size(), 5);
    platform.globalStop();
    platform.close();
  }
}

QTEST_GUILESS_MAIN(DesktopScaleSoakTests)
#include "desktop_scale_soak_tests.moc"
