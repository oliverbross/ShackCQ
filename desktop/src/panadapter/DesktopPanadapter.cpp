#include "shackcq/desktop/DesktopPanadapter.hpp"

#include "shackcq/core.h"

#include <QAudioFormat>
#include <QColor>
#include <QDateTime>
#include <QElapsedTimer>
#include <QMetaObject>
#include <QMutexLocker>
#include <QThread>
#include <algorithm>
#include <cmath>
#include <cstring>
#include <vector>

namespace shackcq::desktop {

struct DesktopPanadapter::Context {
  explicit Context() : dsp(shackcq_panadapter_context_create()) {}
  ~Context() { shackcq_panadapter_context_destroy(dsp); }
  shackcq_panadapter_context *dsp{};
  QVector<float> trace;
  QVector<float> peak;
  QImage waterfall;
  shackcq_panadapter_snapshot snapshot{};
  int sampleRate{};
  bool swapIq{};
  quint64 centreFrequencyHz{};
  quint64 observedFrameSequence{};
  quint64 droppedFrames{};
  quint64 lastDiscontinuities{};
  bool lastDisplayDiscontinuity{};
  qint64 lastObservedMs{};
  QElapsedTimer displayCadence;
  mutable QMutex mutex;
};

class DesktopPanadapter::Sink final : public QIODevice {
public:
  explicit Sink(DesktopPanadapter *owner) : QIODevice(owner), m_owner(owner) {
    open(QIODevice::WriteOnly);
  }
  qint64 readData(char *, qint64) override { return 0; }
  qint64 writeData(const char *data, qint64 length) override {
    m_owner->consume(data, length);
    return length;
  }

private:
  DesktopPanadapter *m_owner;
};

DesktopPanadapter::DesktopPanadapter(QObject *parent) : QObject(parent) {
  m_dspPool.setMaxThreadCount(1);
  m_dspPool.setExpiryTimeout(-1);
}
DesktopPanadapter::~DesktopPanadapter() {
  m_stopping = true;
  stop();
  m_dspPool.waitForDone();
  m_contexts.clear();
}

std::shared_ptr<DesktopPanadapter::Context>
DesktopPanadapter::context(const QString &id, int sampleRate, bool swapIq) {
  auto value = m_contexts.value(id);
  if (!value) {
    if (m_contexts.size() >= MaxContexts)
      return {};
    value = std::make_shared<Context>();
    QMutexLocker lock(&value->mutex);
    if (!value->dsp || !configure(*value, sampleRate, swapIq))
      return {};
    m_contexts.insert(id, value);
    emit receiverIdsChanged();
  } else {
    QMutexLocker lock(&value->mutex);
    if (value->sampleRate != sampleRate || value->swapIq != swapIq ||
        value->trace.size() != m_fftSize) {
      if (!configure(*value, sampleRate, swapIq))
        return {};
    }
  }
  return value;
}
std::shared_ptr<DesktopPanadapter::Context> DesktopPanadapter::current() const {
  return m_contexts.value(m_currentReceiverId);
}

bool DesktopPanadapter::configure(Context &value, int sampleRate, bool swapIq) {
  if (!value.dsp || sampleRate < 8000 || sampleRate > 10000000)
    return false;
  shackcq_panadapter_config c{};
  c.sample_rate = static_cast<uint32_t>(sampleRate);
  c.fft_size = static_cast<uint32_t>(m_fftSize);
  c.overlap_percent = 50;
  c.window = 0;
  c.display_floor_db = static_cast<float>(m_manualFloorDb);
  c.display_top_db = static_cast<float>(m_manualTopDb);
  c.attack = .78F;
  c.release = .16F;
  c.average_frames = static_cast<uint32_t>(m_averageFrames);
  c.peak_hold = m_peakHold ? 1 : 0;
  c.peak_decay_db_per_second = static_cast<float>(m_peakDecay);
  c.swap_iq = swapIq;
  c.i_trim = 1.0F;
  c.q_trim = 1.0F;
  c.zoom_decimation = 1;
  c.fit_auto_contrast = m_fitAutoContrast ? 1 : 0;
  if (shackcq_panadapter_configure(value.dsp, &c) != 1)
    return false;
  value.sampleRate = sampleRate;
  value.swapIq = swapIq;
  value.trace.fill(-140.0F, m_fftSize);
  value.peak.fill(-140.0F, m_fftSize);
  value.waterfall =
      QImage(m_fftSize, m_waterfallRows, QImage::Format_ARGB32_Premultiplied);
  value.waterfall.fill(QColor("#101316"));
  value.snapshot = {};
  value.lastDiscontinuities = 0;
  return true;
}

void DesktopPanadapter::setSelectedDeviceId(const QString &id) {
  if (id == m_selectedDeviceId)
    return;
  stop();
  m_selectedDeviceId = id;
  emit selectedDeviceChanged();
}
void DesktopPanadapter::setCurrentReceiverId(const QString &id) {
  if (id.isEmpty() || id == m_currentReceiverId)
    return;
  m_currentReceiverId = id;
  emit currentReceiverChanged();
  emit frameReady();
}
QStringList DesktopPanadapter::receiverIds() const {
  QStringList ids = m_contexts.keys();
  std::sort(ids.begin(), ids.end());
  return ids;
}
QVariantList DesktopPanadapter::trace() const {
  QVariantList result;
  const auto value = current();
  if (!value)
    return result;
  QMutexLocker lock(&value->mutex);
  result.reserve(value->trace.size());
  for (float bin : value->trace)
    result.push_back(bin);
  return result;
}
bool DesktopPanadapter::hasFrame() const {
  const auto value = current();
  if (!value)
    return false;
  QMutexLocker lock(&value->mutex);
  return value->snapshot.sequence > 0;
}
double DesktopPanadapter::peakDb() const {
  const auto value = current();
  if (!value)
    return -140.0;
  QMutexLocker lock(&value->mutex);
  return value->snapshot.peak_db;
}
bool DesktopPanadapter::validStereo() const {
  const auto value = current();
  if (!value)
    return false;
  QMutexLocker lock(&value->mutex);
  return value->snapshot.valid_stereo != 0;
}
double DesktopPanadapter::fittedFloorDb() const {
  const auto value = current();
  if (!value)
    return -120.0;
  QMutexLocker lock(&value->mutex);
  return value->snapshot.fitted_floor_db;
}
double DesktopPanadapter::fittedTopDb() const {
  const auto value = current();
  if (!value)
    return 0.0;
  QMutexLocker lock(&value->mutex);
  return value->snapshot.fitted_top_db;
}

QVariantList DesktopPanadapter::devices() const {
  QVariantList list;
  for (const auto &device : QMediaDevices::audioInputs())
    list << QVariantMap{{"id", QString::fromLatin1(device.id().toBase64())},
                        {"description", device.description()},
                        {"default", device.isDefault()},
                        {"minimumChannels", device.minimumChannelCount()},
                        {"maximumChannels", device.maximumChannelCount()}};
  return list;
}
bool DesktopPanadapter::start(int sampleRate, bool swapIq) {
  stop();
  if (m_selectedDeviceId.isEmpty()) {
    emit error(
        "Select an exact stereo audio input; microphone fallback is disabled");
    return false;
  }
  QAudioDevice selected;
  for (const auto &device : QMediaDevices::audioInputs())
    if (QString::fromLatin1(device.id().toBase64()) == m_selectedDeviceId) {
      selected = device;
      break;
    }
  if (selected.isNull()) {
    m_state = "Offline — configured audio route is absent";
    emit stateChanged();
    emit error(m_state);
    return false;
  }
  if (selected.maximumChannelCount() < 2) {
    emit error("The selected input does not expose real stereo I/Q");
    return false;
  }
  QAudioFormat format;
  format.setSampleRate(sampleRate);
  format.setChannelCount(2);
  format.setSampleFormat(QAudioFormat::Int16);
  if (!selected.isFormatSupported(format)) {
    emit error(
        "The exact audio route does not support requested stereo Int16 format");
    return false;
  }
  if (!context("audio:local", sampleRate, swapIq)) {
    emit error("Panadapter DSP rejected configuration");
    return false;
  }
  m_currentReceiverId = "audio:local";
  m_sink = std::make_unique<Sink>(this);
  m_source = std::make_unique<QAudioSource>(selected, format, this);
  m_source->setBufferSize(sampleRate * 4 / 10);
  m_source->start(m_sink.get());
  if (m_source->error() != QAudio::NoError) {
    emit error("Qt Multimedia could not start the exact audio route");
    stop();
    return false;
  }
  m_state =
      QStringLiteral("Receiving %1 Hz exact local stereo I/Q — receive only")
          .arg(sampleRate);
  emit currentReceiverChanged();
  emit stateChanged();
  return true;
}
void DesktopPanadapter::stop() {
  ++m_workerGeneration;
  if (m_source) {
    m_source->stop();
    m_source.reset();
  }
  m_sink.reset();
  m_dspPool.waitForDone();
  m_state = m_contexts.isEmpty()
                ? "Offline — select an exact stereo audio route"
                : "TCI I/Q contexts retained — local audio stopped";
  emit stateChanged();
}
void DesktopPanadapter::consume(const char *data, qint64 length) {
  auto value = m_contexts.value("audio:local");
  if (!value || length <= 0 || m_stopping.load())
    return;
  if (m_pendingDsp.load() >= MaxPendingDsp) {
    QMutexLocker lock(&value->mutex);
    ++value->droppedFrames;
    return;
  }
  const QByteArray pcm(data, length);
  const int fftSize = m_fftSize, rows = m_waterfallRows;
  const bool paused = m_paused, fit = m_fitAutoContrast;
  const double floor = m_manualFloorDb, top = m_manualTopDb;
  const QString colourMap = m_colourMap;
  const quint64 generation = m_workerGeneration.load();
  ++m_pendingDsp;
  m_dspPool.start([this, value, pcm, fftSize, rows, paused, fit, floor, top,
                   colourMap, generation] {
    if (const int delay = m_workerDelayMs.load(); delay > 0)
      QThread::msleep(static_cast<unsigned long>(delay));
    m_fftExecutedOffOwnerThread = QThread::currentThread() != thread();
    bool display = false;
    {
      QMutexLocker lock(&value->mutex);
      if (shackcq_panadapter_push(value->dsp,
                             reinterpret_cast<const uint8_t *>(pcm.constData()),
                             static_cast<size_t>(pcm.size()), 2, 2, 16, 0) == 1)
        display = updateFrame(*value, fftSize, rows, paused, fit, floor, top,
                              colourMap);
    }
    --m_pendingDsp;
    publishWorkerFrame("audio:local", display, generation);
  });
}

QRgb DesktopPanadapter::colour(const QString &colourMap, float x) {
  x = std::clamp(x, 0.0F, 1.0F);
  if (colourMap == "High contrast")
    return QColor::fromRgbF(x * x, x, x < .5F ? 0.0F : (x - .5F) * 2.0F).rgba();
  if (colourMap == "Viridis")
    return QColor::fromRgbF(.27F + .55F * x, .05F + .85F * std::sqrt(x),
                            .33F + .35F * (1.0F - x))
        .rgba();
  if (colourMap == "Thermal mono")
    return QColor::fromRgbF(x, x, x).rgba();
  return QColor::fromRgbF(.06F + .88F * x, .08F + .48F * x * x, .10F + .10F * x)
      .rgba();
}

bool DesktopPanadapter::updateFrame(Context &value, int fftSize,
                                    int waterfallRows, bool paused,
                                    bool fitAutoContrast, double manualFloorDb,
                                    double manualTopDb,
                                    const QString &colourMap) {
  std::vector<float> trace(fftSize), waterfall(fftSize), peak(fftSize);
  shackcq_panadapter_snapshot snapshot{};
  const int count =
      shackcq_panadapter_copy_frame(value.dsp, &snapshot, trace.data(),
                               waterfall.data(), peak.data(), trace.size());
  if (count <= 0)
    return false;
  value.trace = QVector<float>(trace.cbegin(), trace.cbegin() + count);
  value.peak = QVector<float>(peak.cbegin(), peak.cbegin() + count);
  const bool discontinuity =
      snapshot.discontinuities != value.lastDiscontinuities;
  value.lastDiscontinuities = snapshot.discontinuities;
  value.lastDisplayDiscontinuity = discontinuity;
  value.snapshot = snapshot;
  value.lastObservedMs = QDateTime::currentMSecsSinceEpoch();
  ++value.observedFrameSequence;
  if (!paused) {
    if (value.waterfall.width() != count ||
        value.waterfall.height() != waterfallRows) {
      value.waterfall =
          QImage(count, waterfallRows, QImage::Format_ARGB32_Premultiplied);
      value.waterfall.fill(QColor("#101316"));
    }
    if (value.waterfall.height() > 1)
      std::memmove(value.waterfall.scanLine(1),
                   value.waterfall.constScanLine(0),
                   static_cast<size_t>(value.waterfall.bytesPerLine()) *
                       static_cast<size_t>(value.waterfall.height() - 1));
    QRgb *row = reinterpret_cast<QRgb *>(value.waterfall.scanLine(0));
    const float floor = fitAutoContrast ? snapshot.fitted_floor_db
                                        : static_cast<float>(manualFloorDb);
    const float top = fitAutoContrast ? snapshot.fitted_top_db
                                      : static_cast<float>(manualTopDb);
    for (int i = 0; i < count; ++i)
      row[i] =
          discontinuity
              ? qRgba(211, 139, 34, 255)
              : colour(colourMap, (waterfall[static_cast<size_t>(i)] - floor) /
                                      std::max(20.0F, top - floor));
  }
  if (!value.displayCadence.isValid()) {
    value.displayCadence.start();
    return true;
  }
  if (value.displayCadence.elapsed() < 30)
    return false;
  value.displayCadence.restart();
  return true;
}

void DesktopPanadapter::publishWorkerFrame(const QString &id, bool displayReady,
                                           quint64 generation) {
  if (m_stopping.load() || generation != m_workerGeneration.load())
    return;
  QMetaObject::invokeMethod(
      this,
      [this, id, displayReady, generation] {
        if (m_stopping.load() || generation != m_workerGeneration.load())
          return;
        const QString state = QStringLiteral("Receiving bounded TCI float32 "
                                             "I/Q · %1 contexts · receive only")
                                  .arg(m_contexts.size());
        if (m_state != state) {
          m_state = state;
          emit stateChanged();
        }
        if (displayReady) {
          emit receiverFrameReady(id);
          if (id == m_currentReceiverId)
            emit frameReady();
        }
      },
      Qt::QueuedConnection);
}

void DesktopPanadapter::pushFloatIq(const QString &id, quint32 sampleRate,
                                    const QVector<float> &values,
                                    quint64 centre, bool discontinuity) {
  if (id.trimmed().isEmpty() || values.isEmpty() ||
      values.size() > MaxFloatValuesPerFrame || m_stopping.load())
    return;
  auto value = context(id, static_cast<int>(sampleRate), false);
  if (!value) {
    emit error("TCI I/Q sample rate or FFT configuration rejected");
    return;
  }
  if (m_pendingDsp.load() >= MaxPendingDsp) {
    QMutexLocker lock(&value->mutex);
    ++value->droppedFrames;
    return;
  }
  const int fftSize = m_fftSize, rows = m_waterfallRows;
  const bool paused = m_paused, fit = m_fitAutoContrast;
  const double floor = m_manualFloorDb, top = m_manualTopDb;
  const QString colourMap = m_colourMap;
  const QVector<float> samples = values;
  const quint64 generation = m_workerGeneration.load();
  {
    QMutexLocker lock(&value->mutex);
    value->centreFrequencyHz = centre;
  }
  ++m_pendingDsp;
  m_dspPool.start([this, value, id, samples, discontinuity, fftSize, rows,
                   paused, fit, floor, top, colourMap, generation] {
    if (const int delay = m_workerDelayMs.load(); delay > 0)
      QThread::msleep(static_cast<unsigned long>(delay));
    m_fftExecutedOffOwnerThread = QThread::currentThread() != thread();
    bool display = false;
    {
      QMutexLocker lock(&value->mutex);
      if (shackcq_panadapter_push_float_iq(value->dsp, samples.constData(),
                                      static_cast<size_t>(samples.size()),
                                      discontinuity ? 1 : 0) == 1)
        display = updateFrame(*value, fftSize, rows, paused, fit, floor, top,
                              colourMap);
    }
    --m_pendingDsp;
    publishWorkerFrame(id, display, generation);
  });
}

bool DesktopPanadapter::processPcmForTest(const QByteArray &pcm,
                                          int sampleRate) {
  auto value = context("audio:local", sampleRate, false);
  if (!value)
    return false;
  m_currentReceiverId = "audio:local";
  QMutexLocker lock(&value->mutex);
  const bool ok =
      shackcq_panadapter_push(value->dsp,
                         reinterpret_cast<const uint8_t *>(pcm.constData()),
                         static_cast<size_t>(pcm.size()), 2, 2, 16, 0) == 1;
  updateFrame(*value, m_fftSize, m_waterfallRows, m_paused, m_fitAutoContrast,
              m_manualFloorDb, m_manualTopDb, m_colourMap);
  return ok;
}
bool DesktopPanadapter::waitForIdleForTest(int timeoutMs) {
  return m_dspPool.waitForDone(timeoutMs);
}
void DesktopPanadapter::setWorkerDelayForTest(int milliseconds) {
  m_workerDelayMs = std::clamp(milliseconds, 0, 100);
}

void DesktopPanadapter::clearWaterfall() {
  for (auto value : m_contexts) {
    QMutexLocker lock(&value->mutex);
    value->waterfall.fill(QColor("#101316"));
  }
  emit frameReady();
}
void DesktopPanadapter::resetPeak() {
  for (auto value : m_contexts) {
    QMutexLocker lock(&value->mutex);
    shackcq_panadapter_reset_peak_hold(value->dsp);
  }
}
qulonglong DesktopPanadapter::frequencyAt(double normalizedX, double zoom,
                                          double pan) const {
  const auto value = current();
  if (!value || value->sampleRate <= 0 || value->centreFrequencyHz == 0)
    return 0;
  zoom = std::clamp(zoom, 1.0, 32.0);
  pan = std::clamp(pan, -1.0, 1.0);
  normalizedX = std::clamp(normalizedX, 0.0, 1.0);
  const double visible = static_cast<double>(value->sampleRate) / zoom;
  const double travel = static_cast<double>(value->sampleRate) - visible;
  const double left = static_cast<double>(value->centreFrequencyHz) -
                      value->sampleRate * .5 + travel * (pan + 1.0) * .5;
  return static_cast<qulonglong>(std::max(0.0, left + normalizedX * visible));
}
double DesktopPanadapter::normalizedForFrequency(qulonglong frequency,
                                                 double zoom,
                                                 double pan) const {
  const auto value = current();
  if (!value || value->sampleRate <= 0 || value->centreFrequencyHz == 0)
    return -1.0;
  const double left = static_cast<double>(frequencyAt(0.0, zoom, pan));
  const double right = static_cast<double>(frequencyAt(1.0, zoom, pan));
  return right > left ? (static_cast<double>(frequency) - left) / (right - left)
                      : -1.0;
}
void DesktopPanadapter::setPaused(bool value) {
  if (m_paused == value)
    return;
  m_paused = value;
  emit settingsChanged();
}
void DesktopPanadapter::setFitAutoContrast(bool value) {
  if (m_fitAutoContrast == value)
    return;
  m_fitAutoContrast = value;
  for (auto context : m_contexts) {
    QMutexLocker lock(&context->mutex);
    configure(*context, context->sampleRate, context->swapIq);
  }
  emit settingsChanged();
}
void DesktopPanadapter::setColourMap(const QString &value) {
  static const QStringList allowed{"Flightline warm", "High contrast",
                                   "Viridis", "Thermal mono"};
  if (!allowed.contains(value) || m_colourMap == value)
    return;
  m_colourMap = value;
  clearWaterfall();
  emit settingsChanged();
}
void DesktopPanadapter::setDisplayMode(const QString &value) {
  static const QStringList allowed{"Spectrum + waterfall", "Spectrum only",
                                   "Waterfall only"};
  if (!allowed.contains(value) || m_displayMode == value)
    return;
  m_displayMode = value;
  emit settingsChanged();
}
void DesktopPanadapter::setFftSize(int value) {
  if ((value != 1024 && value != 2048 && value != 4096 && value != 8192) ||
      m_fftSize == value)
    return;
  m_dspPool.waitForDone();
  m_fftSize = value;
  for (auto context : m_contexts) {
    QMutexLocker lock(&context->mutex);
    configure(*context, context->sampleRate, context->swapIq);
  }
  emit settingsChanged();
}
void DesktopPanadapter::setWaterfallRows(int value) {
  value = std::clamp(value, 64, 512);
  if (m_waterfallRows == value)
    return;
  m_dspPool.waitForDone();
  m_waterfallRows = value;
  for (auto context : m_contexts) {
    QMutexLocker lock(&context->mutex);
    context->waterfall =
        QImage(m_fftSize, value, QImage::Format_ARGB32_Premultiplied);
    context->waterfall.fill(QColor("#101316"));
  }
  emit settingsChanged();
}
void DesktopPanadapter::setAverageFrames(int value) {
  value = std::clamp(value, 1, 64);
  if (m_averageFrames == value)
    return;
  m_dspPool.waitForDone();
  m_averageFrames = value;
  for (auto context : m_contexts) {
    QMutexLocker lock(&context->mutex);
    configure(*context, context->sampleRate, context->swapIq);
  }
  emit settingsChanged();
}
void DesktopPanadapter::setPeakHold(bool value) {
  if (m_peakHold == value)
    return;
  m_dspPool.waitForDone();
  m_peakHold = value;
  for (auto context : m_contexts) {
    QMutexLocker lock(&context->mutex);
    configure(*context, context->sampleRate, context->swapIq);
  }
  emit settingsChanged();
}
void DesktopPanadapter::setPeakDecay(double value) {
  value = std::clamp(value, 0.0, 30.0);
  if (qFuzzyCompare(m_peakDecay, value))
    return;
  m_dspPool.waitForDone();
  m_peakDecay = value;
  for (auto context : m_contexts) {
    QMutexLocker lock(&context->mutex);
    configure(*context, context->sampleRate, context->swapIq);
  }
  emit settingsChanged();
}
void DesktopPanadapter::setManualFloorDb(double value) {
  value = std::clamp(value, -140.0, -20.0);
  if (value > m_manualTopDb - 20.0)
    value = m_manualTopDb - 20.0;
  if (qFuzzyCompare(m_manualFloorDb, value))
    return;
  m_dspPool.waitForDone();
  m_manualFloorDb = value;
  for (auto context : m_contexts) {
    QMutexLocker lock(&context->mutex);
    configure(*context, context->sampleRate, context->swapIq);
  }
  emit settingsChanged();
}
void DesktopPanadapter::setManualTopDb(double value) {
  value = std::clamp(value, m_manualFloorDb + 20.0, 20.0);
  if (qFuzzyCompare(m_manualTopDb, value))
    return;
  m_dspPool.waitForDone();
  m_manualTopDb = value;
  for (auto context : m_contexts) {
    QMutexLocker lock(&context->mutex);
    configure(*context, context->sampleRate, context->swapIq);
  }
  emit settingsChanged();
}

PanadapterRenderFrame DesktopPanadapter::renderFrame(const QString &id) const {
  const auto value = m_contexts.value(id.isEmpty() ? m_currentReceiverId : id);
  if (!value)
    return {};
  QMutexLocker lock(&value->mutex);
  const double floor =
      m_fitAutoContrast ? value->snapshot.fitted_floor_db : m_manualFloorDb;
  const double top =
      m_fitAutoContrast ? value->snapshot.fitted_top_db : m_manualTopDb;
  return {value->trace,
          value->peak,
          value->waterfall,
          floor,
          top,
          value->snapshot.sequence,
          value->lastDisplayDiscontinuity};
}
QVariantMap DesktopPanadapter::health() const {
  QVariantList sources;
  for (const QString &id : receiverIds()) {
    const auto value = m_contexts.value(id);
    QMutexLocker lock(&value->mutex);
    sources.push_back(QVariantMap{
        {"sourceId", id},
        {"receiverId", id},
        {"sampleRate", value->sampleRate},
        {"centreFrequencyHz",
         QVariant::fromValue<qulonglong>(value->centreFrequencyHz)},
        {"streamHealth", value->lastObservedMs > 0 ? "Observed" : "Pending"},
        {"observedFrameSequence",
         QVariant::fromValue<qulonglong>(value->observedFrameSequence)},
        {"droppedFrames",
         QVariant::fromValue<qulonglong>(value->droppedFrames)},
        {"discontinuities",
         QVariant::fromValue<qulonglong>(value->snapshot.discontinuities)},
        {"nonFiniteSamples",
         QVariant::fromValue<qulonglong>(value->snapshot.non_finite_samples)},
        {"lastObservedMs", value->lastObservedMs}});
  }
  return {{"state", m_state},
          {"source", m_currentReceiverId},
          {"fftSize", m_fftSize},
          {"renderer", "Qt Quick scene graph / bounded 30 Hz"},
          {"effectiveFrameRateHz", 33},
          {"fftWorker", true},
          {"fftExecutedOffOwnerThread", m_fftExecutedOffOwnerThread.load()},
          {"workerQueueDepth", m_pendingDsp.load()},
          {"workerQueueCapacity", MaxPendingDsp},
          {"contexts", sources},
          {"waterfallRows", m_waterfallRows},
          {"waterfallWidth", m_fftSize},
          {"waterfallBytesPerContext",
           QVariant::fromValue<qulonglong>(quint64(m_fftSize) *
                                           quint64(m_waterfallRows) * 4ULL)},
          {"pausedDisplay", m_paused},
          {"captureStopped", false}};
}
QVariantMap DesktopPanadapter::configuration() const {
  return {{"schemaVersion", 1},
          {"fftSize", m_fftSize},
          {"waterfallRows", m_waterfallRows},
          {"fitAutoContrast", m_fitAutoContrast},
          {"colourMap", m_colourMap},
          {"displayMode", m_displayMode},
          {"currentReceiverId", m_currentReceiverId},
          {"averageFrames", m_averageFrames},
          {"peakHold", m_peakHold},
          {"peakDecay", m_peakDecay},
          {"manualFloorDb", m_manualFloorDb},
          {"manualTopDb", m_manualTopDb}};
}
bool DesktopPanadapter::restoreConfiguration(const QVariantMap &value,
                                             QString *error) {
  const int schema = value.value("schemaVersion", 0).toInt();
  if (schema > 1) {
    if (error)
      *error = "panadapter schema is newer than this build";
    return false;
  }
  setFftSize(value.value("fftSize", m_fftSize).toInt());
  setWaterfallRows(value.value("waterfallRows", m_waterfallRows).toInt());
  setAverageFrames(value.value("averageFrames", m_averageFrames).toInt());
  setPeakHold(value.value("peakHold", m_peakHold).toBool());
  setPeakDecay(value.value("peakDecay", m_peakDecay).toDouble());
  setManualFloorDb(value.value("manualFloorDb", m_manualFloorDb).toDouble());
  setManualTopDb(value.value("manualTopDb", m_manualTopDb).toDouble());
  setFitAutoContrast(value.value("fitAutoContrast", true).toBool());
  setColourMap(value.value("colourMap", m_colourMap).toString());
  setDisplayMode(value.value("displayMode", m_displayMode).toString());
  const QString receiver = value.value("currentReceiverId").toString();
  if (!receiver.isEmpty())
    m_currentReceiverId = receiver;
  return true;
}

} // namespace shackcq::desktop
