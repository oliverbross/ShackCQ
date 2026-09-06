#pragma once

#include <QQuickItem>

namespace shackcq::desktop {

class DesktopPanadapter;

class PanadapterSceneItem : public QQuickItem {
  Q_OBJECT
  Q_PROPERTY(QObject *source READ source WRITE setSource NOTIFY sourceChanged)
  Q_PROPERTY(QString receiverId READ receiverId WRITE setReceiverId NOTIFY
                 receiverIdChanged)
  Q_PROPERTY(double zoom READ zoom WRITE setZoom NOTIFY viewChanged)
  Q_PROPERTY(double pan READ pan WRITE setPan NOTIFY viewChanged)
  Q_PROPERTY(double spectrumRatio READ spectrumRatio WRITE setSpectrumRatio
                 NOTIFY viewChanged)
  Q_PROPERTY(
      QString rendererHealth READ rendererHealth NOTIFY rendererHealthChanged)
public:
  explicit PanadapterSceneItem(QQuickItem *parent = nullptr);
  QObject *source() const;
  void setSource(QObject *source);
  QString receiverId() const { return m_receiverId; }
  void setReceiverId(const QString &receiverId);
  double zoom() const { return m_zoom; }
  void setZoom(double zoom);
  double pan() const { return m_pan; }
  void setPan(double pan);
  double spectrumRatio() const { return m_spectrumRatio; }
  void setSpectrumRatio(double ratio);
  QString rendererHealth() const { return m_rendererHealth; }

signals:
  void sourceChanged();
  void receiverIdChanged();
  void viewChanged();
  void rendererHealthChanged();

protected:
  QSGNode *updatePaintNode(QSGNode *oldNode, UpdatePaintNodeData *) override;
  void releaseResources() override;

private:
  void queueRendererHealth(QString health);
  DesktopPanadapter *m_source{};
  QMetaObject::Connection m_frameConnection;
  QString m_receiverId;
  QString m_rendererHealth{"Idle — no frame"};
  double m_zoom{1.0};
  double m_pan{};
  double m_spectrumRatio{0.44};
};

} // namespace shackcq::desktop
