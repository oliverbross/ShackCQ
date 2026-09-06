#include "shackcq/desktop/RfMapItem.hpp"
#include "shackcq/desktop/RfObservationModel.hpp"

#include <QDateTime>
#include <QLinearGradient>
#include <QPainter>
#include <QPainterPath>
#include <QRadialGradient>
#include <QtMath>
#include <algorithm>

namespace shackcq::desktop {
namespace {
const QVector<QVector<QPointF>> Land{
    {{-168, 66},
     {-150, 61},
     {-124, 49},
     {-117, 33},
     {-97, 22},
     {-83, 11},
     {-77, 9},
     {-66, 18},
     {-81, 25},
     {-75, 40},
     {-64, 45},
     {-60, 50},
     {-69, 61},
     {-85, 70},
     {-108, 72},
     {-136, 69},
     {-168, 66}},
    {{-81, 12},
     {-77, 4},
     {-81, -6},
     {-76, -15},
     {-70, -20},
     {-75, -42},
     {-68, -55},
     {-58, -52},
     {-54, -34},
     {-40, -22},
     {-35, -7},
     {-50, 0},
     {-61, 8},
     {-81, 12}},
    {{-17, 36}, {-5, 36},  {10, 44},  {25, 40},  {40, 45},  {60, 55},
     {90, 72},  {130, 70}, {160, 60}, {145, 45}, {130, 35}, {120, 23},
     {105, 8},  {95, 16},  {80, 8},   {72, 22},  {55, 26},  {42, 12},
     {34, 30},  {25, 32},  {13, 46},  {-10, 44}, {-17, 36}},
    {{-17, 36},
     {-9, 5},
     {10, 3},
     {13, -11},
     {18, -34},
     {33, -35},
     {40, -15},
     {51, 12},
     {43, 12},
     {34, 30},
     {-17, 36}},
    {{113, -22},
     {115, -34},
     {138, -38},
     {153, -28},
     {145, -14},
     {132, -11},
     {113, -22}},
    {{-54, 60},
     {-44, 60},
     {-22, 70},
     {-28, 83},
     {-61, 82},
     {-73, 77},
     {-54, 60}},
    {{-180, -68},
     {-140, -73},
     {-100, -75},
     {-60, -72},
     {-20, -78},
     {30, -70},
     {80, -68},
     {130, -66},
     {180, -72}}};
QColor pathColour(const QString &e) {
  return e == "LIVE"      ? QColor("#4ec47b")
         : e == "OUTLOOK" ? QColor("#5ca6c8")
                          : QColor("#d38b22");
}
} // namespace

RfMapItem::RfMapItem(QQuickItem *p) : QQuickPaintedItem(p) {
  setAntialiasing(true);
  setRenderTarget(QQuickPaintedItem::FramebufferObject);
}
QObject *RfMapItem::model() const { return m_model; }
void RfMapItem::setModel(QObject *v) {
  auto *m = qobject_cast<RfObservationModel *>(v);
  if (m == m_model)
    return;
  if (m_connection)
    disconnect(m_connection);
  m_model = m;
  if (m_model)
    m_connection = connect(m_model, &RfObservationModel::observationsChanged,
                           this, [this] { update(); });
  emit modelChanged();
  update();
}
void RfMapItem::setProjection(const QString &v) {
  if (!QStringList{"Flat", "Globe"}.contains(v) || m_projection == v)
    return;
  m_projection = v;
  emit viewChanged();
  update();
}
void RfMapItem::setZoom(double v) {
  v = std::clamp(v, 1.0, 8.0);
  if (qFuzzyCompare(v, m_zoom))
    return;
  m_zoom = v;
  emit viewChanged();
  update();
}
void RfMapItem::setLongitude(double v) {
  while (v > 180)
    v -= 360;
  while (v < -180)
    v += 360;
  if (qFuzzyCompare(v, m_longitude))
    return;
  m_longitude = v;
  emit viewChanged();
  update();
}
void RfMapItem::setLatitude(double v) {
  v = std::clamp(v, -80.0, 80.0);
  if (qFuzzyCompare(v, m_latitude))
    return;
  m_latitude = v;
  emit viewChanged();
  update();
}
QPointF RfMapItem::project(double lon, double lat, bool *visible) const {
  if (m_projection == "Flat") {
    double dl = lon - m_longitude;
    while (dl > 180)
      dl -= 360;
    while (dl < -180)
      dl += 360;
    if (visible)
      *visible = true;
    return {width() / 2 + dl / 360 * width() * m_zoom,
            height() / 2 - (lat - m_latitude) / 180 * height() * m_zoom};
  }
  const double la = qDegreesToRadians(lat),
               lo = qDegreesToRadians(lon - m_longitude),
               cl = qDegreesToRadians(m_latitude);
  const double x = std::cos(la) * std::sin(lo),
               y = std::sin(la) * std::cos(cl) -
                   std::cos(la) * std::cos(lo) * std::sin(cl),
               z = std::sin(la) * std::sin(cl) +
                   std::cos(la) * std::cos(lo) * std::cos(cl);
  if (visible)
    *visible = z >= 0;
  const double r = std::min(width(), height()) * .46 * m_zoom;
  return {width() / 2 + x * r, height() / 2 - y * r};
}
void RfMapItem::paint(QPainter *p) {
  p->setRenderHint(QPainter::Antialiasing);
  p->fillRect(boundingRect(), QColor("#0d1114"));
  if (m_projection == "Globe") {
    const double r = std::min(width(), height()) * .46 * m_zoom;
    QRectF sphere(width() / 2 - r, height() / 2 - r, 2 * r, 2 * r);
    QRadialGradient g(sphere.center() - QPointF(r * .22, r * .22), r * 1.2);
    g.setColorAt(0, QColor("#263942"));
    g.setColorAt(1, QColor("#080b0d"));
    p->setBrush(g);
    p->setPen(QPen(QColor("#5b6970"), 1));
    p->drawEllipse(sphere);
    QPainterPath clip;
    clip.addEllipse(sphere);
    p->setClipPath(clip);
  } else {
    p->setPen(QPen(QColor("#253038"), 1));
    for (int lon = -180; lon <= 180; lon += 30)
      p->drawLine(project(lon, -90), project(lon, 90));
    for (int lat = -60; lat <= 60; lat += 30)
      p->drawLine(project(-180, lat), project(180, lat));
  }
  p->setBrush(QColor("#24332e"));
  p->setPen(QPen(QColor("#6f8178"), 1));
  for (const auto &ring : Land) {
    QPainterPath path;
    bool started = false;
    for (const auto &ll : ring) {
      bool vis{};
      const QPointF point = project(ll.x(), ll.y(), &vis);
      if (!vis) {
        started = false;
        continue;
      }
      if (!started) {
        path.moveTo(point);
        started = true;
      } else
        path.lineTo(point);
    }
    p->drawPath(path);
  }
  // UTC solar direction and truthful grayline shading (no forecast heat).
  const QDateTime now = QDateTime::currentDateTimeUtc();
  const double day = now.date().dayOfYear();
  const double decl =
      23.44 * std::sin(qDegreesToRadians(360.0 * (284 + day) / 365.0));
  const double subLon =
      180.0 - 15.0 * (now.time().hour() + now.time().minute() / 60.0);
  if (m_projection == "Globe") {
    const QPointF centre(width() / 2, height() / 2),
        sun = project(subLon, decl);
    const QPointF direction = sun - centre;
    QLinearGradient night(centre + direction, centre - direction);
    night.setColorAt(0, QColor(0, 0, 20, 0));
    night.setColorAt(.48, QColor(0, 0, 20, 25));
    night.setColorAt(1, QColor(0, 0, 20, 155));
    p->fillRect(boundingRect(), night);
  } else {
    p->setPen(Qt::NoPen);
    p->setBrush(QColor(0, 0, 25, 78));
    for (int lon = -180; lon < 180; lon += 8)
      for (int lat = -88; lat < 88; lat += 8) {
        const double cosine = std::sin(qDegreesToRadians(lat + 4)) *
                                  std::sin(qDegreesToRadians(decl)) +
                              std::cos(qDegreesToRadians(lat + 4)) *
                                  std::cos(qDegreesToRadians(decl)) *
                                  std::cos(qDegreesToRadians(lon + 4 - subLon));
        if (cosine < 0) {
          QPolygonF cell{project(lon, lat), project(lon + 8, lat),
                         project(lon + 8, lat + 8), project(lon, lat + 8)};
          p->drawPolygon(cell);
        }
      }
  }
  QPainterPath terminator;
  for (int lon = -180; lon <= 180; lon += 4) {
    const double lat = qRadiansToDegrees(
        std::atan(-std::cos(qDegreesToRadians(lon - subLon)) /
                  std::tan(qDegreesToRadians(decl == 0 ? .01 : decl))));
    const QPointF pt = project(lon, std::clamp(lat, -89.0, 89.0));
    lon == -180 ? terminator.moveTo(pt) : terminator.lineTo(pt);
  }
  p->setPen(QPen(QColor("#e3c765"), 1, Qt::DashLine));
  p->drawPath(terminator);
  if (!m_model)
    return;
  for (const QVariant &entry : m_model->renderObservations()) {
    const QVariantMap o = entry.toMap();
    const QPointF from(o.value("rxLon").toDouble(),
                       o.value("rxLat").toDouble()),
        to(o.value("txLon").toDouble(), o.value("txLat").toDouble());
    const QColor colour = pathColour(o.value("evidenceClass").toString());
    p->setPen(QPen(colour, 1));
    for (const auto &segment : RfObservationModel::greatCircle(
             from, to, m_model->filters().value("longPath").toBool())) {
      QPainterPath path;
      bool started = false;
      for (const auto &ll : segment) {
        bool vis{};
        QPointF point = project(ll.x(), ll.y(), &vis);
        if (!vis) {
          started = false;
          continue;
        }
        if (!started) {
          path.moveTo(point);
          started = true;
        } else
          path.lineTo(point);
      }
      p->drawPath(path);
    }
    bool visible{};
    QPointF marker = project(to.x(), to.y(), &visible);
    if (visible) {
      p->setBrush(o.value("endpointPrecision") == "COARSE" ? Qt::NoBrush
                                                           : colour);
      const double radius = o.value("id") == m_model->selectedId() ? 8 : 5;
      p->drawEllipse(marker, radius, radius);
    }
    for (const QVariant &control : o.value("controlPoints").toList()) {
      const QVariantMap cp = control.toMap();
      QPointF point = project(cp.value("lon").toDouble(),
                              cp.value("lat").toDouble(), &visible);
      if (visible) {
        QColor heat = colour;
        heat.setAlpha(o.value("evidenceClass") == "LIVE" ? 42 : 24);
        p->setBrush(heat);
        p->setPen(Qt::NoPen);
        p->drawEllipse(point, 11, 11);
        p->setBrush(colour.lighter(135));
        p->drawEllipse(point, 2.5, 2.5);
      }
    }
  }
  p->setClipping(false);
  bool stationVisible{};
  QPointF station = project(17.11, 48.17, &stationVisible);
  if (stationVisible) {
    p->setBrush(QColor("#f2efe7"));
    p->setPen(Qt::NoPen);
    p->drawEllipse(station, 5, 5);
  }
}
} // namespace shackcq::desktop
