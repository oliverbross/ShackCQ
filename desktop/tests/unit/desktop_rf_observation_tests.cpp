#include "shackcq/desktop/RfMapItem.hpp"
#include "shackcq/desktop/RfObservationModel.hpp"

#include <QDateTime>
#include <QPainter>
#include <QtTest>

using namespace shackcq::desktop;

class DesktopRfObservationTests final : public QObject {
  Q_OBJECT
private slots:
  void greatCircleDistanceBearingAndDateline() {
    const QPointF darwin(130.8456, -12.4634), bratislava(17.1077, 48.1486);
    const double distance = RfObservationModel::distanceKm(darwin, bratislava);
    QVERIFY(distance > 12'000 && distance < 14'000);
    const double bearing =
        RfObservationModel::initialBearing(darwin, bratislava);
    QVERIFY(bearing >= 0 && bearing < 360);
    const auto dateline =
        RfObservationModel::greatCircle({170, 20}, {-170, 25});
    QVERIFY(dateline.size() >= 2);
    for (const auto &segment : dateline)
      for (int index = 1; index < segment.size(); ++index)
        QVERIFY(std::abs(segment[index].x() - segment[index - 1].x()) <= 180);
    const auto longPath =
        RfObservationModel::greatCircle(darwin, bratislava, true);
    QVERIFY(!longPath.isEmpty());
  }

  void provenanceAndFiltersStayTruthfulAndBounded() {
    RfObservationModel model;
    model.loadDeterministicDemo();
    QCOMPARE(model.rowCount(), 3);
    QVariantMap historical;
    for (const QVariant &entry : model.filteredObservations())
      if (entry.toMap().value("evidenceClass") == "HISTORICAL")
        historical = entry.toMap();
    QVERIFY(!historical.isEmpty());
    QVERIFY(!historical.contains("snrReported"));
    QCOMPARE(historical.value("endpointPrecision").toString(), QString("GRID"));
    model.setFilter("evidence", "LIVE");
    QCOMPARE(model.rowCount(), 1);
    QCOMPARE(model.filteredObservations()
                 .first()
                 .toMap()
                 .value("endpointPrecision")
                 .toString(),
             QString("COARSE"));
    model.setFilter("band", "40m");
    QCOMPARE(model.rowCount(), 0);
    model.resetFilters();
    QCOMPARE(model.rowCount(), 3);
    model.setFilter("confirmed", "Confirmed");
    QCOMPARE(model.rowCount(), 1);
    QCOMPARE(model.filteredObservations()
                 .first()
                 .toMap()
                 .value("evidenceClass")
                 .toString(),
             QString("HISTORICAL"));
    model.resetFilters();
    model.setFilter("neededDxcc", "Needed DXCC");
    QCOMPARE(model.rowCount(), 1);
    QCOMPARE(model.filteredObservations()
                 .first()
                 .toMap()
                 .value("evidenceClass")
                 .toString(),
             QString("LIVE"));
    model.resetFilters();
    QVERIFY(!model.ingest(
        {{"id", "bad"}, {"source", "fixture"}, {"evidenceClass", "INVENTED"}}));
    QString error;
    QVERIFY(!model.restoreConfiguration({{"schemaVersion", 2}}, &error));
    QVERIFY(error.contains("newer"));
  }

  void flatAndGlobeRenderRotateAndZoomDeterministically() {
    RfObservationModel model;
    model.loadDeterministicDemo();
    RfMapItem item;
    item.setWidth(900);
    item.setHeight(520);
    item.setModel(&model);
    auto render = [&item] {
      QImage image(900, 520, QImage::Format_ARGB32_Premultiplied);
      image.fill(Qt::transparent);
      QPainter painter(&image);
      item.paint(&painter);
      return image;
    };
    item.setProjection("Flat");
    const QImage flat = render();
    QVERIFY(!flat.isNull());
    item.setProjection("Globe");
    const QImage globe = render();
    QVERIFY(globe != flat);
    model.setSelectedId("demo-live-ja");
    QCOMPARE(model.selectedObservation().value("id").toString(),
             QString("demo-live-ja"));
    item.setLongitude(72);
    item.setLatitude(-18);
    item.setZoom(1.35);
    const QImage rotated = render();
    QVERIFY(rotated != globe);
  }
};

QTEST_MAIN(DesktopRfObservationTests)
#include "desktop_rf_observation_tests.moc"
