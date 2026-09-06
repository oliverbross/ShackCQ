#include "shackcq/desktop/PanadapterSceneItem.hpp"

#include "shackcq/desktop/DesktopPanadapter.hpp"

#include <QQuickWindow>
#include <QSGFlatColorMaterial>
#include <QSGGeometryNode>
#include <QSGImageNode>
#include <algorithm>
#include <utility>

namespace shackcq::desktop {
namespace {

class PanadapterNode final : public QSGNode {
public:
  PanadapterNode() {
    spectrumGeometry =
        new QSGGeometry(QSGGeometry::defaultAttributes_Point2D(), 0);
    spectrum = new QSGGeometryNode;
    spectrumGeometry->setDrawingMode(QSGGeometry::DrawLineStrip);
    spectrumGeometry->setLineWidth(1.0F);
    spectrum->setGeometry(spectrumGeometry);
    spectrum->setFlags(QSGNode::OwnsGeometry | QSGNode::OwnsMaterial);
    auto *spectrumMaterial = new QSGFlatColorMaterial;
    spectrumMaterial->setColor(QColor("#d38b22"));
    spectrum->setMaterial(spectrumMaterial);
    appendChildNode(spectrum);

    peakGeometry = new QSGGeometry(QSGGeometry::defaultAttributes_Point2D(), 0);
    peak = new QSGGeometryNode;
    peakGeometry->setDrawingMode(QSGGeometry::DrawLineStrip);
    peakGeometry->setLineWidth(1.0F);
    peak->setGeometry(peakGeometry);
    peak->setFlags(QSGNode::OwnsGeometry | QSGNode::OwnsMaterial);
    auto *peakMaterial = new QSGFlatColorMaterial;
    peakMaterial->setColor(QColor("#e3c765"));
    peak->setMaterial(peakMaterial);
    appendChildNode(peak);
  }
  QSGImageNode *waterfall{};
  QSGGeometry *spectrumGeometry{};
  QSGGeometryNode *spectrum{};
  QSGGeometry *peakGeometry{};
  QSGGeometryNode *peak{};
  quint64 sequence{};
};

void updateLine(QSGGeometry &geometry, const QVector<float> &values,
                const QRectF &bounds, float floor, float top, int first,
                int last) {
  const int available = static_cast<int>(values.size());
  const int boundedFirst = std::clamp(first, 0, available);
  const int boundedLast = std::clamp(last, boundedFirst, available);
  const int count = boundedLast - boundedFirst;
  if (geometry.vertexCount() != count)
    geometry.allocate(count);
  if (count < 2)
    return;
  auto *vertices = geometry.vertexDataAsPoint2D();
  const float range = std::max(20.0F, top - floor);
  for (int index = 0; index < count; ++index) {
    const float x = static_cast<float>(
        bounds.left() + bounds.width() * index / std::max(1, count - 1));
    const float normalized =
        std::clamp((values.at(boundedFirst + index) - floor) / range, 0.0F,
                   1.0F);
    vertices[index].set(
        x, static_cast<float>(bounds.bottom() - bounds.height() * normalized));
  }
}

} // namespace

PanadapterSceneItem::PanadapterSceneItem(QQuickItem *parent)
    : QQuickItem(parent) {
  setFlag(ItemHasContents, true);
  setClip(true);
}
QObject *PanadapterSceneItem::source() const { return m_source; }
void PanadapterSceneItem::setSource(QObject *value) {
  auto *source = qobject_cast<DesktopPanadapter *>(value);
  if (source == m_source)
    return;
  if (m_frameConnection)
    disconnect(m_frameConnection);
  m_source = source;
  if (m_source)
    m_frameConnection = connect(
        m_source, &DesktopPanadapter::receiverFrameReady, this,
        [this](const QString &id) {
          if (m_receiverId.isEmpty() || m_receiverId == id)
            update();
        },
        Qt::QueuedConnection);
  emit sourceChanged();
  update();
}
void PanadapterSceneItem::setReceiverId(const QString &id) {
  if (m_receiverId == id)
    return;
  m_receiverId = id;
  emit receiverIdChanged();
  update();
}
void PanadapterSceneItem::setZoom(double value) {
  value = std::clamp(value, 1.0, 32.0);
  if (qFuzzyCompare(m_zoom, value))
    return;
  m_zoom = value;
  emit viewChanged();
  update();
}
void PanadapterSceneItem::setPan(double value) {
  value = std::clamp(value, -1.0, 1.0);
  if (qFuzzyCompare(m_pan, value))
    return;
  m_pan = value;
  emit viewChanged();
  update();
}
void PanadapterSceneItem::setSpectrumRatio(double value) {
  value = std::clamp(value, .2, .8);
  if (qFuzzyCompare(m_spectrumRatio, value))
    return;
  m_spectrumRatio = value;
  emit viewChanged();
  update();
}

void PanadapterSceneItem::queueRendererHealth(QString health) {
  QMetaObject::invokeMethod(
      this,
      [this, health = std::move(health)] {
        if (m_rendererHealth == health)
          return;
        m_rendererHealth = health;
        emit rendererHealthChanged();
      },
      Qt::QueuedConnection);
}

QSGNode *PanadapterSceneItem::updatePaintNode(QSGNode *oldNode,
                                              UpdatePaintNodeData *) {
  auto *node = static_cast<PanadapterNode *>(oldNode);
  if (!node)
    node = new PanadapterNode;
  if (!m_source || !window()) {
    queueRendererHealth(QStringLiteral("Idle — source unavailable"));
    return node;
  }
  const PanadapterRenderFrame frame = m_source->renderFrame(m_receiverId);
  if (frame.trace.size() < 2) {
    queueRendererHealth(QStringLiteral("Idle — no observed frame"));
    return node;
  }
  const QString mode = m_source->displayMode();
  const bool spectrumVisible = mode != "Waterfall only";
  const bool waterfallVisible = mode != "Spectrum only";
  const QRectF bounds = boundingRect();
  const qreal spectrumHeight =
      spectrumVisible ? (waterfallVisible ? bounds.height() * m_spectrumRatio
                                          : bounds.height())
                      : 0.0;
  const QRectF spectrumBounds(bounds.left(), bounds.top(), bounds.width(),
                              spectrumHeight);
  const QRectF waterfallBounds(bounds.left(), bounds.top() + spectrumHeight,
                               bounds.width(),
                               bounds.height() - spectrumHeight);
  const int visible =
      std::max(2, static_cast<int>(frame.trace.size() / m_zoom));
  const int travel = frame.trace.size() - visible;
  const int first =
      std::clamp(static_cast<int>(travel * (m_pan + 1.0) * .5), 0, travel);
  const int last = first + visible;
  if (spectrumVisible) {
    updateLine(*node->spectrumGeometry, frame.trace, spectrumBounds,
               static_cast<float>(frame.floorDb),
               static_cast<float>(frame.topDb), first, last);
    updateLine(*node->peakGeometry, frame.peak, spectrumBounds,
               static_cast<float>(frame.floorDb),
               static_cast<float>(frame.topDb), first, last);
  } else {
    node->spectrumGeometry->allocate(0);
    node->peakGeometry->allocate(0);
  }
  node->spectrum->markDirty(QSGNode::DirtyGeometry);
  node->peak->markDirty(QSGNode::DirtyGeometry);
  if (waterfallVisible && !frame.waterfall.isNull() &&
      node->sequence != frame.sequence) {
    if (QSGTexture *replacement =
            window()->createTextureFromImage(frame.waterfall)) {
      replacement->setFiltering(QSGTexture::Linear);
      auto *replacementNode = window()->createImageNode();
      if (!replacementNode) {
        delete replacement;
        return node;
      }
      replacementNode->setTexture(replacement);
      replacementNode->setOwnsTexture(true);
      if (node->waterfall) {
        node->removeChildNode(node->waterfall);
        delete node->waterfall;
      }
      node->waterfall = replacementNode;
      node->prependChildNode(node->waterfall);
      node->sequence = frame.sequence;
    }
  }
  if (node->waterfall) {
    node->waterfall->setRect(waterfallVisible ? waterfallBounds : QRectF{});
    if (waterfallVisible)
      node->waterfall->setSourceRect(
          QRectF(first, 0, visible, frame.waterfall.height()));
  }
  queueRendererHealth(
      QStringLiteral("Healthy · scene graph · %1 bins · DPR %2")
          .arg(visible)
          .arg(window()->effectiveDevicePixelRatio(), 0, 'f', 2));
  return node;
}
void PanadapterSceneItem::releaseResources() {
  queueRendererHealth(QStringLiteral("Released cleanly"));
}

} // namespace shackcq::desktop
