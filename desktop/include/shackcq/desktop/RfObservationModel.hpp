#pragma once

#include <QAbstractListModel>
#include <QPointF>
#include <QVariantList>

namespace shackcq::desktop {

class RfObservationModel final : public QAbstractListModel {
  Q_OBJECT
  Q_PROPERTY(int count READ rowCount NOTIFY countChanged)
  Q_PROPERTY(QVariantMap filters READ filters NOTIFY filtersChanged)
  Q_PROPERTY(QString filterSummary READ filterSummary NOTIFY filtersChanged)
  Q_PROPERTY(QString selectedId READ selectedId WRITE setSelectedId NOTIFY
                 selectedChanged)
public:
  enum Role {
    ObservationRole = Qt::UserRole + 1,
    IdRole,
    SourceRole,
    EvidenceRole,
    CallsignRole,
    BandRole,
    ModeRole,
    AgeRole,
    DistanceRole,
    BearingRole,
    PrecisionRole,
    FreshnessRole
  };
  explicit RfObservationModel(QObject *parent = nullptr);
  int rowCount(const QModelIndex &parent = {}) const override;
  QVariant data(const QModelIndex &index, int role) const override;
  QHash<int, QByteArray> roleNames() const override;
  QVariantMap filters() const { return m_filters; }
  QString filterSummary() const;
  QString selectedId() const { return m_selectedId; }
  void setSelectedId(const QString &id);
  QVariantList filteredObservations() const { return m_visible; }
  QVariantList renderObservations(int maximum = 4096) const;
  int storedCount() const { return m_all.size(); }
  quint64 droppedObservations() const { return m_droppedObservations; }
  Q_INVOKABLE QVariantMap selectedObservation() const;
  QVariantMap configuration() const;
  bool restoreConfiguration(const QVariantMap &value, QString *error = nullptr);

  Q_INVOKABLE bool ingest(const QVariantMap &observation);
  Q_INVOKABLE int ingestBatch(const QVariantList &observations);
  Q_INVOKABLE void setFilter(const QString &name, const QVariant &value);
  Q_INVOKABLE void resetFilters();
  Q_INVOKABLE void loadDeterministicDemo();
  static double distanceKm(QPointF fromLonLat, QPointF toLonLat);
  static double initialBearing(QPointF fromLonLat, QPointF toLonLat);
  static QVector<QVector<QPointF>> greatCircle(QPointF fromLonLat,
                                               QPointF toLonLat,
                                               bool longPath = false,
                                               int points = 96);

signals:
  void countChanged();
  void filtersChanged();
  void selectedChanged();
  void observationsChanged();

private:
  void applyFilters();
  QVector<QVariantMap> m_all;
  QVariantList m_visible;
  QVariantMap m_filters{{"source", "All"},
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
  QString m_selectedId;
  quint64 m_droppedObservations{};
  static constexpr int MaxObservations = 100'000;
};

} // namespace shackcq::desktop
