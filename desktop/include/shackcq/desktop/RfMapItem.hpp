#pragma once

#include <QQuickPaintedItem>

namespace shackcq::desktop {
class RfObservationModel;

class RfMapItem : public QQuickPaintedItem {
  Q_OBJECT
  Q_PROPERTY(QObject *model READ model WRITE setModel NOTIFY modelChanged)
  Q_PROPERTY(
      QString projection READ projection WRITE setProjection NOTIFY viewChanged)
  Q_PROPERTY(double zoom READ zoom WRITE setZoom NOTIFY viewChanged)
  Q_PROPERTY(
      double longitude READ longitude WRITE setLongitude NOTIFY viewChanged)
  Q_PROPERTY(double latitude READ latitude WRITE setLatitude NOTIFY viewChanged)
public:
  explicit RfMapItem(QQuickItem *parent = nullptr);
  QObject *model() const;
  void setModel(QObject *model);
  QString projection() const { return m_projection; }
  void setProjection(const QString &projection);
  double zoom() const { return m_zoom; }
  void setZoom(double value);
  double longitude() const { return m_longitude; }
  void setLongitude(double value);
  double latitude() const { return m_latitude; }
  void setLatitude(double value);
  void paint(QPainter *painter) override;
signals:
  void modelChanged();
  void viewChanged();

private:
  QPointF project(double lon, double lat, bool *visible = nullptr) const;
  RfObservationModel *m_model{};
  QMetaObject::Connection m_connection;
  QString m_projection{"Flat"};
  double m_zoom{1.0};
  double m_longitude{};
  double m_latitude{};
};
} // namespace shackcq::desktop
