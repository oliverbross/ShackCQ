#pragma once

#include <QAudioDevice>
#include <QAudioSource>
#include <QHash>
#include <QIODevice>
#include <QImage>
#include <QMediaDevices>
#include <QMutex>
#include <QObject>
#include <QThreadPool>
#include <QVariantList>
#include <atomic>
#include <memory>

namespace shackcq::desktop {

struct PanadapterRenderFrame {
  QVector<float> trace;
  QVector<float> peak;
  QImage waterfall;
  double floorDb{-120.0};
  double topDb{0.0};
  quint64 sequence{};
  bool discontinuity{};
};

class DesktopPanadapter final : public QObject {
  Q_OBJECT
  Q_PROPERTY(QString state READ state NOTIFY stateChanged)
  Q_PROPERTY(QString selectedDeviceId READ selectedDeviceId WRITE
                 setSelectedDeviceId NOTIFY selectedDeviceChanged)
  Q_PROPERTY(QString currentReceiverId READ currentReceiverId WRITE
                 setCurrentReceiverId NOTIFY currentReceiverChanged)
  Q_PROPERTY(QStringList receiverIds READ receiverIds NOTIFY receiverIdsChanged)
  Q_PROPERTY(QVariantList trace READ trace NOTIFY frameReady)
  Q_PROPERTY(bool hasFrame READ hasFrame NOTIFY frameReady)
  Q_PROPERTY(double peakDb READ peakDb NOTIFY frameReady)
  Q_PROPERTY(bool validStereo READ validStereo NOTIFY frameReady)
  Q_PROPERTY(bool paused READ paused WRITE setPaused NOTIFY settingsChanged)
  Q_PROPERTY(bool fitAutoContrast READ fitAutoContrast WRITE setFitAutoContrast
                 NOTIFY settingsChanged)
  Q_PROPERTY(double fittedFloorDb READ fittedFloorDb NOTIFY frameReady)
  Q_PROPERTY(double fittedTopDb READ fittedTopDb NOTIFY frameReady)
  Q_PROPERTY(QString colourMap READ colourMap WRITE setColourMap NOTIFY
                 settingsChanged)
  Q_PROPERTY(QString displayMode READ displayMode WRITE setDisplayMode NOTIFY
                 settingsChanged)
  Q_PROPERTY(int fftSize READ fftSize WRITE setFftSize NOTIFY settingsChanged)
  Q_PROPERTY(int waterfallRows READ waterfallRows WRITE setWaterfallRows NOTIFY
                 settingsChanged)
  Q_PROPERTY(int averageFrames READ averageFrames WRITE setAverageFrames NOTIFY
                 settingsChanged)
  Q_PROPERTY(
      bool peakHold READ peakHold WRITE setPeakHold NOTIFY settingsChanged)
  Q_PROPERTY(
      double peakDecay READ peakDecay WRITE setPeakDecay NOTIFY settingsChanged)
  Q_PROPERTY(double manualFloorDb READ manualFloorDb WRITE setManualFloorDb
                 NOTIFY settingsChanged)
  Q_PROPERTY(double manualTopDb READ manualTopDb WRITE setManualTopDb NOTIFY
                 settingsChanged)
public:
  explicit DesktopPanadapter(QObject *parent = nullptr);
  ~DesktopPanadapter() override;
  QString state() const { return m_state; }
  QString selectedDeviceId() const { return m_selectedDeviceId; }
  void setSelectedDeviceId(const QString &id);
  QString currentReceiverId() const { return m_currentReceiverId; }
  void setCurrentReceiverId(const QString &id);
  QStringList receiverIds() const;
  QVariantList trace() const;
  bool hasFrame() const;
  double peakDb() const;
  bool validStereo() const;
  bool paused() const { return m_paused; }
  void setPaused(bool paused);
  bool fitAutoContrast() const { return m_fitAutoContrast; }
  void setFitAutoContrast(bool enabled);
  double fittedFloorDb() const;
  double fittedTopDb() const;
  QString colourMap() const { return m_colourMap; }
  void setColourMap(const QString &name);
  QString displayMode() const { return m_displayMode; }
  void setDisplayMode(const QString &mode);
  int fftSize() const { return m_fftSize; }
  void setFftSize(int size);
  int waterfallRows() const { return m_waterfallRows; }
  void setWaterfallRows(int rows);
  int averageFrames() const { return m_averageFrames; }
  void setAverageFrames(int frames);
  bool peakHold() const { return m_peakHold; }
  void setPeakHold(bool enabled);
  double peakDecay() const { return m_peakDecay; }
  void setPeakDecay(double dbPerSecond);
  double manualFloorDb() const { return m_manualFloorDb; }
  void setManualFloorDb(double floorDb);
  double manualTopDb() const { return m_manualTopDb; }
  void setManualTopDb(double topDb);
  PanadapterRenderFrame renderFrame(const QString &receiverId) const;
  QVariantMap health() const;
  QVariantMap configuration() const;
  bool restoreConfiguration(const QVariantMap &configuration,
                            QString *error = nullptr);

  Q_INVOKABLE QVariantList devices() const;
  Q_INVOKABLE bool start(int sampleRate = 96000, bool swapIq = false);
  Q_INVOKABLE void stop();
  Q_INVOKABLE void clearWaterfall();
  Q_INVOKABLE void resetPeak();
  Q_INVOKABLE qulonglong frequencyAt(double normalizedX, double zoom,
                                     double pan) const;
  Q_INVOKABLE double normalizedForFrequency(qulonglong frequencyHz, double zoom,
                                            double pan) const;
  Q_INVOKABLE void pushFloatIq(const QString &receiverId, quint32 sampleRate,
                               const QVector<float> &values,
                               quint64 centreFrequencyHz = 0,
                               bool discontinuity = false);
  bool processPcmForTest(const QByteArray &pcm, int sampleRate = 96000);
  bool waitForIdleForTest(int timeoutMs = 5000);
  void setWorkerDelayForTest(int milliseconds);

signals:
  void stateChanged();
  void selectedDeviceChanged();
  void currentReceiverChanged();
  void receiverIdsChanged();
  void settingsChanged();
  void frameReady();
  void receiverFrameReady(QString receiverId);
  void error(QString message);

private:
  class Sink;
  struct Context;
  std::shared_ptr<Context> context(const QString &receiverId, int sampleRate,
                                   bool swapIq);
  std::shared_ptr<Context> current() const;
  bool configure(Context &context, int sampleRate, bool swapIq);
  void consume(const char *data, qint64 length);
  bool updateFrame(Context &context, int fftSize, int waterfallRows,
                   bool paused, bool fitAutoContrast, double manualFloorDb,
                   double manualTopDb, const QString &colourMap);
  static QRgb colour(const QString &colourMap, float normalized);
  void publishWorkerFrame(const QString &receiverId, bool displayReady,
                          quint64 generation);

  std::unique_ptr<QAudioSource> m_source;
  std::unique_ptr<Sink> m_sink;
  QThreadPool m_dspPool;
  QHash<QString, std::shared_ptr<Context>> m_contexts;
  QString m_state{"Offline — select an exact stereo audio route"};
  QString m_selectedDeviceId;
  QString m_currentReceiverId{"audio:local"};
  QString m_colourMap{"Flightline warm"};
  QString m_displayMode{"Spectrum + waterfall"};
  int m_fftSize{4096};
  int m_waterfallRows{256};
  int m_averageFrames{2};
  bool m_peakHold{true};
  double m_peakDecay{2.0};
  double m_manualFloorDb{-120.0};
  double m_manualTopDb{0.0};
  bool m_paused{};
  bool m_fitAutoContrast{true};
  std::atomic_int m_pendingDsp{};
  std::atomic_bool m_stopping{};
  std::atomic_bool m_fftExecutedOffOwnerThread{};
  std::atomic_int m_workerDelayMs{};
  std::atomic<quint64> m_workerGeneration{1};
  static constexpr int MaxPendingDsp = 8;
  static constexpr int MaxContexts = 9;
  static constexpr int MaxFloatValuesPerFrame = 2'000'000;
};

} // namespace shackcq::desktop
