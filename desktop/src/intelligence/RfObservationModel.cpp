#include "shackcq/desktop/RfObservationModel.hpp"

#include <QDateTime>
#include <QVector3D>
#include <QtMath>
#include <algorithm>
#include <cmath>
#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

namespace shackcq::desktop {
namespace {
constexpr double EarthKm = 6371.0088;
double radians(double v) { return qDegreesToRadians(v); }
bool normalizeObservation(const QVariantMap &input, QVariantMap *output,
                          qint64 now) {
  QVariantMap o = input;
  const QString id = o.value("id").toString(),
                source = o.value("source").toString(),
                evidence = o.value("evidenceClass").toString();
  const double txLat = o.value("txLat").toDouble(),
               txLon = o.value("txLon").toDouble(),
               rxLat = o.value("rxLat").toDouble(),
               rxLon = o.value("rxLon").toDouble();
  if (id.isEmpty() || source.isEmpty() ||
      !QStringList{"LIVE", "HISTORICAL", "OUTLOOK"}.contains(evidence) ||
      std::abs(txLat) > 90 || std::abs(rxLat) > 90 || std::abs(txLon) > 180 ||
      std::abs(rxLon) > 180)
    return false;
  if (!o.value("snrReported").isValid())
    o.remove("snrReported");
  const double distance =
      RfObservationModel::distanceKm({txLon, txLat}, {rxLon, rxLat});
  o["distanceKm"] = distance;
  o["bearingDeg"] =
      RfObservationModel::initialBearing({rxLon, rxLat}, {txLon, txLat});
  o["ageMinutes"] =
      std::max<qint64>(0, (now - o.value("observedUtc").toLongLong()) / 60);
  o["freshness"] = o.value("ageMinutes").toInt() <= 30 ? "FRESH" : "STALE";
  QVariantList controls;
  if (distance >= 1000) {
    const int hops = std::clamp(int(std::ceil(distance / 3500.0)), 1, 5);
    QVector<QPointF> path;
    for (const auto &segment : RfObservationModel::greatCircle(
             {rxLon, rxLat}, {txLon, txLat}, false, std::max(8, hops * 2)))
      path += segment;
    for (int hop = 0; hop < hops; ++hop) {
      const QPointF point = path.at(std::clamp(
          int((hop + .5) * path.size() / hops), 0, int(path.size()) - 1));
      controls.push_back(QVariantMap{
          {"lon", point.x()},
          {"lat", point.y()},
          {"label", evidence == "OUTLOOK" ? "Speculative outlook control point"
                                          : "Observed-path control point"},
          {"mufClaim", false}});
    }
  }
  o["controlPoints"] = controls;
  *output = std::move(o);
  return true;
}
} // namespace

RfObservationModel::RfObservationModel(QObject *parent)
    : QAbstractListModel(parent) {}
int RfObservationModel::rowCount(const QModelIndex &p) const {
  return p.isValid() ? 0 : m_visible.size();
}
QVariant RfObservationModel::data(const QModelIndex &i, int role) const {
  if (!i.isValid() || i.row() < 0 || i.row() >= m_visible.size())
    return {};
  const auto row = m_visible.at(i.row()).toMap();
  switch (role) {
  case ObservationRole:
    return row;
  case IdRole:
    return row.value("id");
  case SourceRole:
    return row.value("source");
  case EvidenceRole:
    return row.value("evidenceClass");
  case CallsignRole:
    return row.value("callsign");
  case BandRole:
    return row.value("band");
  case ModeRole:
    return row.value("mode");
  case AgeRole:
    return row.value("ageMinutes");
  case DistanceRole:
    return row.value("distanceKm");
  case BearingRole:
    return row.value("bearingDeg");
  case PrecisionRole:
    return row.value("endpointPrecision");
  case FreshnessRole:
    return row.value("freshness");
  default:
    return {};
  }
}
QHash<int, QByteArray> RfObservationModel::roleNames() const {
  return {{ObservationRole, "observation"},
          {IdRole, "observationId"},
          {SourceRole, "source"},
          {EvidenceRole, "evidenceClass"},
          {CallsignRole, "callsign"},
          {BandRole, "band"},
          {ModeRole, "mode"},
          {AgeRole, "ageMinutes"},
          {DistanceRole, "distanceKm"},
          {BearingRole, "bearingDeg"},
          {PrecisionRole, "endpointPrecision"},
          {FreshnessRole, "freshness"}};
}

double RfObservationModel::distanceKm(QPointF a, QPointF b) {
  const double p1 = radians(a.y()), p2 = radians(b.y()), dp = p2 - p1,
               dl = radians(b.x() - a.x());
  const double h =
      std::sin(dp / 2) * std::sin(dp / 2) +
      std::cos(p1) * std::cos(p2) * std::sin(dl / 2) * std::sin(dl / 2);
  return EarthKm * 2 *
         std::atan2(std::sqrt(h), std::sqrt(std::max(0.0, 1 - h)));
}
double RfObservationModel::initialBearing(QPointF a, QPointF b) {
  const double p1 = radians(a.y()), p2 = radians(b.y()),
               dl = radians(b.x() - a.x());
  return std::fmod(qRadiansToDegrees(std::atan2(
                       std::sin(dl) * std::cos(p2),
                       std::cos(p1) * std::sin(p2) -
                           std::sin(p1) * std::cos(p2) * std::cos(dl))) +
                       360.0,
                   360.0);
}
QVector<QVector<QPointF>> RfObservationModel::greatCircle(QPointF a, QPointF b,
                                                          bool longPath,
                                                          int points) {
  points = std::clamp(points, 8, 256);
  auto xyz = [](QPointF p) {
    const double lat = radians(p.y()), lon = radians(p.x());
    return QVector3D(float(std::cos(lat) * std::cos(lon)),
                     float(std::cos(lat) * std::sin(lon)),
                     float(std::sin(lat)));
  };
  QVector3D va = xyz(a), vb = xyz(b);
  double omega =
      std::acos(std::clamp(double(QVector3D::dotProduct(va, vb)), -1.0, 1.0));
  if (longPath)
    omega = 2 * M_PI - omega;
  QVector<QVector<QPointF>> segments(1);
  double previous{};
  for (int i = 0; i <= points; ++i) {
    const double t = double(i) / points;
    QVector3D v;
    if (!longPath) {
      const double s = std::sin(omega);
      v = s < 1e-9 ? va
                   : va * float(std::sin((1 - t) * omega) / s) +
                         vb * float(std::sin(t * omega) / s);
    } else {
      QVector3D axis = QVector3D::crossProduct(va, vb).normalized();
      const double angle = -omega * t;
      v = va * float(std::cos(angle)) +
          QVector3D::crossProduct(axis, va) * float(std::sin(angle)) +
          axis * float(QVector3D::dotProduct(axis, va) * (1 - std::cos(angle)));
    }
    v.normalize();
    const double lon = qRadiansToDegrees(std::atan2(v.y(), v.x())),
                 lat = qRadiansToDegrees(std::asin(v.z()));
    if (i > 0 && std::abs(lon - previous) > 180)
      segments.push_back({});
    segments.last().push_back(QPointF(lon, lat));
    previous = lon;
  }
  return segments;
}

bool RfObservationModel::ingest(const QVariantMap &input) {
  QVariantMap o;
  if (!normalizeObservation(input, &o, QDateTime::currentSecsSinceEpoch()))
    return false;
  const QString id = o.value("id").toString();
  for (auto &existing : m_all)
    if (existing.value("id") == id) {
      existing = o;
      applyFilters();
      return true;
    }
  m_all.push_back(o);
  if (m_all.size() > MaxObservations) {
    m_all.removeFirst();
    ++m_droppedObservations;
  }
  applyFilters();
  return true;
}
int RfObservationModel::ingestBatch(const QVariantList &inputs) {
  QHash<QString, int> indices;
  indices.reserve(m_all.size() + inputs.size());
  for (int i = 0; i < m_all.size(); ++i)
    indices.insert(m_all.at(i).value("id").toString(), i);
  const qint64 now = QDateTime::currentSecsSinceEpoch();
  int accepted = 0;
  for (const QVariant &entry : inputs) {
    QVariantMap o;
    if (!normalizeObservation(entry.toMap(), &o, now))
      continue;
    const QString id = o.value("id").toString();
    const auto found = indices.constFind(id);
    if (found != indices.cend())
      m_all[*found] = std::move(o);
    else {
      indices.insert(id, m_all.size());
      m_all.push_back(std::move(o));
    }
    ++accepted;
  }
  if (m_all.size() > MaxObservations) {
    const int excess = m_all.size() - MaxObservations;
    m_all.remove(0, excess);
    m_droppedObservations += static_cast<quint64>(excess);
  }
  applyFilters();
  return accepted;
}
QVariantList RfObservationModel::renderObservations(int maximum) const {
  maximum = std::clamp(maximum, 1, 8192);
  if (m_visible.size() <= maximum)
    return m_visible;
  QVariantList result;
  result.reserve(maximum + 1);
  const int stride = std::max(1, static_cast<int>(m_visible.size() / maximum));
  bool selectedIncluded = false;
  for (int i = 0; i < m_visible.size() && result.size() < maximum;
       i += stride) {
    const QVariant &entry = m_visible.at(i);
    result.push_back(entry);
    selectedIncluded |= entry.toMap().value("id") == m_selectedId;
  }
  if (!m_selectedId.isEmpty() && !selectedIncluded) {
    for (const QVariant &entry : m_visible)
      if (entry.toMap().value("id") == m_selectedId) {
        result.push_back(entry);
        break;
      }
  }
  return result;
}
void RfObservationModel::applyFilters() {
  QVariantList next;
  for (const auto &o : m_all) {
    if (m_filters.value("source") != "All" &&
        o.value("source") != m_filters.value("source"))
      continue;
    if (m_filters.value("band") != "All" &&
        o.value("band") != m_filters.value("band"))
      continue;
    if (m_filters.value("mode") != "All" &&
        o.value("mode") != m_filters.value("mode"))
      continue;
    if (m_filters.value("evidence") != "All" &&
        o.value("evidenceClass") != m_filters.value("evidence"))
      continue;
    if (o.value("ageMinutes").toInt() >
        m_filters.value("maximumAgeMinutes").toInt())
      continue;
    const double d = o.value("distanceKm").toDouble();
    if (d < m_filters.value("minimumDistanceKm").toDouble() ||
        d > m_filters.value("maximumDistanceKm").toDouble())
      continue;
    if (m_filters.value("freshOnly").toBool() &&
        o.value("freshness") != "FRESH")
      continue;
    if (!o.value("callsign")
             .toString()
             .contains(m_filters.value("callsign").toString(),
                       Qt::CaseInsensitive))
      continue;
    if (m_filters.value("worked") != "All" &&
        o.value("worked").toBool() != (m_filters.value("worked") == "Worked"))
      continue;
    if (m_filters.value("confirmed") != "All" &&
        o.value("confirmed").toBool() !=
            (m_filters.value("confirmed") == "Confirmed"))
      continue;
    if (m_filters.value("neededDxcc") != "All" &&
        o.value("neededDxcc").toBool() !=
            (m_filters.value("neededDxcc") == "Needed DXCC"))
      continue;
    next.push_back(o);
  }
  beginResetModel();
  m_visible = next;
  endResetModel();
  emit countChanged();
  emit observationsChanged();
}
void RfObservationModel::setFilter(const QString &name, const QVariant &value) {
  if (!m_filters.contains(name) || m_filters.value(name) == value)
    return;
  m_filters[name] = value;
  applyFilters();
  emit filtersChanged();
}
void RfObservationModel::resetFilters() {
  m_filters = {{"source", "All"},
               {"band", "All"},
               {"mode", "All"},
               {"evidence", "All"},
               {"maximumAgeMinutes", 120},
               {"minimumDistanceKm", 0},
               {"maximumDistanceKm", 20000},
               {"callsign", QString{}},
               {"worked", "All"},
               {"confirmed", "All"},
               {"neededDxcc", "All"},
               {"freshOnly", false},
               {"longPath", false}};
  applyFilters();
  emit filtersChanged();
}
QString RfObservationModel::filterSummary() const {
  return QStringLiteral("%1 · %2 · %3 · %4 min · %5 results")
      .arg(m_filters.value("source").toString(),
           m_filters.value("band").toString(),
           m_filters.value("evidence").toString())
      .arg(m_filters.value("maximumAgeMinutes").toInt())
      .arg(m_visible.size());
}
void RfObservationModel::setSelectedId(const QString &id) {
  if (m_selectedId == id)
    return;
  m_selectedId = id;
  emit selectedChanged();
}
QVariantMap RfObservationModel::selectedObservation() const {
  for (const auto &o : m_all)
    if (o.value("id") == m_selectedId)
      return o;
  return {};
}
QVariantMap RfObservationModel::configuration() const {
  return {{"schemaVersion", 1}, {"filters", m_filters}};
}
bool RfObservationModel::restoreConfiguration(const QVariantMap &value,
                                              QString *error) {
  if (value.value("schemaVersion", 0).toInt() > 1) {
    if (error)
      *error = "RF observation filter schema is newer than this build";
    return false;
  }
  const auto restored = value.value("filters").toMap();
  for (auto it = restored.cbegin(); it != restored.cend(); ++it)
    if (m_filters.contains(it.key()))
      m_filters[it.key()] = it.value();
  applyFilters();
  return true;
}
void RfObservationModel::loadDeterministicDemo() {
  const qint64 now = QDateTime::currentSecsSinceEpoch();
  ingest({{"id", "demo-live-ja"},
          {"source", "PSK Reporter demo"},
          {"sourceRecordId", "fixture-1"},
          {"observedUtc", now - 180},
          {"evidenceClass", "LIVE"},
          {"callsign", "JA1XYZ"},
          {"txLat", 35.68},
          {"txLon", 139.65},
          {"rxLat", 48.17},
          {"rxLon", 17.11},
          {"endpointPrecision", "COARSE"},
          {"frequencyHz", 14074000ULL},
          {"band", "20m"},
          {"mode", "FT8"},
          {"snrReported", -13.0},
          {"worked", false},
          {"confirmed", false},
          {"neededDxcc", true}});
  ingest({{"id", "demo-historical-vk"},
          {"source", "Logged QSO demo"},
          {"sourceRecordId", "fixture-2"},
          {"observedUtc", now - 2400},
          {"evidenceClass", "HISTORICAL"},
          {"callsign", "VK6DEMO"},
          {"txLat", -31.95},
          {"txLon", 115.86},
          {"rxLat", 48.17},
          {"rxLon", 17.11},
          {"endpointPrecision", "GRID"},
          {"frequencyHz", 7074000ULL},
          {"band", "40m"},
          {"mode", "CW"},
          {"worked", true},
          {"confirmed", true}});
  ingest({{"id", "demo-outlook-na"},
          {"source", "Empirical Outlook demo"},
          {"sourceRecordId", "fixture-3"},
          {"observedUtc", now - 600},
          {"evidenceClass", "OUTLOOK"},
          {"callsign", "OUTLOOK"},
          {"txLat", 40.0},
          {"txLon", -100.0},
          {"rxLat", 48.17},
          {"rxLon", 17.11},
          {"endpointPrecision", "COARSE"},
          {"frequencyHz", 21074000ULL},
          {"band", "15m"},
          {"mode", "FT8"},
          {"worked", false},
          {"confirmed", false}});
}

} // namespace shackcq::desktop
