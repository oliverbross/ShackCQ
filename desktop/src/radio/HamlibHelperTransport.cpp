#include "shackcq/desktop/HamlibHelperTransport.hpp"

#include <QCoreApplication>
#include <QEventLoop>
#include <QFileInfo>
#include <QElapsedTimer>
#include <QJsonDocument>
#include <QTimer>

namespace shackcq::desktop {
namespace {
constexpr qsizetype MaxLineBytes = 64 * 1024;
}

HamlibHelperTransport::HamlibHelperTransport(QObject *parent) : QObject(parent) {
  m_program = QCoreApplication::applicationDirPath() +
              QStringLiteral("/shackcq-hamlib-helper") +
#ifdef Q_OS_WIN
              QStringLiteral(".exe");
#else
              QString{};
#endif
  if (!QFileInfo::exists(m_program)) {
    m_program = QCoreApplication::applicationDirPath() +
                QStringLiteral("/stationd/shackcq-hamlib-helper") +
#ifdef Q_OS_WIN
                QStringLiteral(".exe");
#else
                QString{};
#endif
  }
  connect(&m_process, &QProcess::readyReadStandardOutput, this,
          &HamlibHelperTransport::consumeOutput);
  connect(&m_process, &QProcess::errorOccurred, this,
          [this](QProcess::ProcessError) {
            if (m_process.state() == QProcess::NotRunning)
              quarantine(QStringLiteral("Hamlib helper unavailable"));
          });
  connect(&m_process,
          qOverload<int, QProcess::ExitStatus>(&QProcess::finished), this,
          [this](int, QProcess::ExitStatus) {
            if (m_operationActive || !m_quarantined)
              quarantine(QStringLiteral("Hamlib helper stopped unexpectedly"));
          });
}

HamlibHelperTransport::~HamlibHelperTransport() { close(); }

void HamlibHelperTransport::setProgramForTest(const QString &program,
                                              const QStringList &arguments) {
  close();
  m_program = program;
  m_arguments = arguments;
}

void HamlibHelperTransport::setTimeoutsForTest(int operationMillis,
                                               int stopMillis) {
  m_operationTimeoutMillis = qMax(10, operationMillis);
  m_stopTimeoutMillis = qMax(10, stopMillis);
}

bool HamlibHelperTransport::startProcess() {
  if (m_process.state() != QProcess::NotRunning)
    return true;
  m_output.clear();
  m_responses.clear();
  m_lossNotifiedForProcess = false;
  m_process.setProgram(m_program);
  m_process.setArguments(m_arguments);
  m_process.setProcessChannelMode(QProcess::SeparateChannels);
  m_process.setStandardErrorFile(QProcess::nullDevice());
  m_process.start(QIODevice::ReadWrite);
  if (!m_process.waitForStarted(2'000)) {
    quarantine(QStringLiteral("Hamlib helper failed to start"));
    return false;
  }
  return true;
}

bool HamlibHelperTransport::emergencyStop() {
  if (!m_routeConfigured)
    return false;
  QProcess emergency;
  emergency.setProgram(m_program);
  emergency.setArguments(m_arguments);
  emergency.setProcessChannelMode(QProcess::SeparateChannels);
  emergency.setStandardErrorFile(QProcess::nullDevice());
  emergency.start(QIODevice::ReadWrite);
  if (!emergency.waitForStarted(m_stopTimeoutMillis))
    return false;

  quint64 emergencyRequest = 0;
  auto exchange = [&](const QString &operation,
                      const QJsonObject &parameters) -> QJsonObject {
    const QString requestId = QStringLiteral("emergency-%1")
                                  .arg(++emergencyRequest);
    const QJsonObject frame{{"requestId", requestId},
                            {"epoch", QJsonValue::fromVariant(m_epoch)},
                            {"operation", operation},
                            {"parameters", parameters}};
    const QByteArray bytes =
        QJsonDocument(frame).toJson(QJsonDocument::Compact) + '\n';
    if (bytes.size() > MaxLineBytes || emergency.write(bytes) != bytes.size() ||
        !emergency.waitForBytesWritten(qMin(100, m_stopTimeoutMillis)))
      return {{"ok", false}, {"code", "EMERGENCY_WRITE_FAILED"}};
    QElapsedTimer elapsed;
    elapsed.start();
    QByteArray output;
    while (elapsed.elapsed() < m_stopTimeoutMillis) {
      const int remaining = m_stopTimeoutMillis - int(elapsed.elapsed());
      if (!emergency.waitForReadyRead(qMax(1, remaining)) &&
          emergency.state() == QProcess::NotRunning)
        break;
      output += emergency.readAllStandardOutput();
      if (output.size() > MaxLineBytes)
        break;
      const qsizetype newline = output.indexOf('\n');
      if (newline < 0)
        continue;
      QJsonParseError parse;
      const QJsonObject response =
          QJsonDocument::fromJson(output.left(newline), &parse).object();
      if (parse.error == QJsonParseError::NoError &&
          response.value("requestId").toString() == requestId &&
          response.value("epoch").toVariant().toULongLong() == m_epoch)
        return response;
      break;
    }
    return {{"ok", false}, {"code", "EMERGENCY_READ_FAILED"}};
  };

  const QJsonObject opened = exchange(
      QStringLiteral("open"),
      {{"modelId", m_modelId}, {"route", m_route}, {"baudRate", m_baudRate}});
  const QJsonObject stopped = opened.value("ok").toBool()
                                  ? exchange(QStringLiteral("stop"), {})
                                  : QJsonObject{{"ok", false}};
  emergency.closeWriteChannel();
  if (!emergency.waitForFinished(qMin(100, m_stopTimeoutMillis))) {
    emergency.kill();
    emergency.waitForFinished(qMin(100, m_stopTimeoutMillis));
  }
  return stopped.value("ok").toBool() &&
         stopped.value("transmitting").isBool() &&
         !stopped.value("transmitting").toBool(true);
}

void HamlibHelperTransport::consumeOutput() {
  m_output += m_process.readAllStandardOutput();
  if (m_output.size() > MaxLineBytes * 2) {
    quarantine(QStringLiteral("Hamlib helper output exceeded safety bound"));
    return;
  }
  while (true) {
    const qsizetype newline = m_output.indexOf('\n');
    if (newline < 0)
      return;
    const QByteArray line = m_output.left(newline);
    m_output.remove(0, newline + 1);
    if (line.isEmpty() || line.size() > MaxLineBytes) {
      quarantine(QStringLiteral("Hamlib helper emitted an invalid frame"));
      return;
    }
    QJsonParseError parse;
    const QJsonObject response =
        QJsonDocument::fromJson(line, &parse).object();
    const QString requestId = response.value("requestId").toString();
    if (parse.error != QJsonParseError::NoError || requestId.isEmpty()) {
      quarantine(QStringLiteral("Hamlib helper emitted malformed JSON"));
      return;
    }
    m_responses.insert(requestId, response);
  }
}

void HamlibHelperTransport::quarantine(const QString &reason) {
  const bool notifyUnsafeLoss = m_routeConfigured &&
                                !m_intentionalTermination &&
                                !m_lossNotifiedForProcess;
  ++m_epoch;
  m_quarantined = true;
  m_operationActive = false;
  if (m_process.state() != QProcess::NotRunning) {
    m_process.kill();
    m_process.waitForFinished(1'000);
  }
  emit sanitizedError(reason.left(200));
  if (notifyUnsafeLoss) {
    m_lossNotifiedForProcess = true;
    emit unsafeOwnershipLost();
  }
}

QJsonObject HamlibHelperTransport::request(const QString &operation,
                                           const QJsonObject &parameters,
                                           int timeoutMillis,
                                           bool allowWhileQuarantined) {
  if ((!allowWhileQuarantined && m_quarantined) || !startProcess())
    return {{"ok", false}, {"code", "HELPER_QUARANTINED"}};
  const quint64 requestEpoch = m_epoch;
  const QString requestId = QString::number(++m_nextRequest);
  const QJsonObject frame{{"requestId", requestId},
                          {"epoch", QJsonValue::fromVariant(requestEpoch)},
                          {"operation", operation},
                          {"parameters", parameters}};
  const QByteArray bytes =
      QJsonDocument(frame).toJson(QJsonDocument::Compact) + '\n';
  if (bytes.size() > MaxLineBytes || m_process.write(bytes) != bytes.size()) {
    quarantine(QStringLiteral("Hamlib helper command write failed"));
    return {{"ok", false}, {"code", "HELPER_WRITE_FAILED"}};
  }
  m_process.waitForBytesWritten(100);

  QEventLoop loop;
  QTimer watchdog;
  watchdog.setSingleShot(true);
  connect(&watchdog, &QTimer::timeout, &loop, &QEventLoop::quit);
  const auto outputConnection = connect(
      &m_process, &QProcess::readyReadStandardOutput, &loop,
      [this, requestId, &loop] {
        consumeOutput();
        if (m_responses.contains(requestId))
          loop.quit();
      });
  const auto exitConnection = connect(
      &m_process, qOverload<int, QProcess::ExitStatus>(&QProcess::finished),
      &loop, &QEventLoop::quit);
  watchdog.start(timeoutMillis);
  if (!m_responses.contains(requestId))
    loop.exec(QEventLoop::ExcludeUserInputEvents);
  disconnect(outputConnection);
  disconnect(exitConnection);

  if (requestEpoch != m_epoch)
    return {{"ok", false}, {"code", "OPERATION_CANCELLED"}};
  if (!m_responses.contains(requestId)) {
    quarantine(QStringLiteral("Hamlib helper operation watchdog expired"));
    return {{"ok", false}, {"code", "OPERATION_WATCHDOG"}};
  }
  return m_responses.take(requestId);
}

bool HamlibHelperTransport::open(int modelId, const QString &route, int baudRate,
                                 QJsonObject *description) {
  close();
  ++m_epoch;
  m_quarantined = false;
  const QJsonObject opened = request(
      QStringLiteral("open"),
      {{"modelId", modelId}, {"route", route}, {"baudRate", baudRate}},
      m_operationTimeoutMillis, true);
  if (!opened.value("ok").toBool()) {
    quarantine(QStringLiteral("Hamlib helper could not open the radio (%1)")
                   .arg(opened.value("code").toString("UNKNOWN")));
    return false;
  }
  m_modelId = modelId;
  m_route = route;
  m_baudRate = baudRate;
  m_routeConfigured = true;
  // Every new helper epoch begins with an explicit RX proof. No generic
  // mutation is accepted until the helper has observed PTT off.
  const QJsonObject stopped = request(QStringLiteral("stop"), {},
                                      m_stopTimeoutMillis, true);
  if (!stopped.value("ok").toBool() ||
      stopped.value("transmitting").toBool(true)) {
    quarantine(QStringLiteral("Hamlib helper could not verify RX (%1)")
                   .arg(stopped.value("code").toString("UNKNOWN")));
    return false;
  }
  m_quarantined = false;
  if (description)
    *description = opened;
  return true;
}

QJsonObject HamlibHelperTransport::mutate(const QString &action,
                                          const QJsonObject &parameters) {
  if (m_operationActive)
    return {{"ok", false}, {"code", "OPERATION_BUSY"}};
  m_operationActive = true;
  const QJsonObject response = request(QStringLiteral("mutate"),
                                       {{"action", action},
                                        {"parameters", parameters}},
                                       m_operationTimeoutMillis);
  m_operationActive = false;
  return response;
}

QJsonObject HamlibHelperTransport::snapshot() {
  if (m_operationActive)
    return {{"ok", false}, {"code", "OPERATION_BUSY"}};
  if (m_process.state() == QProcess::NotRunning)
    return {{"ok", false}, {"code", "HELPER_QUARANTINED"}};
  return request(QStringLiteral("snapshot"), {}, m_operationTimeoutMillis,
                 true);
}

QJsonObject HamlibHelperTransport::setPtt(bool enabled) {
  if (m_quarantined && m_process.state() == QProcess::NotRunning)
    return {{"ok", false}, {"code", "HELPER_QUARANTINED"}};
  return request(QStringLiteral("ptt"), {{"enabled", enabled}},
                 m_stopTimeoutMillis, !enabled);
}

bool HamlibHelperTransport::priorityStop() {
  if (m_stopInProgress)
    return false;
  m_stopInProgress = true;
  ++m_epoch;
  m_quarantined = true;
  // Terminate the possibly hung owner before opening an independently
  // supervised emergency owner. No request is queued behind the hung call.
  bool primaryOwnerTerminated = true;
  if (m_process.state() != QProcess::NotRunning) {
    m_intentionalTermination = true;
    m_process.kill();
    m_process.waitForFinished(qMin(100, m_stopTimeoutMillis));
    primaryOwnerTerminated = m_process.state() == QProcess::NotRunning;
    m_intentionalTermination = false;
  }
  const bool verified = primaryOwnerTerminated && emergencyStop();
  if (!verified) {
    m_operationActive = false;
    m_stopInProgress = false;
    emit sanitizedError(QStringLiteral(
        "Priority STOP could not independently verify RX"));
    return false;
  }
  m_operationActive = false;
  // STOP is a durable command boundary. Even after RX is verified, an
  // explicit reopen (and its second RX proof) is required before commands
  // from a possibly stale socket queue may resume.
  m_quarantined = true;
  m_stopInProgress = false;
  return true;
}

void HamlibHelperTransport::close() {
  ++m_epoch;
  m_quarantined = true;
  m_operationActive = false;
  m_stopInProgress = false;
  m_routeConfigured = false;
  m_route.clear();
  if (m_process.state() == QProcess::NotRunning)
    return;
  m_intentionalTermination = true;
  const QJsonObject frame{{"requestId", QString::number(++m_nextRequest)},
                          {"epoch", QJsonValue::fromVariant(m_epoch)},
                          {"operation", "close"},
                          {"parameters", QJsonObject{}}};
  m_process.write(QJsonDocument(frame).toJson(QJsonDocument::Compact) + '\n');
  m_process.closeWriteChannel();
  if (!m_process.waitForFinished(250)) {
    m_process.kill();
    m_process.waitForFinished(1'000);
  }
  m_intentionalTermination = false;
  m_output.clear();
  m_responses.clear();
}

} // namespace shackcq::desktop
